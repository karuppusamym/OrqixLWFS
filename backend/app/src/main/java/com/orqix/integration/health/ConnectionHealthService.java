package com.orqix.integration.health;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import com.orqix.shared.web.Sql;

/**
 * Reads and settings of connection health (CG-C09) and the binding request limit (CG-W04), tag ConnectionHealth in
 * contracts/openapi.yaml (docs/build/S131). Health is visible with {@code connection.read}; the request limit is set
 * with {@code connection.manage} and audited.
 */
@Service
public class ConnectionHealthService {

    public record Circuit(String state, int consecutiveFailures, int failureThreshold, Instant openedAt,
            Instant openUntil, String reason, int timesOpened) {
    }

    public record RateLimit(Integer maxRequestsPerMinute, Integer perPod, int livePods) {
    }

    public record HealthView(String connectionKey, String name, boolean bound, boolean enabled,
            ConnectionHealth.Window lastHour, ConnectionHealth.Window lastDay, ConnectionHealth.LastError lastError,
            Circuit circuit, RateLimit rateLimit, List<ConnectionHealth.Notice> notices, Instant asOf) {
    }

    public record HealthList(List<HealthView> items, int noticeCount) {
    }

    public record PutRateLimitRequest(Integer maxRequestsPerMinute) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final ConnectionService connections;
    private final ConnectionHealth health;
    private final ConnectionCircuit circuit;
    private final BindingRateLimiter limiter;
    private final AuditWriter audit;
    private final Clock clock;

    public ConnectionHealthService(JdbcClient jdbc, PolicyService policy, ConnectionService connections,
            ConnectionHealth health, ConnectionCircuit circuit, BindingRateLimiter limiter, AuditWriter audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.connections = connections;
        this.health = health;
        this.circuit = circuit;
        this.limiter = limiter;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public HealthList list(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.CONNECTION_READ, "listConnectionHealth", false);
        Map<String, String> names = new LinkedHashMap<>();
        connections.definitions(workspaceId).forEach(d -> names.put(d.key(), d.definition().name()));
        List<HealthView> items = new ArrayList<>();
        names.forEach((key, name) -> items.add(view(workspaceId, key, name)));
        int notices = items.stream().mapToInt(i -> i.notices().size()).sum();
        return new HealthList(items, notices);
    }

    /** Every connection's health without a permission check (the caller checks), for the job dashboard (S142). */
    @Transactional(readOnly = true)
    public List<HealthView> views(UUID workspaceId) {
        Map<String, String> names = new LinkedHashMap<>();
        connections.definitions(workspaceId).forEach(d -> names.put(d.key(), d.definition().name()));
        List<HealthView> items = new ArrayList<>();
        names.forEach((key, name) -> items.add(view(workspaceId, key, name)));
        return items;
    }

    @Transactional(readOnly = true)
    public HealthView get(Caller caller, UUID workspaceId, String key) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        List<ConnectionService.DefinitionRecord> versions = connections.definitions(workspaceId, key);
        if (versions.isEmpty()) {
            throw ApiException.notFound();
        }
        policy.require(access, Permission.CONNECTION_READ, "getConnectionHealth", false);
        return view(workspaceId, key, versions.get(versions.size() - 1).definition().name());
    }

    @Transactional
    public HealthView putRateLimit(Caller caller, UUID workspaceId, String key, PutRateLimitRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        List<ConnectionService.DefinitionRecord> versions = connections.definitions(workspaceId, key);
        if (versions.isEmpty()) {
            throw ApiException.notFound();
        }
        policy.require(access, Permission.CONNECTION_MANAGE, "putConnectionRateLimit", true);
        Integer limit = request == null ? null : request.maxRequestsPerMinute();
        if (limit != null && (limit < 1 || limit > 100000)) {
            throw new ApiException(com.orqix.shared.problem.ProblemCode.VALIDATION_FAILED,
                    "maxRequestsPerMinute must be between 1 and 100000, or null for no limit.");
        }
        int revision = jdbc.sql("""
                INSERT INTO connection_rate_limit (workspace_id, connection_key, max_requests_per_minute, revision,
                                                   updated_by, updated_at)
                VALUES (:ws, :key, :limit, 1, :by, :now)
                ON CONFLICT (workspace_id, connection_key) DO UPDATE
                    SET max_requests_per_minute = EXCLUDED.max_requests_per_minute,
                        revision = connection_rate_limit.revision + 1, updated_by = EXCLUDED.updated_by,
                        updated_at = EXCLUDED.updated_at
                RETURNING revision
                """)
                .param("ws", workspaceId).param("key", key).param("limit", limit).param("by", caller.subjectId())
                .param("now", Sql.ts(clock.instant())).query(Integer.class).single();
        limiter.invalidate(workspaceId, key);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("maxRequestsPerMinute", limit);
        metadata.put("revision", revision);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "connection.rate-limit.updated", "connection", key, AuditWriter.Outcome.SUCCESS, metadata));
        return view(workspaceId, key, versions.get(versions.size() - 1).definition().name());
    }

    private HealthView view(UUID workspaceId, String key, String name) {
        ConnectionHealth.Summary summary = health.summary(workspaceId, key);
        ConnectionCircuit.Status status = circuit.status(workspaceId, key).orElse(null);
        Integer limit = limiter.limit(workspaceId, key);
        int pods = limiter.pods();
        var binding = connections.binding(workspaceId, key);
        Circuit circuitView = status == null
                ? new Circuit("closed", 0, circuit.failureThreshold(), null, null, null, 0)
                : new Circuit(status.state().value(), status.consecutiveFailures(), circuit.failureThreshold(),
                        status.openedAt(), status.state() == ConnectionCircuit.State.CLOSED ? null : status.openUntil(),
                        status.state() == ConnectionCircuit.State.CLOSED ? null : status.reason(),
                        status.timesOpened());
        return new HealthView(key, name, binding.isPresent(), binding.map(ConnectionService.Binding::enabled)
                .orElse(false), summary.lastHour(), summary.lastDay(), summary.lastError(), circuitView,
                new RateLimit(limit, limit == null ? null : Math.max(1, limit / pods), pods),
                ConnectionHealth.notices(name, summary, status, limit), clock.instant());
    }
}
