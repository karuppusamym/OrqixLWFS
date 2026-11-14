package com.orqix.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * The per-workspace MCP server switch (S136 section 4) and the recent-calls view. Off until a workspace administrator
 * ({@code workspace.manage-access}) switches it on; read on every call without a cache, so a change applies on every
 * replica at the next call; every change audited ({@code mcp.server.updated}).
 */
@Service
public class McpServerSwitch {

    public record SubjectRef(UUID id, String displayName) {
    }

    /** getMcpServer / setMcpServer response. */
    public record State(boolean enabled, String endpoint, String metadataUrl, List<String> audiences, int callsPerMinute,
            int confirmationSeconds, Instant updatedAt, SubjectRef updatedBy) {
    }

    /** One recent tool call: the audit record (tool, step, outcome and ids; never arguments or results). */
    public record Call(Instant occurredAt, SubjectRef actor, String tool, String step, String outcome, String errorCode,
            UUID runId, String taskId, UUID workflowId, UUID confirmationId) {
    }

    public record CallList(List<Call> items) {
    }

    private record Row(boolean enabled, Instant updatedAt, SubjectRef updatedBy) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final McpSettings settings;
    private final Json json;
    private final Clock clock;

    public McpServerSwitch(JdbcClient jdbc, PolicyService policy, AuditWriter audit, McpSettings settings, Json json,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
    }

    public boolean enabled(UUID workspaceId) {
        return jdbc.sql("SELECT enabled FROM mcp_workspace_setting WHERE workspace_id = :ws").param("ws", workspaceId)
                .query(Boolean.class).optional().orElse(false);
    }

    /** The workspaces among {@code workspaceIds} where the MCP server is on. */
    public Set<UUID> enabledAmong(Collection<UUID> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT workspace_id FROM mcp_workspace_setting WHERE workspace_id IN (:ws) AND enabled")
                .param("ws", List.copyOf(workspaceIds)).query(UUID.class).list());
    }

    @Transactional(readOnly = true)
    public State get(Caller caller, UUID workspaceId, String endpoint, String metadataUrl) {
        requireAdmin(caller, workspaceId, "getMcpServer", false);
        return read(workspaceId, endpoint, metadataUrl);
    }

    /** Setting the current value again writes nothing. */
    @Transactional
    public State set(Caller caller, UUID workspaceId, boolean enabled, String endpoint, String metadataUrl) {
        requireAdmin(caller, workspaceId, "setMcpServer", true);
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", workspaceId).query(UUID.class).optional();
        if (enabled(workspaceId) != enabled) {
            jdbc.sql("""
                    INSERT INTO mcp_workspace_setting (workspace_id, enabled, updated_by, updated_at)
                    VALUES (:ws, :enabled, :by, :now)
                    ON CONFLICT (workspace_id) DO UPDATE SET enabled = :enabled, updated_by = :by, updated_at = :now
                    """).param("ws", workspaceId).param("enabled", enabled).param("by", caller.subjectId())
                    .param("now", Sql.ts(clock.instant())).update();
            audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                    "mcp.server.updated", "workspace", workspaceId.toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("enabled", enabled)));
        }
        return read(workspaceId, endpoint, metadataUrl);
    }

    /** listMcpCalls: newest first, at most {@code limit} (1..100, default 50). */
    @Transactional(readOnly = true)
    public CallList calls(Caller caller, UUID workspaceId, Integer limit) {
        requireAdmin(caller, workspaceId, "listMcpCalls", false);
        int size = limit == null ? 50 : Math.max(1, Math.min(100, limit));
        List<Call> items = jdbc.sql("""
                SELECT a.occurred_at, a.actor_subject_id, s.display_name, a.outcome, a.metadata::text AS metadata
                FROM audit_event a LEFT JOIN subject s ON s.id = a.actor_subject_id
                WHERE a.workspace_id = :ws AND a.action = 'mcp.tool-called'
                ORDER BY a.occurred_at DESC, a.id DESC
                LIMIT :limit
                """).param("ws", workspaceId).param("limit", size)
                .query((rs, n) -> {
                    JsonNode m = json.parse(rs.getString("metadata"));
                    UUID actor = Sql.uuid(rs, "actor_subject_id");
                    return new Call(Sql.instant(rs, "occurred_at"),
                            actor == null ? null : new SubjectRef(actor, rs.getString("display_name")),
                            m.path("tool").asString(""), m.path("step").asString(null),
                            m.path("outcome").asString(rs.getString("outcome")), m.path("errorCode").asString(null),
                            uuid(m.path("runId").asString(null)), m.path("taskId").asString(null),
                            uuid(m.path("workflowId").asString(null)), uuid(m.path("confirmationId").asString(null)));
                }).list();
        return new CallList(items);
    }

    private void requireAdmin(Caller caller, UUID workspaceId, String operationId, boolean audited) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, operationId, audited);
    }

    private State read(UUID workspaceId, String endpoint, String metadataUrl) {
        Optional<Row> row = jdbc.sql("""
                SELECT m.enabled, m.updated_at, m.updated_by, s.display_name
                FROM mcp_workspace_setting m LEFT JOIN subject s ON s.id = m.updated_by
                WHERE m.workspace_id = :ws
                """).param("ws", workspaceId).query((rs, n) -> {
                    UUID by = Sql.uuid(rs, "updated_by");
                    return new Row(rs.getBoolean("enabled"), Sql.instant(rs, "updated_at"),
                            by == null ? null : new SubjectRef(by, rs.getString("display_name")));
                }).optional();
        return new State(row.map(Row::enabled).orElse(false), endpoint, metadataUrl, settings.audiences(),
                settings.callsPerMinute(), (int) settings.confirmationTtl().toSeconds(),
                row.map(Row::updatedAt).orElse(null), row.map(Row::updatedBy).orElse(null));
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
