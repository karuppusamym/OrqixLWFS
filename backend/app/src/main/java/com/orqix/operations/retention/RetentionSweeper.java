package com.orqix.operations.retention;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.runtime.RunRetention;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Retention sweeps (CG-O07, docs/build/S144). A sweep looks at top-level requests of workflows with an enabled policy
 * that ended more than {@code keepDays} ago. A request and its child requests form one unit (the top-level request's
 * policy applies to the whole hierarchy). For each unit, in its own transaction: re-check, then for every member
 * (deepest first) build the archive, store it, read it back and compare the checksum, record it, and purge the
 * operational rows through {@code orqix_purge_run} (which re-checks every guard). Any failure rolls the unit back and
 * leaves the request untouched. A dry run only counts and lists. At most one sweep runs per workspace.
 */
@Service
public class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);
    static final int DETAIL_LIMIT = 200;

    public record Outcome(UUID sweepId) {
    }

    private final JdbcClient jdbc;
    private final RunRetention runs;
    private final RunArchiver archiver;
    private final ArchiveStore store;
    private final HistoryPurger history;
    private final RetentionSettings settings;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final Json json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final boolean scheduled;

    public RetentionSweeper(JdbcClient jdbc, RunRetention runs, RunArchiver archiver, ArchiveStore store,
            HistoryPurger history, RetentionSettings settings, AuditWriter audit, PlatformTransactionManager txm,
            Json json, Clock clock, MeterRegistry meters,
            @Value("${orqix.retention.scheduled:true}") boolean scheduled) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.archiver = archiver;
        this.store = store;
        this.history = history;
        this.settings = settings;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
        this.json = json;
        this.clock = clock;
        this.meters = meters;
        this.scheduled = scheduled;
    }

    /** One purge sweep per workspace that has an enabled policy (see {@link RetentionScheduling}). */
    public void scheduledSweeps() {
        if (!scheduled) {
            return;
        }
        Instant now = clock.instant();
        List<UUID> workspaces = jdbc.sql("SELECT DISTINCT workspace_id FROM retention_policy WHERE enabled")
                .query(UUID.class).list();
        for (UUID ws : workspaces) {
            try {
                sweep(ws, false, null, now);
            } catch (ApiException e) {
                log.info("Retention sweep skipped for a workspace: {}", e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Retention sweep failed for a workspace: {}", e.getClass().getSimpleName());
            }
        }
        history.purgePending(now);
    }

    private record Candidate(UUID id, String reference, UUID workflowId, String workflowKey, Instant endedAt,
            int keepDays, boolean includeRunData, boolean includeFiles) {
    }

    private record Verdict(String outcome, String reason, List<RunRetention.Run> members) {
    }

    /**
     * Runs one sweep now. {@code requestedBy} is null for the scheduled sweep. Throws 409 when a sweep of the
     * workspace is still running.
     */
    public UUID sweep(UUID ws, boolean dryRun, UUID requestedBy, Instant now) {
        UUID sweepId = Ids.newId();
        tx.executeWithoutResult(s -> {
            // A sweep that has been running for six hours is from a node that stopped; it no longer blocks.
            jdbc.sql("""
                    UPDATE retention_sweep SET status = 'failed', finished_at = :now,
                           detail = detail || '{"error":"abandoned"}'::jsonb
                    WHERE workspace_id = :ws AND status = 'running' AND started_at < :stale
                    """).param("now", Sql.ts(now)).param("ws", ws).param("stale", Sql.ts(now.minus(Duration.ofHours(6))))
                    .update();
            try {
                jdbc.sql("""
                        INSERT INTO retention_sweep (id, workspace_id, mode, trigger, requested_by, status, started_at)
                        VALUES (:id, :ws, :mode, :trigger, :by, 'running', :now)
                        """).param("id", sweepId).param("ws", ws).param("mode", dryRun ? "dryRun" : "purge")
                        .param("trigger", requestedBy == null ? "scheduled" : "manual").param("by", requestedBy)
                        .param("now", Sql.ts(now)).update();
            } catch (DuplicateKeyException e) {
                throw new ApiException(ProblemCode.RESOURCE_IN_USE, "A retention sweep of this workspace is running.");
            }
        });
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String k : List.of("eligible", "archived", "purged", "held", "skipped", "failed")) {
            totals.put(k, 0);
        }
        ObjectNode detail = json.object();
        ArrayNode items = detail.putArray("items");
        String[] wsInfo = workspace(ws);
        try {
            List<Candidate> candidates = candidates(ws, now);
            detail.put("truncated", candidates.size() > settings.maxUnitsPerSweep());
            for (Candidate c : candidates.subList(0, Math.min(candidates.size(), settings.maxUnitsPerSweep()))) {
                Verdict verdict = tx.execute(s -> verdict(ws, c));
                String outcome = verdict.outcome();
                String reason = verdict.reason();
                if ("eligible".equals(outcome)) {
                    totals.merge("eligible", 1, Integer::sum);
                    if (!dryRun) {
                        try {
                            int done = tx.execute(s -> archiveAndPurge(ws, wsInfo, c, sweepId, requestedBy, now));
                            totals.merge("archived", done, Integer::sum);
                            totals.merge("purged", done, Integer::sum);
                            outcome = "purged";
                        } catch (RunArchiver.TooLarge e) {
                            outcome = "skipped";
                            reason = "archive_too_large";
                        } catch (ArchiveStore.ArchiveStoreException e) {
                            outcome = "failed";
                            reason = "store_unavailable";
                        } catch (DataAccessException e) {
                            String code = guard(e);
                            outcome = code == null ? "failed" : "skipped";
                            reason = code == null ? "purge_failed" : code;
                            if (code == null) {
                                log.warn("Retention purge failed: {}", e.getMostSpecificCause().getClass().getSimpleName());
                            }
                        }
                    }
                }
                if (!"eligible".equals(outcome) && !"purged".equals(outcome)) {
                    totals.merge(outcome, 1, Integer::sum);
                }
                count(outcome);
                if (items.size() < DETAIL_LIMIT) {
                    ObjectNode item = items.addObject().put("runId", c.id().toString()).put("reference", c.reference())
                            .put("workflowId", c.workflowId().toString()).put("endedAt", c.endedAt().toString())
                            .put("outcome", outcome).put("reason", reason).put("members", verdict.members().size());
                    item.put("keepDays", c.keepDays());
                }
            }
            finish(sweepId, "completed", totals, detail, now);
        } catch (RuntimeException e) {
            detail.put("error", "sweep_failed");
            finish(sweepId, "failed", totals, detail, now);
            throw e;
        }
        Map<String, Object> meta = new LinkedHashMap<>(totals);
        meta.put("mode", dryRun ? "dryRun" : "purge");
        tx.executeWithoutResult(s -> audit.write(new AuditWriter.Entry(ws,
                requestedBy == null ? AuditWriter.ActorType.SYSTEM : AuditWriter.ActorType.USER, requestedBy,
                "retention.sweep-completed", "retentionSweep", sweepId.toString(),
                AuditWriter.Outcome.SUCCESS, meta)));
        if (!dryRun && totals.get("purged") > 0) {
            history.purgePending(now);
        }
        return sweepId;
    }

    /** Eligible top-level requests, oldest first (one more than the per-sweep limit, to report truncation). */
    private List<Candidate> candidates(UUID ws, Instant now) {
        return jdbc.sql("""
                SELECT r.id, r.reference, r.workflow_id, w.key AS workflow_key, r.ended_at, p.keep_days,
                       p.include_run_data, p.include_files
                FROM workflow_run r
                JOIN retention_policy p ON p.workspace_id = r.workspace_id AND p.workflow_id = r.workflow_id AND p.enabled
                JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                WHERE r.workspace_id = :ws AND r.parent_run_id IS NULL AND r.lifecycle IN ('completed', 'cancelled')
                  AND r.ended_at < CAST(:now AS timestamptz) - make_interval(days => p.keep_days)
                ORDER BY r.ended_at, r.id LIMIT :limit
                """).param("ws", ws).param("now", Sql.ts(now)).param("limit", settings.maxUnitsPerSweep() + 1)
                .query((rs, n) -> new Candidate(Sql.uuid(rs, "id"), rs.getString("reference"),
                        Sql.uuid(rs, "workflow_id"), rs.getString("workflow_key"), Sql.instant(rs, "ended_at"),
                        rs.getInt("keep_days"), rs.getBoolean("include_run_data"), rs.getBoolean("include_files")))
                .list();
    }

    private Verdict verdict(UUID ws, Candidate c) {
        RunRetention.Run root = runs.find(ws, c.id()).orElse(null);
        if (root == null) {
            return new Verdict("skipped", "not_found", List.of());
        }
        List<RunRetention.Run> members = new ArrayList<>(runs.descendantsDeepestFirst(ws, c.id()));
        members.add(root);
        if (members.stream().anyMatch(m -> !m.ended())) {
            return new Verdict("skipped", "open_child", members);
        }
        List<UUID> ids = members.stream().map(RunRetention.Run::id).toList();
        List<UUID> workflows = members.stream().map(RunRetention.Run::workflowId).distinct().toList();
        int holds = jdbc.sql("""
                SELECT count(*) FROM legal_hold WHERE workspace_id = :ws AND released_at IS NULL
                  AND (run_id IN (:runs) OR workflow_id IN (:workflows))
                """).param("ws", ws).param("runs", ids).param("workflows", workflows).query(Integer.class).single();
        if (holds > 0) {
            return new Verdict("held", "legal_hold", members);
        }
        if (jdbc.sql("SELECT count(*) FROM case_workflow_task WHERE workspace_id = :ws AND run_id IN (:runs)")
                .param("ws", ws).param("runs", ids).query(Integer.class).single() > 0) {
            return new Verdict("skipped", "case_linked", members);
        }
        if (jdbc.sql("""
                SELECT count(*) FROM ai_eval_candidate c JOIN ai_decision_record d ON d.id = c.decision_id
                WHERE d.run_id IN (:runs)
                """).param("runs", ids).query(Integer.class).single() > 0) {
            return new Verdict("skipped", "ai_evaluation_candidate", members);
        }
        return new Verdict("eligible", null, members);
    }

    private int archiveAndPurge(UUID ws, String[] wsInfo, Candidate c, UUID sweepId, UUID requestedBy, Instant now) {
        // Lock the top-level request, then decide again: nothing may have changed since the verdict.
        jdbc.sql("SELECT id FROM workflow_run WHERE workspace_id = :ws AND id = :id FOR UPDATE").param("ws", ws)
                .param("id", c.id()).query(UUID.class).optional()
                .orElseThrow(() -> new ArchiveGuard("not_found"));
        Verdict again = verdict(ws, c);
        if (!"eligible".equals(again.outcome())) {
            throw new ArchiveGuard(again.reason());
        }
        RunArchiver.Policy policy = new RunArchiver.Policy(c.keepDays(), c.includeRunData(), c.includeFiles());
        int done = 0;
        for (RunRetention.Run member : again.members()) {
            String workflowKey = member.workflowId().equals(c.workflowId()) ? c.workflowKey()
                    : jdbc.sql("SELECT key FROM workflow WHERE workspace_id = :ws AND id = :id").param("ws", ws)
                            .param("id", member.workflowId()).query(String.class).single();
            RunArchiver.Built built = archiver.build(ws, wsInfo[0], wsInfo[1], workflowKey, member, c.id(), policy);
            String key = RunArchiver.key(wsInfo[0], workflowKey, member);
            store.put(key, built.bytes(), built.sha256());
            byte[] readBack = store.get(key).orElseThrow(() -> new ArchiveStore.ArchiveStoreException("missing"));
            if (!RunArchiver.sha256(readBack).equals(built.sha256())) {
                throw new ArchiveStore.ArchiveStoreException("checksum mismatch");
            }
            UUID archiveId = Ids.newId();
            jdbc.sql("""
                    INSERT INTO run_archive (id, workspace_id, run_id, run_reference, workflow_id, root_run_id, sweep_id,
                                             lifecycle, ended_at, store, object_key, sha256, bytes, status,
                                             engine_process_instance_id, history_status, archived_at)
                    VALUES (:id, :ws, :run, :ref, :wf, :root, :sweep, :lifecycle, :ended, :store, :key, :sha, :bytes,
                            'archived', :pid, :history, :now)
                    """).param("id", archiveId).param("ws", ws).param("run", member.id())
                    .param("ref", member.reference()).param("wf", member.workflowId()).param("root", c.id())
                    .param("sweep", sweepId).param("lifecycle", member.lifecycle())
                    .param("ended", Sql.ts(member.endedAt())).param("store", store.kind()).param("key", key)
                    .param("sha", built.sha256()).param("bytes", (long) built.bytes().length)
                    .param("pid", member.engineProcessInstanceId())
                    .param("history", member.engineProcessInstanceId() == null ? "none" : "pending")
                    .param("now", Sql.ts(now)).update();
            String counts = jdbc.sql("SELECT orqix_purge_run(:ws, :run)::text").param("ws", ws).param("run", member.id())
                    .query(String.class).single();
            jdbc.sql("""
                    UPDATE run_archive SET status = 'purged', purged_at = :now, purge_counts = CAST(:counts AS jsonb)
                    WHERE id = :id
                    """).param("now", Sql.ts(now)).param("counts", counts).param("id", archiveId).update();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("reference", member.reference());
            meta.put("workflowId", member.workflowId().toString());
            meta.put("rootRunId", c.id().toString());
            meta.put("archiveId", archiveId.toString());
            meta.put("sha256", built.sha256());
            meta.put("bytes", built.bytes().length);
            meta.put("store", store.kind());
            meta.put("keepDays", c.keepDays());
            audit.write(new AuditWriter.Entry(ws,
                    requestedBy == null ? AuditWriter.ActorType.SYSTEM : AuditWriter.ActorType.USER, requestedBy,
                    "run.archived-and-purged", "run", member.id().toString(), AuditWriter.Outcome.SUCCESS, meta));
            done++;
        }
        return done;
    }

    /** A guard that failed at purge time; reported as skipped with its reason. */
    static final class ArchiveGuard extends org.springframework.dao.DataIntegrityViolationException {

        private static final long serialVersionUID = 1L;

        ArchiveGuard(String reason) {
            super("retention:" + reason);
        }
    }

    /** The reason of a guard raised by {@code orqix_purge_run} or {@link ArchiveGuard}, else null. */
    static String guard(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) {
                int i = message.indexOf("retention:");
                if (i >= 0) {
                    String rest = message.substring(i + "retention:".length());
                    String code = rest.split("[^a-z_]", 2)[0];
                    if (!code.isEmpty()) {
                        return code;
                    }
                }
            }
        }
        return null;
    }

    private void finish(UUID sweepId, String status, Map<String, Integer> totals, ObjectNode detail, Instant now) {
        tx.executeWithoutResult(s -> jdbc.sql("""
                UPDATE retention_sweep SET status = :status, finished_at = :now, eligible = :eligible,
                       archived = :archived, purged = :purged, held = :held, skipped = :skipped, failed = :failed,
                       detail = CAST(:detail AS jsonb)
                WHERE id = :id
                """).param("status", status).param("now", Sql.ts(clock.instant().isAfter(now) ? clock.instant() : now))
                .param("eligible", totals.get("eligible")).param("archived", totals.get("archived"))
                .param("purged", totals.get("purged")).param("held", totals.get("held"))
                .param("skipped", totals.get("skipped")).param("failed", totals.get("failed"))
                .param("detail", json.write(detail)).param("id", sweepId).update());
    }

    private String[] workspace(UUID ws) {
        return jdbc.sql("SELECT key, name FROM workspace WHERE id = :ws").param("ws", ws)
                .query((rs, n) -> new String[] {rs.getString("key"), rs.getString("name")}).single();
    }

    private void count(String outcome) {
        Counter.builder("orqix_retention_requests").description("Requests looked at by retention sweeps, by outcome")
                .tag("outcome", outcome).register(meters).increment();
    }
}
