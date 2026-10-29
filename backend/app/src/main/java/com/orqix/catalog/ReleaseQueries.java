package com.orqix.catalog;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Read access to immutable releases for other modules (runtime). Releases never change after insert, so lookups are
 * cached by id.
 */
@Service
public class ReleaseQueries {

    /** Immutable release facts needed at run time. */
    public record ReleaseView(UUID id, UUID workspaceId, UUID workflowId, String workflowKey, String workflowName,
            int releaseNumber, JsonNode definition, String processDefinitionId, String engineDeploymentId,
            Instant publishedAt, JsonNode sourceMap, JsonNode manifest) {

        /** Business label of a canonical node or of a generated helper (mapped through the source map). */
        public String nodeLabel(String elementId) {
            if (elementId == null) {
                return null;
            }
            String canonical = sourceMap == null ? elementId
                    : sourceMap.path("bpmnToCanonical").path(elementId).asString(elementId);
            JsonNode node = node(canonical);
            return node == null ? null : node.path("label").asString(null);
        }

        /**
         * The child release a "Start another workflow" step is pinned to (S12), from the release manifest; null when
         * the release has no such pin.
         */
        public JsonNode childPin(String nodeId) {
            for (JsonNode pin : manifest.path("workflows")) {
                if (nodeId.equals(pin.path("nodeId").asString(null))) {
                    return pin;
                }
            }
            return null;
        }

        public JsonNode inputSchema() {
            return definition.path("inputSchema");
        }

        public JsonNode inputUi() {
            return definition.path("inputUi");
        }

        public boolean selfApprovalAllowedByWorkflow() {
            return "allow".equals(definition.path("security").path("selfApproval").asString(""));
        }

        public int maxConcurrentRuns() {
            return definition.path("execution").path("maxConcurrentRuns").asInt(Integer.MAX_VALUE);
        }

        public int maxAttempts() {
            return definition.path("execution").path("maxAttempts").asInt(1);
        }

        public JsonNode node(String nodeId) {
            for (JsonNode node : definition.path("nodes")) {
                if (nodeId.equals(node.path("id").asString(null))) {
                    return node;
                }
            }
            return null;
        }

        public JsonNode decisionTable(String key) {
            for (JsonNode table : definition.path("decisions")) {
                if (key.equals(table.path("key").asString(null))) {
                    return table;
                }
            }
            return null;
        }
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Map<UUID, ReleaseView> cache = new ConcurrentHashMap<>();

    public ReleaseQueries(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Optional<ReleaseView> find(UUID workspaceId, UUID releaseId) {
        ReleaseView cached = cache.get(releaseId);
        if (cached != null) {
            return cached.workspaceId().equals(workspaceId) ? Optional.of(cached) : Optional.empty();
        }
        Optional<ReleaseView> loaded = jdbc.sql("""
                SELECT r.id, r.workspace_id, r.workflow_id, w.key AS workflow_key, w.name AS workflow_name,
                       r.release_number, r.definition::text AS definition, r.process_definition_id,
                       r.engine_deployment_id, r.published_at, r.source_map::text AS source_map,
                       r.manifest::text AS manifest
                FROM workflow_release r JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                WHERE r.workspace_id = :ws AND r.id = :id
                """)
                .param("ws", workspaceId).param("id", releaseId)
                .query((rs, n) -> new ReleaseView(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"),
                        Sql.uuid(rs, "workflow_id"), rs.getString("workflow_key"), rs.getString("workflow_name"),
                        rs.getInt("release_number"), json.parse(rs.getString("definition")),
                        rs.getString("process_definition_id"), rs.getString("engine_deployment_id"),
                        Sql.instant(rs, "published_at"), json.parse(rs.getString("source_map")),
                        json.parse(rs.getString("manifest"))))
                .optional();
        loaded.ifPresent(view -> cache.put(view.id(), view));
        return loaded;
    }

    /** Workflow facts needed to start runs (never cached: the active release changes). */
    public record WorkflowRef(UUID id, UUID workspaceId, String key, String name, UUID activeReleaseId) {
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowRef> workflow(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("""
                SELECT id, workspace_id, key, name, active_release_id FROM workflow
                WHERE workspace_id = :ws AND id = :id AND archived_at IS NULL
                """)
                .param("ws", workspaceId).param("id", workflowId)
                .query((rs, n) -> new WorkflowRef(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("key"),
                        rs.getString("name"), Sql.uuid(rs, "active_release_id")))
                .optional();
    }

    /** Locks the workflow row against release changes (they take FOR UPDATE); starts of one workflow do not wait for each other here. The open-request limit has its own lock (RunQuota). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<WorkflowRef> lockWorkflow(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("""
                SELECT id, workspace_id, key, name, active_release_id FROM workflow
                WHERE workspace_id = :ws AND id = :id AND archived_at IS NULL FOR SHARE
                """)
                .param("ws", workspaceId).param("id", workflowId)
                .query((rs, n) -> new WorkflowRef(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("key"),
                        rs.getString("name"), Sql.uuid(rs, "active_release_id")))
                .optional();
    }

    /** The canary release of a workflow and its share of new starts, if one is set (PL02). */
    public record Canary(UUID releaseId, int percent) {
    }

    @Transactional(readOnly = true)
    public Optional<Canary> canary(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("SELECT release_id, percent FROM workflow_canary WHERE workspace_id = :ws AND workflow_id = :wf")
                .param("ws", workspaceId).param("wf", workflowId)
                .query((rs, n) -> new Canary(Sql.uuid(rs, "release_id"), rs.getInt("percent"))).optional();
    }

    /** Whether a new run uses the canary: a stable function of the run id, about {@code percent} of 100. */
    public static boolean inCanaryShare(UUID runId, int percent) {
        return Math.floorMod(Long.hashCode(runId.getLeastSignificantBits() ^ runId.getMostSignificantBits()), 100) < percent;
    }

    /** Releases of one workflow, newest first. */
    @Transactional(readOnly = true)
    public java.util.List<UUID> releaseIds(UUID workspaceId, UUID workflowId, int limit) {
        return jdbc.sql("""
                SELECT id FROM workflow_release WHERE workspace_id = :ws AND workflow_id = :wf
                ORDER BY release_number DESC LIMIT :limit
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("limit", limit)
                .query((rs, n) -> Sql.uuid(rs, "id")).list();
    }

    public ReleaseView require(UUID workspaceId, UUID releaseId) {
        return find(workspaceId, releaseId)
                .orElseThrow(() -> new IllegalStateException("Release " + releaseId + " not found in workspace"));
    }
}
