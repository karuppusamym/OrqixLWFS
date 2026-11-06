package com.orqix.integration.agenttools;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.ConnectionDefinition;
import com.orqix.integration.McpClient;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Tool catalog pins of registered MCP servers (CG-M01, S135). An administrator takes a snapshot of the server behind a
 * workspace binding: for every allow-listed tool of the latest definition version it records the {@code inputSchema}
 * and the SHA-256 of its canonical JSON. At runtime every {@code tools/list} answer is compared with the pins: a tool
 * without a pin, missing from the server or with another schema hash is refused ({@code TOOL_NOT_PINNED},
 * {@code TOOL_SCHEMA_CHANGED}), recorded as drift and the administrator who pinned it is notified. A new snapshot
 * resolves the drift. The server's own descriptions are never stored or offered.
 */
@Service
public class McpToolPins {

    private static final Logger log = LoggerFactory.getLogger(McpToolPins.class);

    /** A pinned tool. */
    public record Pin(String toolName, JsonNode inputSchema, String sha256, UUID takenBy, Instant takenAt) {
    }

    /** Snapshot answer per allow-listed tool: {@code pinned} or {@code missing} (not offered by the server). */
    public record SnapshotTool(String name, String label, String status, String schemaSha256) {
    }

    public record Snapshot(String connectionKey, int definitionVersion, Instant takenAt, List<SnapshotTool> tools,
            List<Drift> drift) {
    }

    public record Drift(UUID id, String connectionKey, String toolName, String kind, String pinnedSha256,
            String observedSha256, int occurrences, Instant firstSeenAt, Instant lastSeenAt) {
    }

    /** Outcome of comparing one live tool with its pin. */
    public enum Check {
        OK, CHANGED, MISSING, UNPINNED
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final McpServers servers;
    private final McpClient mcp;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate independent;

    public McpToolPins(JdbcClient jdbc, Json json, Clock clock, PolicyService policy, AuditWriter audit,
            McpServers servers, McpClient mcp, ApplicationEventPublisher events, PlatformTransactionManager tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.audit = audit;
        this.servers = servers;
        this.mcp = mcp;
        this.events = events;
        this.independent = new TransactionTemplate(tx);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** SHA-256 of the canonical JSON of a schema (absent schemas hash as an empty object). */
    public String hash(JsonNode schema) {
        return json.canonicalSha256(schema == null || !schema.isObject() ? json.object() : schema);
    }

    public Map<String, Pin> pins(UUID workspaceId, String key, int version) {
        Map<String, Pin> pins = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT tool_name, input_schema::text AS schema, schema_sha256, taken_by, taken_at FROM mcp_tool_snapshot
                WHERE workspace_id = :ws AND connection_key = :key AND definition_version = :version
                """).param("ws", workspaceId).param("key", key).param("version", version)
                .query((rs, n) -> new Pin(rs.getString("tool_name"), json.parse(rs.getString("schema")),
                        rs.getString("schema_sha256").strip(), Sql.uuid(rs, "taken_by"), Sql.instant(rs, "taken_at")))
                .list().forEach(p -> pins.put(p.toolName(), p));
        return pins;
    }

    /** Compares a live schema with the pin; records drift (own transaction) unless it matches. */
    public Check check(UUID workspaceId, String key, Pin pin, String toolName, McpClient.Tool live) {
        if (pin == null) {
            drift(workspaceId, key, toolName, "unpinned", null, live == null ? null : hash(live.inputSchema()), null);
            return Check.UNPINNED;
        }
        if (live == null) {
            drift(workspaceId, key, toolName, "missing", pin.sha256(), null, pin.takenBy());
            return Check.MISSING;
        }
        String observed = hash(live.inputSchema());
        if (!observed.equals(pin.sha256())) {
            drift(workspaceId, key, toolName, "changed", pin.sha256(), observed, pin.takenBy());
            return Check.CHANGED;
        }
        return Check.OK;
    }

    private void drift(UUID workspaceId, String key, String tool, String kind, String pinned, String observed,
            UUID notify) {
        try {
            independent.executeWithoutResult(status -> {
                int updated = jdbc.sql("""
                        UPDATE mcp_tool_drift SET occurrences = occurrences + 1, last_seen_at = :at
                        WHERE workspace_id = :ws AND connection_key = :key AND tool_name = :tool AND kind = :kind
                          AND coalesce(observed_sha256, '') = coalesce(:observed, '') AND resolved_at IS NULL
                        """).param("ws", workspaceId).param("key", key).param("tool", tool).param("kind", kind)
                        .param("observed", observed).param("at", Sql.ts(clock.instant())).update();
                if (updated > 0) {
                    return;
                }
                jdbc.sql("""
                        INSERT INTO mcp_tool_drift (id, workspace_id, connection_key, tool_name, kind, pinned_sha256,
                            observed_sha256, first_seen_at, last_seen_at)
                        VALUES (:id, :ws, :key, :tool, :kind, :pinned, :observed, :at, :at)
                        ON CONFLICT DO NOTHING
                        """).param("id", Ids.newId()).param("ws", workspaceId).param("key", key).param("tool", tool)
                        .param("kind", kind).param("pinned", pinned).param("observed", observed)
                        .param("at", Sql.ts(clock.instant())).update();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("connectionKey", key);
                metadata.put("toolName", tool);
                metadata.put("kind", kind);
                metadata.put("pinnedSha256", pinned);
                metadata.put("observedSha256", observed);
                audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.SYSTEM, null,
                        "mcp.tool-schema-changed", "connection", key, AuditWriter.Outcome.DENIED, metadata));
                events.publishEvent(new com.orqix.integration.McpToolDriftDetected(workspaceId, key, tool, kind, notify));
            });
        } catch (RuntimeException e) {
            log.warn("Tool drift could not be recorded: {}", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ API

    /** {@code takeMcpToolSnapshot}: connects to the bound server and pins every allow-listed tool it offers. */
    @Transactional
    public Snapshot take(Caller caller, UUID workspaceId, String key) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "takeMcpToolSnapshot", true);
        ConnectionDefinition definition = servers.definition(workspaceId, key).orElseThrow(ApiException::notFound);
        McpServers.Server server;
        try {
            server = servers.server(workspaceId, key);
        } catch (McpServers.Unavailable e) {
            throw new ApiException(ProblemCode.CONNECTION_NOT_BOUND, e.getMessage());
        }
        List<McpClient.Tool> live;
        try (McpClient.Session session = mcp.connect(server.endpoint(), new McpClient.Deadline(Duration.ofSeconds(20)))) {
            live = session.listTools();
        } catch (McpClient.McpException e) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "The tool server " + definition.name()
                    + " could not list its tools (" + e.kind() + ").");
        }
        Map<String, McpClient.Tool> byName = new LinkedHashMap<>();
        live.forEach(t -> byName.putIfAbsent(t.name(), t));
        Instant now = clock.instant();
        jdbc.sql("DELETE FROM mcp_tool_snapshot WHERE workspace_id = :ws AND connection_key = :key "
                + "AND definition_version = :v").param("ws", workspaceId).param("key", key)
                .param("v", definition.version()).update();
        List<SnapshotTool> tools = new ArrayList<>();
        int pinned = 0;
        for (ConnectionDefinition.ServerTool tool : definition.mcpServer().tools()) {
            McpClient.Tool found = byName.get(tool.name());
            if (found == null) {
                tools.add(new SnapshotTool(tool.name(), tool.label(), "missing", null));
                continue;
            }
            JsonNode schema = found.inputSchema() != null && found.inputSchema().isObject() ? found.inputSchema()
                    : json.object().put("type", "object");
            String sha = hash(found.inputSchema());
            jdbc.sql("""
                    INSERT INTO mcp_tool_snapshot (workspace_id, connection_key, definition_version, tool_name,
                        input_schema, schema_sha256, taken_by, taken_at)
                    VALUES (:ws, :key, :v, :tool, CAST(:schema AS jsonb), :sha, :by, :at)
                    """).param("ws", workspaceId).param("key", key).param("v", definition.version())
                    .param("tool", tool.name()).param("schema", json.write(schema)).param("sha", sha)
                    .param("by", access.subjectId()).param("at", Sql.ts(now)).update();
            jdbc.sql("UPDATE mcp_tool_drift SET resolved_at = :at WHERE workspace_id = :ws AND connection_key = :key "
                    + "AND tool_name = :tool AND resolved_at IS NULL").param("at", Sql.ts(now)).param("ws", workspaceId)
                    .param("key", key).param("tool", tool.name()).update();
            tools.add(new SnapshotTool(tool.name(), tool.label(), "pinned", sha));
            pinned++;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("connectionKey", key);
        metadata.put("definitionVersion", definition.version());
        metadata.put("pinned", pinned);
        metadata.put("missing", tools.size() - pinned);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, access.subjectId(),
                "mcp.tool-snapshot.taken", "connection", key, AuditWriter.Outcome.SUCCESS, metadata));
        return new Snapshot(key, definition.version(), now, tools, openDrift(workspaceId, key));
    }

    /** {@code getMcpToolSnapshot}. */
    @Transactional(readOnly = true)
    public Snapshot snapshot(Caller caller, UUID workspaceId, String key) {
        WorkspaceAccess access = policy.access(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_READ, "getMcpToolSnapshot", false);
        ConnectionDefinition definition = servers.definition(workspaceId, key).orElseThrow(ApiException::notFound);
        Map<String, Pin> pins = pins(workspaceId, key, definition.version());
        List<SnapshotTool> tools = new ArrayList<>();
        Instant taken = null;
        for (ConnectionDefinition.ServerTool tool : definition.mcpServer().tools()) {
            Pin pin = pins.get(tool.name());
            tools.add(new SnapshotTool(tool.name(), tool.label(), pin == null ? "unpinned" : "pinned",
                    pin == null ? null : pin.sha256()));
            if (pin != null && (taken == null || pin.takenAt().isAfter(taken))) {
                taken = pin.takenAt();
            }
        }
        return new Snapshot(key, definition.version(), taken, tools, openDrift(workspaceId, key));
    }

    public List<Drift> openDrift(UUID workspaceId, String key) {
        return jdbc.sql("""
                SELECT id, connection_key, tool_name, kind, pinned_sha256, observed_sha256, occurrences, first_seen_at,
                    last_seen_at
                FROM mcp_tool_drift WHERE workspace_id = :ws AND (CAST(:key AS text) IS NULL OR connection_key = :key)
                  AND resolved_at IS NULL
                ORDER BY last_seen_at DESC LIMIT 100
                """).param("ws", workspaceId).param("key", key)
                .query((rs, n) -> new Drift(Sql.uuid(rs, "id"), rs.getString("connection_key"),
                        rs.getString("tool_name"), rs.getString("kind"), trim(rs.getString("pinned_sha256")),
                        trim(rs.getString("observed_sha256")), rs.getInt("occurrences"),
                        Sql.instant(rs, "first_seen_at"), Sql.instant(rs, "last_seen_at")))
                .list();
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }
}
