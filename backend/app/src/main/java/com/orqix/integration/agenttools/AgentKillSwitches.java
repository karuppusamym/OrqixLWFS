package com.orqix.integration.agenttools;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.ConnectionService;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/**
 * Per-agent and per-server kill switches (CG-M03, S135). Read before every execution, every durable turn and every
 * call to a server's tools, so switching one on stops new calls immediately (a call already in flight finishes).
 */
@Service
public class AgentKillSwitches {

    public static final String AGENT = "agent";
    public static final String SERVER = "server";

    public record Switch(String scope, String connectionKey, boolean active, String reason, UUID changedBy,
            Instant changedAt, int version) {
    }

    public record SetRequest(@NotNull Boolean active, @NotNull @Size(max = 500) String reason,
            @Min(1) Integer expectedVersion) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final ConnectionService connections;

    public AgentKillSwitches(JdbcClient jdbc, Clock clock, PolicyService policy, AuditWriter audit,
            ConnectionService connections) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.policy = policy;
        this.audit = audit;
        this.connections = connections;
    }

    /** True while the switch for this agent or server is on. */
    public boolean stopped(UUID workspaceId, String scope, String key) {
        return jdbc.sql("SELECT count(*) FROM agent_kill_switch WHERE workspace_id = :ws AND scope = :scope "
                + "AND connection_key = :key AND active").param("ws", workspaceId).param("scope", scope)
                .param("key", key).query(Integer.class).single() > 0;
    }

    @Transactional(readOnly = true)
    public List<Switch> list(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.access(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        if (!access.has(Permission.CONNECTION_READ) && !access.has(Permission.RUN_OPERATE)) {
            policy.require(access, Permission.CONNECTION_READ, "listAgentKillSwitches", false);
        }
        return all(workspaceId);
    }

    public List<Switch> all(UUID workspaceId) {
        return jdbc.sql("""
                SELECT scope, connection_key, active, reason, changed_by, changed_at, version FROM agent_kill_switch
                WHERE workspace_id = :ws ORDER BY active DESC, changed_at DESC
                """).param("ws", workspaceId)
                .query((rs, n) -> new Switch(rs.getString("scope"), rs.getString("connection_key"),
                        rs.getBoolean("active"), rs.getString("reason"), Sql.uuid(rs, "changed_by"),
                        Sql.instant(rs, "changed_at"), rs.getInt("version")))
                .list();
    }

    /** {@code setAgentKillSwitch}: fresh connection.manage, reason required, version-checked when the row exists. */
    @Transactional
    public Switch set(Caller caller, UUID workspaceId, String scope, String key, SetRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "setAgentKillSwitch", true);
        if (!AGENT.equals(scope) && !SERVER.equals(scope)) {
            throw ApiException.notFound();
        }
        boolean matches = connections.definitions(workspaceId, key).stream().anyMatch(d -> AGENT.equals(scope)
                ? d.definition().isAgent() : d.definition().isMcpServer());
        if (!matches) {
            throw ApiException.notFound();
        }
        String reason = request.reason().strip();
        if (reason.isEmpty()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Say why the switch is changed.");
        }
        Integer current = jdbc.sql("SELECT version FROM agent_kill_switch WHERE workspace_id = :ws AND scope = :scope "
                + "AND connection_key = :key FOR UPDATE").param("ws", workspaceId).param("scope", scope)
                .param("key", key).query(Integer.class).optional().orElse(null);
        Instant now = clock.instant();
        if (current == null) {
            jdbc.sql("""
                    INSERT INTO agent_kill_switch (workspace_id, scope, connection_key, active, reason, changed_by,
                        changed_at) VALUES (:ws, :scope, :key, :active, :reason, :by, :at)
                    """).param("ws", workspaceId).param("scope", scope).param("key", key)
                    .param("active", request.active()).param("reason", reason).param("by", access.subjectId())
                    .param("at", Sql.ts(now)).update();
        } else {
            if (request.expectedVersion() == null || request.expectedVersion() != current) {
                throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                        "The switch was changed by someone else. Reload and try again.");
            }
            jdbc.sql("""
                    UPDATE agent_kill_switch SET active = :active, reason = :reason, changed_by = :by, changed_at = :at,
                        version = version + 1
                    WHERE workspace_id = :ws AND scope = :scope AND connection_key = :key
                    """).param("ws", workspaceId).param("scope", scope).param("key", key)
                    .param("active", request.active()).param("reason", reason).param("by", access.subjectId())
                    .param("at", Sql.ts(now)).update();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", scope);
        metadata.put("connectionKey", key);
        metadata.put("active", request.active());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, access.subjectId(),
                "agent.kill-switch.changed", "connection", key, AuditWriter.Outcome.SUCCESS, metadata));
        return all(workspaceId).stream().filter(s -> s.scope().equals(scope) && s.connectionKey().equals(key))
                .findFirst().orElseThrow();
    }
}
