package com.orqix.integration.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.Ids;
import com.orqix.shared.web.Sql;

/**
 * Connection health (CG-C09, docs/build/S131): every call attempt, deferral and credential failure of a binding is
 * kept for 24 hours in {@code connection_call} (outcome, status, error code, duration; never payloads, headers or
 * secrets). The summary gives calls and success rate for the last hour and day, p50 and p95 latency, the 429 count,
 * token refresh failures and the last error, and derives operator notices from thresholds.
 */
@Service
public class ConnectionHealth {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHealth.class);

    /** Notice thresholds: success rate under 90 percent over at least 10 calls in the last hour. */
    static final int MIN_CALLS_FOR_RATE = 10;
    static final double LOW_SUCCESS_RATE = 0.9;

    /** One outcome of a call that went out, or of one that did not ({@code deferred}, {@code tokenFailure}). */
    public enum Outcome {
        SUCCEEDED("succeeded"), RETRYABLE("retryable"), PERMANENT("permanent"), UNKNOWN("unknown"),
        DEFERRED("deferred"), TOKEN_FAILURE("tokenFailure");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** @param successRate between 0 and 1, null without calls; latencies in milliseconds, null without calls */
    public record Window(int calls, int succeeded, Double successRate, Integer p50Ms, Integer p95Ms, int rateLimited,
            int tokenRefreshFailures, int deferred) {
    }

    public record LastError(String errorCode, Integer httpStatus, String outcome, Instant at) {
    }

    /** severity: info, warning or critical. */
    public record Notice(String code, String severity, String message) {
    }

    public record Summary(Window lastHour, Window lastDay, LastError lastError) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public ConnectionHealth(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records one outcome in its own transaction, so it is kept when the job rolls back. Never fails the call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID workspaceId, String connectionKey, String operation, Outcome outcome, Integer httpStatus,
            String errorCode, Long durationMs) {
        try {
            jdbc.sql("""
                    INSERT INTO connection_call (id, workspace_id, connection_key, operation, outcome, http_status,
                                                 error_code, duration_ms, at)
                    VALUES (:id, :ws, :key, :op, :outcome, :status, :code, :ms, :at)
                    """)
                    .param("id", Ids.newId()).param("ws", workspaceId).param("key", connectionKey)
                    .param("op", operation).param("outcome", outcome.value()).param("status", httpStatus)
                    .param("code", errorCode == null ? null : errorCode.length() > 64 ? errorCode.substring(0, 64)
                            : errorCode)
                    .param("ms", durationMs == null ? null : (int) Math.min(Integer.MAX_VALUE, durationMs))
                    .param("at", Sql.ts(clock.instant())).update();
        } catch (RuntimeException e) {
            log.warn("Connection health for {} not recorded: {}", connectionKey, e.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID workspaceId, String connectionKey) {
        Instant now = clock.instant();
        return new Summary(window(workspaceId, connectionKey, now.minus(Duration.ofHours(1))),
                window(workspaceId, connectionKey, now.minus(Duration.ofHours(24))),
                lastError(workspaceId, connectionKey, now.minus(Duration.ofHours(24))));
    }

    /** Notices from the circuit state and the last hour (doc 29 section 6.12); most severe first. */
    public static List<Notice> notices(String name, Summary summary, ConnectionCircuit.Status circuit,
            Integer maxRequestsPerMinute) {
        List<Notice> notices = new ArrayList<>();
        if (circuit != null && circuit.state() == ConnectionCircuit.State.OPEN) {
            notices.add(new Notice("CIRCUIT_OPEN", "critical", "Calls to " + name + " are paused until "
                    + circuit.openUntil() + " after repeated failures (" + circuit.reason() + "). Requests wait"
                    + " without using their attempts; one trial call then decides."));
        } else if (circuit != null && circuit.state() == ConnectionCircuit.State.HALF_OPEN) {
            notices.add(new Notice("CIRCUIT_TRIAL", "warning", "Calls to " + name + " were paused; a trial call now"
                    + " decides whether they resume."));
        }
        Window hour = summary.lastHour();
        if (hour.tokenRefreshFailures() > 0) {
            notices.add(new Notice("TOKEN_REFRESH_FAILING", "critical", "The credential for " + name + " could not"
                    + " be obtained " + hour.tokenRefreshFailures() + " time(s) in the last hour. Check the OAuth"
                    + " client or authorize the connection again."));
        }
        if (hour.calls() >= MIN_CALLS_FOR_RATE && hour.successRate() != null
                && hour.successRate() < LOW_SUCCESS_RATE) {
            notices.add(new Notice("LOW_SUCCESS_RATE", "warning", Math.round(hour.successRate() * 100)
                    + " percent of " + hour.calls() + " calls to " + name + " succeeded in the last hour."));
        }
        if (hour.rateLimited() > 0) {
            notices.add(new Notice("RATE_LIMITED_BY_SYSTEM", "warning", name + " declined " + hour.rateLimited()
                    + " call(s) as too many requests (HTTP 429) in the last hour."
                    + (maxRequestsPerMinute == null ? " Consider setting a request limit for this binding." : "")));
        }
        if (hour.deferred() > 0 && (circuit == null || circuit.state() == ConnectionCircuit.State.CLOSED)) {
            notices.add(new Notice("CALLS_DEFERRED", "info", hour.deferred() + " call(s) to " + name + " waited in"
                    + " the last hour for the request limit or a paused connection."));
        }
        return notices;
    }

    /** Removes outcomes older than a day. */
    @Scheduled(fixedDelayString = "${orqix.integration.health-retention-delay:1h}", initialDelayString = "${orqix.integration.health-retention-delay:1h}")
    @Transactional
    public int prune() {
        return jdbc.sql("DELETE FROM connection_call WHERE at < :before")
                .param("before", Sql.ts(clock.instant().minus(Duration.ofHours(24)))).update();
    }

    private Window window(UUID workspaceId, String connectionKey, Instant since) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE outcome IN ('succeeded', 'retryable', 'permanent', 'unknown')) AS calls,
                       count(*) FILTER (WHERE outcome = 'succeeded') AS succeeded,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms)
                           FILTER (WHERE duration_ms IS NOT NULL) AS p50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
                           FILTER (WHERE duration_ms IS NOT NULL) AS p95,
                       count(*) FILTER (WHERE http_status = 429) AS limited,
                       count(*) FILTER (WHERE outcome = 'tokenFailure') AS token,
                       count(*) FILTER (WHERE outcome = 'deferred') AS deferred
                FROM connection_call
                WHERE workspace_id = :ws AND connection_key = :key AND at > :since
                """)
                .param("ws", workspaceId).param("key", connectionKey).param("since", Sql.ts(since))
                .query((rs, n) -> {
                    int calls = rs.getInt("calls");
                    int succeeded = rs.getInt("succeeded");
                    Object p50 = rs.getObject("p50");
                    Object p95 = rs.getObject("p95");
                    return new Window(calls, succeeded, calls == 0 ? null : (double) succeeded / calls,
                            p50 == null ? null : (int) Math.round(((Number) p50).doubleValue()),
                            p95 == null ? null : (int) Math.round(((Number) p95).doubleValue()),
                            rs.getInt("limited"), rs.getInt("token"), rs.getInt("deferred"));
                })
                .single();
    }

    private LastError lastError(UUID workspaceId, String connectionKey, Instant since) {
        return jdbc.sql("""
                SELECT error_code, http_status, outcome, at FROM connection_call
                WHERE workspace_id = :ws AND connection_key = :key AND at > :since
                  AND outcome IN ('retryable', 'permanent', 'unknown', 'tokenFailure')
                ORDER BY at DESC LIMIT 1
                """)
                .param("ws", workspaceId).param("key", connectionKey).param("since", Sql.ts(since))
                .query((rs, n) -> new LastError(rs.getString("error_code"), (Integer) rs.getObject("http_status"),
                        rs.getString("outcome"), Sql.instant(rs, "at")))
                .optional().orElse(null);
    }
}
