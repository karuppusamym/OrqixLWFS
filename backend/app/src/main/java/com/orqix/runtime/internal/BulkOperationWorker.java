package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/**
 * Runs confirmed bulk operations (CG-O01, docs/build/S142). Every tick leases due operations ({@code FOR UPDATE SKIP
 * LOCKED}, so one pod works an operation at a time) and settles at most {@value #PER_TICK} items each, paced by the
 * operation's rate. An item:
 * <ol>
 * <li>is skipped ({@code NO_LONGER_MATCHES}) when the run no longer matches the filter and state rule;</li>
 * <li>runs the single-request operation as the person who confirmed the batch, rebuilt from their latest sign-in, with
 * the idempotency key {@code bulk:<operation>:<run>}, so its permission, state, version and audit rules are the API's
 * and a repeated attempt never acts twice;</li>
 * <li>is retried up to {@value #MAX_ATTEMPTS} times when the run is busy or changed while it was read; any other refusal
 * fails the item with the API's problem code.</li>
 * </ol>
 * When the confirmer no longer holds {@code run.operate} and {@code run.bulk-operate} (or is disabled), the remaining
 * items are cancelled.
 */
@Component
public class BulkOperationWorker {

    private static final Logger log = LoggerFactory.getLogger(BulkOperationWorker.class);
    static final int PER_TICK = 25;
    static final int MAX_ATTEMPTS = 3;
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration TICK = Duration.ofSeconds(2);

    private record Pending(UUID runId, int attempts) {
    }

    private final JdbcClient jdbc;
    private final BulkOperations operations;
    private final OperatorService operator;
    private final RunStore runs;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String pod = "bulk-" + UUID.randomUUID();

    private PlatformMetrics platformMetrics;

    /** CG-P06 (S150): feature counters. */
    @org.springframework.beans.factory.annotation.Autowired
    void setPlatformMetrics(PlatformMetrics platformMetrics) {
        this.platformMetrics = platformMetrics;
    }

    public BulkOperationWorker(JdbcClient jdbc, BulkOperations operations, OperatorService operator, RunStore runs,
            PolicyService policy, SubjectService subjects, AuditWriter audit,
            org.springframework.transaction.PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.operations = operations;
        this.operator = operator;
        this.runs = runs;
        this.policy = policy;
        this.subjects = subjects;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.bulk.poll-delay:2s}", initialDelayString = "${orqix.bulk.poll-delay:2s}")
    public void tick() {
        try {
            runDue();
        } catch (RuntimeException e) {
            log.warn("Bulk operations tick failed: {}", e.toString());
        }
    }

    /** Works every due operation once; returns the number of items settled. */
    public int runDue() {
        Instant now = clock.instant();
        List<UUID[]> due = tx.execute(status -> {
            List<UUID[]> ids = jdbc.sql("""
                    SELECT id, workspace_id FROM bulk_operation
                    WHERE state = 'running' AND next_item_at <= :now AND (lease_until IS NULL OR lease_until < :now)
                    ORDER BY next_item_at LIMIT 5 FOR UPDATE SKIP LOCKED
                    """).param("now", Sql.ts(now))
                    .query((rs, n) -> new UUID[] {Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id")}).list();
            for (UUID[] id : ids) {
                jdbc.sql("UPDATE bulk_operation SET lease_owner = :pod, lease_until = :until WHERE id = :id")
                        .param("pod", pod).param("until", Sql.ts(now.plus(LEASE))).param("id", id[0]).update();
            }
            return ids;
        });
        int settled = 0;
        for (UUID[] id : due == null ? List.<UUID[]>of() : due) {
            try {
                settled += work(id[1], id[0]);
            } finally {
                tx.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE bulk_operation SET lease_owner = NULL, lease_until = NULL
                        WHERE id = :id AND lease_owner = :pod
                        """).param("id", id[0]).param("pod", pod).update());
            }
        }
        return settled;
    }

    private int work(UUID workspaceId, UUID operationId) {
        BulkOperations.Row op = operations.find(workspaceId, operationId).orElse(null);
        if (op == null || !"running".equals(op.state())) {
            return 0;
        }
        Caller caller = caller(op.createdBy());
        WorkspaceAccess access = caller == null ? null
                : policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible).orElse(null);
        if (access == null || !access.has(Permission.RUN_OPERATE) || !access.has(Permission.RUN_BULK_OPERATE)) {
            stop(op, "PERMISSION_REVOKED", "The person who confirmed the bulk operation can no longer run it.");
            return 0;
        }
        Duration interval = Duration.ofMillis(Math.max(100, 60_000L / op.ratePerMinute()));
        // Token clock: next_item_at is the earliest time of the next item; credit is never banked beyond one tick, so a
        // paused or slow operation does not burst afterwards.
        Instant now = clock.instant();
        Instant slot = op.nextItemAt() == null || op.nextItemAt().isBefore(now.minus(TICK)) ? now : op.nextItemAt();
        int settled = 0;
        try {
            for (int i = 0; i < PER_TICK && !slot.isAfter(clock.instant()); i++) {
                BulkOperations.Row current = operations.find(workspaceId, operationId).orElse(null);
                if (current == null || !"running".equals(current.state())) {
                    return settled; // paused or cancelled while working
                }
                Pending item = jdbc.sql("""
                        SELECT run_id, attempts FROM bulk_operation_item WHERE operation_id = :id AND state = 'pending'
                        ORDER BY attempts, position LIMIT 1
                        """).param("id", operationId).query((rs, n) -> new Pending(Sql.uuid(rs, "run_id"),
                        rs.getInt("attempts"))).optional().orElse(null);
                if (item == null) {
                    complete(current);
                    return settled;
                }
                settle(current, caller, item);
                settled++;
                slot = slot.plus(interval);
            }
        } finally {
            Instant at = slot;
            tx.executeWithoutResult(status -> jdbc.sql(
                    "UPDATE bulk_operation SET next_item_at = :at WHERE id = :id AND state = 'running'")
                    .param("at", Sql.ts(at)).param("id", operationId).update());
        }
        // Finish without waiting for another tick when the last item was just settled.
        BulkOperations.Row current = operations.find(workspaceId, operationId).orElse(null);
        if (current != null && "running".equals(current.state()) && current.items() == current.succeeded()
                + current.failed() + current.skipped() + current.cancelledItems()) {
            complete(current);
        }
        return settled;
    }

    private void settle(BulkOperations.Row op, Caller caller, Pending item) {
        String outcome;
        String code = null;
        String message = null;
        if (!operations.stillMatches(op, item.runId())) {
            outcome = "skipped";
            code = "NO_LONGER_MATCHES";
            message = "The request no longer matches the filter or the action's state rule.";
        } else {
            try {
                act(op, caller, item.runId());
                outcome = "succeeded";
            } catch (ApiException e) {
                if (transientFailure(op, e, item)) {
                    record(op, item.runId(), "pending", e.code().name(), e.getMessage(), true);
                    return;
                }
                if (e.code() == ProblemCode.IDEMPOTENCY_KEY_REUSED) {
                    // An earlier attempt with this item's key completed: the action was applied once.
                    outcome = "succeeded";
                    code = "ALREADY_APPLIED";
                } else {
                    outcome = "failed";
                    code = e.code().name();
                    message = e.getMessage();
                }
            } catch (RuntimeException e) {
                log.warn("Bulk item {} of {} failed: {}", item.runId(), op.id(), e.toString());
                outcome = "failed";
                code = "INTERNAL_ERROR";
                message = "Something went wrong; nothing was changed for this request.";
            }
        }
        record(op, item.runId(), outcome, code, message, false);
        if (platformMetrics != null) {
            platformMetrics.bulkItem(op.action(), outcome);
        }
    }

    private boolean transientFailure(BulkOperations.Row op, ApiException e, Pending item) {
        if (item.attempts() + 1 >= MAX_ATTEMPTS) {
            return false;
        }
        if (e.code() == ProblemCode.RUN_BUSY) {
            return true;
        }
        // A version race: the run is still active but changed between reading and locking it.
        return e.code() == ProblemCode.RUN_NOT_ACTIVE
                && runs.find(op.workspaceId(), item.runId()).map(RunStore.RunRow::isActive).orElse(false);
    }

    private void act(BulkOperations.Row op, Caller caller, UUID runId) {
        UUID ws = op.workspaceId();
        RunStore.RunRow run = runs.find(ws, runId).orElseThrow(ApiException::notFound);
        String key = "bulk:" + op.id() + ":" + runId;
        String reason = op.reason() + " (bulk operation " + op.id().toString().substring(0, 8) + ")";
        int version = run.version();
        switch (op.action()) {
            case "retry" -> operator.retry(caller, ws, runId, key, new RuntimeDtos.RetryRunRequest(null, reason, version));
            case "cancel" -> operator.cancel(caller, ws, runId, key, new RuntimeDtos.CancelRunRequest(reason, version));
            case "suspend" -> operator.suspend(caller, ws, runId, key, new RuntimeDtos.SuspendRunRequest(reason, version));
            case "resume" -> operator.resume(caller, ws, runId, key, new RuntimeDtos.ResumeRunRequest(reason, version));
            case "migrate" -> operator.migrate(caller, ws, runId, key,
                    new RuntimeDtos.MigrateRunRequest(op.targetReleaseId(), reason, version));
            default -> throw new IllegalStateException(op.action());
        }
    }

    private void record(BulkOperations.Row op, UUID runId, String state, String code, String message,
            boolean retry) {
        String text = message == null ? null : message.length() > 300 ? message.substring(0, 300) : message;
        String column = switch (state) {
            case "succeeded" -> "succeeded";
            case "failed" -> "failed";
            case "skipped" -> "skipped";
            default -> null;
        };
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                    UPDATE bulk_operation_item SET state = :state, attempts = attempts + 1, error_code = :code,
                           message = :message, finished_at = :finished
                    WHERE operation_id = :id AND run_id = :run AND state = 'pending'
                    """).param("state", state).param("code", code).param("message", text)
                    .param("finished", retry ? null : Sql.ts(now)).param("id", op.id()).param("run", runId).update();
            if (updated == 0 && !retry) {
                // The operation was cancelled while this item ran: the item's own result is the truth.
                updated = jdbc.sql("""
                        UPDATE bulk_operation_item SET state = :state, attempts = attempts + 1, error_code = :code,
                               message = :message, finished_at = :finished
                        WHERE operation_id = :id AND run_id = :run AND state = 'cancelled'
                        """).param("state", state).param("code", code).param("message", text)
                        .param("finished", Sql.ts(now)).param("id", op.id()).param("run", runId).update();
                if (updated == 1) {
                    jdbc.sql("UPDATE bulk_operation SET cancelled_items = cancelled_items - 1 WHERE id = :id")
                            .param("id", op.id()).update();
                }
            }
            if (column != null && updated == 1) {
                jdbc.sql("UPDATE bulk_operation SET " + column + " = " + column + " + 1 WHERE id = :id")
                        .param("id", op.id()).update();
            }
        });
    }

    private void complete(BulkOperations.Row op) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                    UPDATE bulk_operation SET state = 'completed', finished_at = :now, next_item_at = NULL,
                           version = version + 1
                    WHERE id = :id AND state = 'running'
                    """).param("now", Sql.ts(clock.instant())).param("id", op.id()).update();
            if (updated == 1) {
                BulkOperations.Row done = operations.find(op.workspaceId(), op.id()).orElseThrow();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("action", done.action());
                metadata.put("succeeded", done.succeeded());
                metadata.put("failed", done.failed());
                metadata.put("skipped", done.skipped());
                audit.write(new AuditWriter.Entry(op.workspaceId(), AuditWriter.ActorType.SYSTEM, null,
                        "run.bulk-completed", "bulkOperation", op.id().toString(), AuditWriter.Outcome.SUCCESS,
                        metadata));
            }
        });
    }

    private void stop(BulkOperations.Row op, String code, String message) {
        tx.executeWithoutResult(status -> {
            int cancelled = operations.stopRemaining(op, code, message, message);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("reasonCode", code);
            metadata.put("cancelledItems", cancelled);
            audit.write(new AuditWriter.Entry(op.workspaceId(), AuditWriter.ActorType.SYSTEM, null, "run.bulk-stopped",
                    "bulkOperation", op.id().toString(), AuditWriter.Outcome.SUCCESS, metadata));
        });
    }

    private Caller caller(UUID subjectId) {
        SubjectService.Subject subject = subjects.find(subjectId).orElse(null);
        if (subject == null || subject.disabled()) {
            return null;
        }
        return new Caller(subject.id(), subject.displayName(), subject.email(), subject.issuer(),
                subject.externalSubject(), subjects.storedGroups(subjectId), false);
    }
}
