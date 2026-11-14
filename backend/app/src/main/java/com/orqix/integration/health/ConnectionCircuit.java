package com.orqix.integration.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.web.Sql;

/**
 * Circuit breaker per connection binding (CG-W04, docs/build/S131). After {@code failureThreshold} consecutive failures
 * of the kind a retry may cure (not sent, maybe applied, 429) across all requests, all within {@code window}, the
 * binding opens: calls are deferred to the end of a cool-down (1 minute, doubling up to 15 minutes) without using an
 * attempt. Then one trial call decides: success closes the circuit, failure opens it again for twice the cool-down.
 * <p>
 * Every change runs in its own transaction on {@code connection_circuit} only, so it survives the job's rollback and
 * never waits on the engine's locks. The row is locked for the decision, so pods agree on who makes the trial call.
 */
@Service
public class ConnectionCircuit {

    private static final Logger log = LoggerFactory.getLogger(ConnectionCircuit.class);

    public enum State {
        CLOSED("closed"), OPEN("open"), HALF_OPEN("halfOpen");

        private final String value;

        State(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        static State of(String value) {
            for (State s : values()) {
                if (s.value.equals(value)) {
                    return s;
                }
            }
            throw new IllegalStateException("Unknown circuit state " + value);
        }
    }

    /**
     * @param allowed true when the call may go out now
     * @param trial   true when this call is the half-open trial that decides the circuit
     * @param reason  {@code CIRCUIT_OPEN} or {@code CIRCUIT_TRIAL_RUNNING} when not allowed
     * @param until   when to try again when not allowed
     */
    public record Admission(boolean allowed, boolean trial, String reason, Instant until) {
        static final Admission GO = new Admission(true, false, null, null);
    }

    /** Current state of one binding's circuit, for health and the Settings page. */
    public record Status(State state, int consecutiveFailures, Instant openedAt, Instant openUntil, String reason,
            int cooldownSeconds, int timesOpened, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final int failureThreshold;
    private final Duration window;
    private final int initialCooldownSeconds;
    private final int maxCooldownSeconds;

    public ConnectionCircuit(JdbcClient jdbc, Clock clock,
            @Value("${orqix.integration.circuit.failure-threshold:10}") int failureThreshold,
            @Value("${orqix.integration.circuit.window:5m}") Duration window,
            @Value("${orqix.integration.circuit.cooldown:1m}") Duration cooldown,
            @Value("${orqix.integration.circuit.max-cooldown:15m}") Duration maxCooldown) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.window = window;
        this.initialCooldownSeconds = (int) Math.max(1, cooldown.toSeconds());
        this.maxCooldownSeconds = (int) Math.max(initialCooldownSeconds, maxCooldown.toSeconds());
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    /**
     * Decides whether a call may go out. A closed or missing circuit always admits. An open circuit whose cool-down is
     * over becomes half-open and admits this one call as the trial; while the trial runs ({@code trialFor}) others wait.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Admission admit(UUID workspaceId, String connectionKey, Duration trialFor) {
        Optional<Row> found = lock(workspaceId, connectionKey);
        if (found.isEmpty() || found.get().state() == State.CLOSED) {
            return Admission.GO;
        }
        Row row = found.get();
        Instant now = clock.instant();
        if (row.state() == State.OPEN && row.openUntil() != null && now.isBefore(row.openUntil())) {
            return new Admission(false, false, "CIRCUIT_OPEN", row.openUntil());
        }
        if (row.state() == State.HALF_OPEN && row.trialUntil() != null && now.isBefore(row.trialUntil())) {
            // Another request is making the trial call; look again shortly after it must have finished.
            return new Admission(false, false, "CIRCUIT_TRIAL_RUNNING", row.trialUntil());
        }
        jdbc.sql("""
                UPDATE connection_circuit SET state = 'halfOpen', trial_until = :trial, updated_at = :now
                WHERE workspace_id = :ws AND connection_key = :key
                """)
                .param("trial", Sql.ts(now.plus(trialFor))).param("now", Sql.ts(now)).param("ws", workspaceId)
                .param("key", connectionKey).update();
        return new Admission(true, true, null, null);
    }

    /**
     * Records the outcome of a call that went out. {@code failure} is true for outcomes a retry may cure (not sent,
     * maybe applied, 429); a success or a definitive answer resets the count.
     *
     * @return the new state
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public State record(UUID workspaceId, String connectionKey, boolean failure, String errorCode, boolean trial) {
        Instant now = clock.instant();
        Optional<Row> found = lock(workspaceId, connectionKey);
        if (!failure) {
            if (found.isEmpty()) {
                return State.CLOSED;
            }
            Row row = found.get();
            if (row.state() == State.CLOSED && row.consecutiveFailures() == 0) {
                return State.CLOSED;
            }
            if (row.state() == State.OPEN && !trial) {
                // A call admitted before the circuit opened; it does not close it.
                return State.OPEN;
            }
            jdbc.sql("""
                    UPDATE connection_circuit SET state = 'closed', consecutive_failures = 0, first_failure_at = NULL,
                           open_until = NULL, trial_until = NULL, cooldown_seconds = :cooldown, updated_at = :now
                    WHERE workspace_id = :ws AND connection_key = :key
                    """)
                    .param("cooldown", initialCooldownSeconds).param("now", Sql.ts(now)).param("ws", workspaceId)
                    .param("key", connectionKey).update();
            if (row.state() != State.CLOSED) {
                log.info("Circuit of connection {} in workspace {} closed after a successful trial call", connectionKey,
                        workspaceId);
            }
            return State.CLOSED;
        }
        String reason = "Consecutive failures (last " + (errorCode == null ? "error" : errorCode) + ")";
        if (found.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO connection_circuit (workspace_id, connection_key, state, consecutive_failures,
                                                    first_failure_at, cooldown_seconds, updated_at)
                    VALUES (:ws, :key, 'closed', 0, NULL, :cooldown, :now)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("ws", workspaceId).param("key", connectionKey).param("cooldown", initialCooldownSeconds)
                    .param("now", Sql.ts(now)).update();
            found = lock(workspaceId, connectionKey);
        }
        Row row = found.orElseThrow();
        if (row.state() == State.HALF_OPEN || (row.state() == State.OPEN && trial)) {
            int cooldown = Math.min(maxCooldownSeconds, row.cooldownSeconds() * 2);
            open(workspaceId, connectionKey, now, cooldown, row.consecutiveFailures() + 1,
                    "The trial call failed (" + (errorCode == null ? "error" : errorCode) + ")");
            return State.OPEN;
        }
        if (row.state() == State.OPEN) {
            return State.OPEN;
        }
        boolean fresh = row.consecutiveFailures() == 0 || row.firstFailureAt() == null
                || row.firstFailureAt().isBefore(now.minus(window));
        int failures = fresh ? 1 : row.consecutiveFailures() + 1;
        if (failures >= failureThreshold) {
            open(workspaceId, connectionKey, now, initialCooldownSeconds, failures,
                    failures + " " + reason.toLowerCase(java.util.Locale.ROOT));
            return State.OPEN;
        }
        jdbc.sql("""
                UPDATE connection_circuit SET consecutive_failures = :failures, first_failure_at = :first,
                       updated_at = :now
                WHERE workspace_id = :ws AND connection_key = :key
                """)
                .param("failures", failures).param("first", Sql.ts(fresh ? now : row.firstFailureAt()))
                .param("now", Sql.ts(now)).param("ws", workspaceId).param("key", connectionKey).update();
        return State.CLOSED;
    }

    @Transactional(readOnly = true)
    public Optional<Status> status(UUID workspaceId, String connectionKey) {
        return jdbc.sql("""
                SELECT state, consecutive_failures, opened_at, open_until, reason, cooldown_seconds, times_opened,
                       updated_at
                FROM connection_circuit WHERE workspace_id = :ws AND connection_key = :key
                """)
                .param("ws", workspaceId).param("key", connectionKey)
                .query((rs, n) -> new Status(State.of(rs.getString("state")), rs.getInt("consecutive_failures"),
                        Sql.instant(rs, "opened_at"), Sql.instant(rs, "open_until"), rs.getString("reason"),
                        rs.getInt("cooldown_seconds"), rs.getInt("times_opened"), Sql.instant(rs, "updated_at")))
                .optional();
    }

    private void open(UUID workspaceId, String connectionKey, Instant now, int cooldownSeconds, int failures,
            String reason) {
        String text = reason.length() > 120 ? reason.substring(0, 120) : reason;
        jdbc.sql("""
                UPDATE connection_circuit SET state = 'open', consecutive_failures = :failures, opened_at = :now,
                       open_until = :until, cooldown_seconds = :cooldown, reason = :reason, trial_until = NULL,
                       times_opened = times_opened + 1, updated_at = :now
                WHERE workspace_id = :ws AND connection_key = :key
                """)
                .param("failures", failures).param("now", Sql.ts(now))
                .param("until", Sql.ts(now.plusSeconds(cooldownSeconds))).param("cooldown", cooldownSeconds)
                .param("reason", text).param("ws", workspaceId).param("key", connectionKey).update();
        log.warn("Circuit of connection {} in workspace {} opened for {} s: {}", connectionKey, workspaceId,
                cooldownSeconds, text);
    }

    private record Row(State state, int consecutiveFailures, Instant firstFailureAt, Instant openUntil,
            Instant trialUntil, int cooldownSeconds) {
    }

    private Optional<Row> lock(UUID workspaceId, String connectionKey) {
        return jdbc.sql("""
                SELECT state, consecutive_failures, first_failure_at, open_until, trial_until, cooldown_seconds
                FROM connection_circuit WHERE workspace_id = :ws AND connection_key = :key FOR UPDATE
                """)
                .param("ws", workspaceId).param("key", connectionKey)
                .query((rs, n) -> new Row(State.of(rs.getString("state")), rs.getInt("consecutive_failures"),
                        Sql.instant(rs, "first_failure_at"), Sql.instant(rs, "open_until"),
                        Sql.instant(rs, "trial_until"), rs.getInt("cooldown_seconds")))
                .optional();
    }
}
