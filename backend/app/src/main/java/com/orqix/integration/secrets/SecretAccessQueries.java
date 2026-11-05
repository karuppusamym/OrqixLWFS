package com.orqix.integration.secrets;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.problem.ApiException;

/** Read side of the secret-access audit (docs/build/S132): counts per reference and provider, with the bindings using it. */
@Service
public class SecretAccessQueries {

    public record Entry(String secretRef, String provider, List<String> connectionKeys, long resolutions,
            long cacheHits, long notFound, long failures, Instant lastResolvedAt, Instant lastFailedAt) {
    }

    public record Report(LocalDate since, List<Entry> items) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final SecretAccessAudit audit;
    private final Clock clock;

    public SecretAccessQueries(JdbcClient jdbc, PolicyService policy, SecretAccessAudit audit, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /** @param days how many UTC days to sum, today included (1..90) */
    public Report report(Caller caller, UUID workspaceId, int days) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.CONNECTION_MANAGE, "getSecretAccess", false);
        if (days < 1 || days > 90) {
            throw new ApiException(com.orqix.shared.problem.ProblemCode.VALIDATION_FAILED, "Use 1 to 90 days.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/days", "range", "Use 1 to 90 days.")));
        }
        audit.flush();
        LocalDate since = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(days - 1L);
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT secret_ref, connection_key FROM connection_binding
                WHERE workspace_id = :ws AND secret_ref IS NOT NULL ORDER BY connection_key
                """).param("ws", workspaceId).query((rs, n) -> {
                    bindings.computeIfAbsent(rs.getString("secret_ref"), k -> new ArrayList<>()).add(rs.getString("connection_key"));
                    return null;
                }).list();
        List<Entry> items = jdbc.sql("""
                SELECT secret_ref, provider, SUM(resolutions) AS resolutions, SUM(cache_hits) AS cache_hits,
                       SUM(not_found) AS not_found, SUM(failures) AS failures,
                       MAX(last_resolved_at) AS last_resolved_at, MAX(last_failed_at) AS last_failed_at
                FROM secret_access_count WHERE workspace_id = :ws AND day >= :since
                GROUP BY secret_ref, provider ORDER BY secret_ref, provider
                """).param("ws", workspaceId).param("since", since)
                .query((rs, n) -> new Entry(rs.getString("secret_ref"), rs.getString("provider"),
                        List.copyOf(bindings.getOrDefault(rs.getString("secret_ref"), List.of())),
                        rs.getLong("resolutions"), rs.getLong("cache_hits"), rs.getLong("not_found"), rs.getLong("failures"),
                        instant(rs.getTimestamp("last_resolved_at")), instant(rs.getTimestamp("last_failed_at"))))
                .list();
        return new Report(since, items);
    }

    private static Instant instant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
