package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.web.Sql;

/**
 * Operator suspension of a request (docs/build/S37). A row means the engine process instance is suspended: its tasks,
 * timers and jobs are frozen by the engine, and Orqix refuses task commands and defers message delivery.
 */
@Repository
public class RunSuspensions {

    public record Suspension(UUID runId, Instant suspendedAt, UUID suspendedBy, String reason) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public RunSuspensions(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Suspension> find(UUID workspaceId, UUID runId) {
        return jdbc.sql("""
                SELECT run_id, suspended_at, suspended_by, reason FROM run_suspension
                WHERE workspace_id = :ws AND run_id = :run
                """)
                .param("ws", workspaceId).param("run", runId)
                .query((rs, n) -> new Suspension(Sql.uuid(rs, "run_id"), Sql.instant(rs, "suspended_at"),
                        Sql.uuid(rs, "suspended_by"), rs.getString("reason")))
                .optional();
    }

    @Transactional(readOnly = true)
    public boolean isSuspended(UUID workspaceId, UUID runId) {
        return find(workspaceId, runId).isPresent();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Suspension insert(UUID workspaceId, UUID runId, UUID by, String reason) {
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO run_suspension (run_id, workspace_id, suspended_at, suspended_by, reason)
                VALUES (:run, :ws, :at, :by, :reason)
                """)
                .param("run", runId).param("ws", workspaceId).param("at", Sql.ts(now)).param("by", by)
                .param("reason", reason).update();
        return new Suspension(runId, now, by, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean remove(UUID workspaceId, UUID runId) {
        return jdbc.sql("DELETE FROM run_suspension WHERE workspace_id = :ws AND run_id = :run")
                .param("ws", workspaceId).param("run", runId).update() > 0;
    }

    /** Throws {@link com.orqix.shared.problem.ApiException} with {@code RUN_SUSPENDED} while the run is suspended. */
    @Transactional(readOnly = true)
    public void requireNotSuspended(UUID workspaceId, UUID runId) {
        if (isSuspended(workspaceId, runId)) {
            throw new com.orqix.shared.problem.ApiException(com.orqix.shared.problem.ProblemCode.RUN_SUSPENDED,
                    "This request is suspended by an operator. It can continue after it is resumed.");
        }
    }
}
