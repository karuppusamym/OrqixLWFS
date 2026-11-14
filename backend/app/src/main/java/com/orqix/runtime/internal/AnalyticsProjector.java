package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Projects runs into the analytics read model (docs/build/S145, D-CG6). Each pass takes the runs that have outbox rows
 * due, and for each run, in its own transaction: takes a per-run advisory lock (a run is never projected twice at once,
 * on any replica), locks exactly the outbox rows it saw, rebuilds the run's facts from its timeline and its tasks
 * (delete and insert: projecting twice gives the same rows), and deletes those outbox rows. An event committed meanwhile
 * keeps its own row and is projected in the next pass. A failure rolls the run back and retries the rows with backoff;
 * after {@code max-attempts} they are kept as failed and shown on the analytics status.
 */
@Component
public class AnalyticsProjector {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsProjector.class);
    /** Events read for one run, as for the run diagram; a longer history keeps the run and task facts only. */
    static final int MAX_EVENTS = RunPaths.MAX_EVENTS;
    private static final Duration PASS_BUDGET = Duration.ofSeconds(20);

    record Due(UUID workspaceId, UUID runId, List<Long> ids) {
    }

    private record TaskRow(String taskId, String nodeId, String label, String group, String status, Instant createdAt,
            Instant endedAt, Instant dueAt, Instant claimedAt, boolean assigned) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RunStore runs;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final Clock clock;
    private final int batch;
    private final int maxAttempts;

    public AnalyticsProjector(JdbcClient jdbc, TransactionTemplate transactions, RunStore runs, RunEvents events,
            ReleaseQueries releases, Clock clock, @Value("${orqix.analytics.batch:200}") int batch,
            @Value("${orqix.analytics.max-attempts:5}") int maxAttempts) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.runs = runs;
        this.events = events;
        this.releases = releases;
        this.clock = clock;
        this.batch = Math.max(1, Math.min(batch, 1000));
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${orqix.analytics.projector-tick:5s}",
            initialDelayString = "${orqix.analytics.projector-tick:5s}")
    public void tick() {
        try {
            run();
        } catch (RuntimeException e) {
            LOG.warn("Analytics projection failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    /** One pass (also called by tests). @return the number of runs projected */
    public int run() {
        Instant passEnd = clock.instant().plus(PASS_BUDGET);
        int projected = 0;
        for (Due due : due(null)) {
            if (!clock.instant().isBefore(passEnd)) {
                break;
            }
            if (projectDue(due)) {
                projected++;
            }
        }
        return projected;
    }

    /**
     * Projects only the given runs' due outbox rows, through the same claim as a pass (tests, and an operator who wants
     * one request's figures now). @return the number of runs projected
     */
    public int runFor(java.util.Collection<UUID> runIds) {
        int projected = 0;
        for (Due due : due(List.copyOf(runIds))) {
            if (projectDue(due)) {
                projected++;
            }
        }
        return projected;
    }

    /** Runs with outbox rows due (of the given runs, or of all when null), oldest first, with the row ids seen now. */
    List<Due> due(List<UUID> only) {
        if (only != null && only.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT workspace_id, run_id, array_agg(id ORDER BY id) AS ids
                FROM analytics_outbox
                WHERE NOT dead AND next_attempt_at <= :now
                """ + (only == null ? "" : " AND run_id IN (:only)") + """

                GROUP BY workspace_id, run_id
                ORDER BY min(id)
                LIMIT :batch
                """)
                .param("now", Sql.ts(clock.instant())).param("batch", only == null ? batch : only.size())
                .param("only", only == null ? List.of(new UUID(0, 0)) : only)
                .query((rs, n) -> {
                    Long[] ids = (Long[]) rs.getArray("ids").getArray();
                    return new Due(Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"), List.of(ids));
                })
                .list();
    }

    private boolean projectDue(Due due) {
        try {
            Boolean done = transactions.execute(status -> {
                boolean locked = Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:k, 0))")
                        .param("k", "analytics:" + due.runId()).query(Boolean.class).single());
                if (!locked) {
                    return false;
                }
                List<Long> claimed = jdbc.sql("""
                        SELECT id FROM analytics_outbox WHERE id IN (:ids) AND NOT dead FOR UPDATE SKIP LOCKED
                        """).param("ids", due.ids()).query(Long.class).list();
                if (claimed.isEmpty()) {
                    return false;
                }
                project(due.workspaceId(), due.runId());
                jdbc.sql("DELETE FROM analytics_outbox WHERE id IN (:ids)").param("ids", claimed)
                        .update();
                return true;
            });
            return Boolean.TRUE.equals(done);
        } catch (RuntimeException e) {
            String reason = e.getClass().getSimpleName();
            LOG.warn("Analytics projection of a run failed; retrying later ({})", reason);
            transactions.executeWithoutResult(status -> jdbc.sql("""
                    UPDATE analytics_outbox
                    SET attempts = attempts + 1,
                        dead = attempts + 1 >= :max,
                        next_attempt_at = CAST(:now AS timestamptz) + make_interval(secs => least(3600, 30 * power(2, attempts))),
                        last_error = :reason
                    WHERE id IN (:ids)
                    """).param("max", maxAttempts).param("now", Sql.ts(clock.instant())).param("reason", reason)
                    .param("ids", due.ids()).update());
            return false;
        }
    }

    /** Rebuilds every fact of one run; must run inside a transaction. A run that no longer exists loses its facts. */
    void project(UUID workspaceId, UUID runId) {
        for (String table : List.of("analytics_step_visit", "analytics_route", "analytics_task")) {
            jdbc.sql("DELETE FROM " + table + " WHERE run_id = :run").param("run", runId).update();
        }
        Optional<RunStore.RunRow> found = runs.find(workspaceId, runId);
        if (found.isEmpty()) {
            jdbc.sql("DELETE FROM analytics_run WHERE run_id = :run").param("run", runId).update();
            return;
        }
        RunStore.RunRow run = found.get();
        Instant now = clock.instant();
        List<RunEvents.EventRow> history = events.list(workspaceId, runId, 0, MAX_EVENTS + 1);
        boolean truncated = history.size() > MAX_EVENTS;
        if (truncated) {
            history = history.subList(0, MAX_EVENTS);
        }
        List<TaskRow> tasks = tasks(workspaceId, runId);
        if (!truncated) {
            projectPath(run, history);
        }
        projectTasks(run, history, tasks);

        Double cycle = "completed".equals(run.lifecycle()) && run.endedAt() != null
                ? AnalyticsTaskTimes.seconds(run.createdAt(), run.endedAt()) : null;
        boolean automated = "completed".equals(run.lifecycle()) && tasks.isEmpty();
        jdbc.sql("""
                INSERT INTO analytics_run (run_id, workspace_id, workflow_id, release_id, lifecycle, outcome_kind, started_at,
                                           ended_at, cycle_seconds, human_tasks, automated, history_truncated,
                                           projected_seq, projected_at)
                VALUES (:run, :ws, :wf, :rel, :lifecycle, :outcome, :started, :ended, :cycle, :tasks, :automated, :truncated,
                        :seq, :now)
                ON CONFLICT (run_id) DO UPDATE SET
                    release_id = EXCLUDED.release_id, lifecycle = EXCLUDED.lifecycle, outcome_kind = EXCLUDED.outcome_kind,
                    ended_at = EXCLUDED.ended_at, cycle_seconds = EXCLUDED.cycle_seconds,
                    human_tasks = EXCLUDED.human_tasks, automated = EXCLUDED.automated,
                    history_truncated = EXCLUDED.history_truncated, projected_seq = EXCLUDED.projected_seq,
                    projected_at = EXCLUDED.projected_at
                """)
                .param("run", runId).param("ws", workspaceId).param("wf", run.workflowId()).param("rel", run.releaseId())
                .param("lifecycle", run.lifecycle()).param("outcome", run.outcomeKind())
                .param("started", Sql.ts(run.createdAt())).param("ended", run.endedAt() == null ? null : Sql.ts(run.endedAt()))
                .param("cycle", cycle).param("tasks", tasks.size()).param("automated", automated)
                .param("truncated", truncated).param("seq", run.eventSeq()).param("now", Sql.ts(now))
                .update();
    }

    /** Finished step visits and connections used, from the same derivation as the run diagram (S24). */
    private void projectPath(RunStore.RunRow run, List<RunEvents.EventRow> history) {
        ReleaseQueries.ReleaseView release = releases.require(run.workspaceId(), run.releaseId());
        List<RuntimeDtos.CurrentStep> current = new ArrayList<>();
        for (JsonNode step : run.currentSteps()) {
            current.add(new RuntimeDtos.CurrentStep(step.path("nodeId").asString(), step.path("label").asString(""),
                    step.path("kind").asString(""), null, null, null, null));
        }
        RunPathBuilder.Result path = RunPathBuilder.build(run.id(), run.lifecycle(), release.definition(),
                release.sourceMap(), history, current, null);
        Map<String, String> kinds = new HashMap<>();
        path.diagram().nodes().forEach(n -> kinds.put(n.id(), n.kind()));
        int ordinal = 0;
        for (RunPathBuilder.Visit v : path.visited()) {
            if (v.leftAt() == null) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO analytics_step_visit (run_id, ordinal, workspace_id, workflow_id, release_id, run_started_at,
                                                      node_id, kind, entered_at, left_at, duration_seconds, outcome)
                    VALUES (:run, :ordinal, :ws, :wf, :rel, :started, :node, :kind, :entered, :left, :secs, :outcome)
                    """)
                    .param("run", run.id()).param("ordinal", ordinal++).param("ws", run.workspaceId())
                    .param("wf", run.workflowId()).param("rel", run.releaseId()).param("started", Sql.ts(run.createdAt()))
                    .param("node", v.nodeId()).param("kind", kinds.getOrDefault(v.nodeId(), "step"))
                    .param("entered", Sql.ts(v.enteredAt())).param("left", Sql.ts(v.leftAt()))
                    .param("secs", AnalyticsTaskTimes.seconds(v.enteredAt(), v.leftAt()))
                    .param("outcome", v.outcome()).update();
        }
        for (String transition : new LinkedHashSet<>(path.taken())) {
            jdbc.sql("""
                    INSERT INTO analytics_route (run_id, transition_id, workspace_id, workflow_id, release_id, run_started_at)
                    VALUES (:run, :t, :ws, :wf, :rel, :started)
                    """)
                    .param("run", run.id()).param("t", transition).param("ws", run.workspaceId())
                    .param("wf", run.workflowId()).param("rel", run.releaseId()).param("started", Sql.ts(run.createdAt()))
                    .update();
        }
    }

    private List<TaskRow> tasks(UUID workspaceId, UUID runId) {
        return jdbc.sql("""
                SELECT engine_task_id, node_id, label, candidate_group_key, status, created_at, ended_at, due_at, claimed_at,
                       assignee_subject_id IS NOT NULL AS assigned
                FROM task_context WHERE workspace_id = :ws AND run_id = :run
                ORDER BY created_at, engine_task_id
                """)
                .param("ws", workspaceId).param("run", runId)
                .query((rs, n) -> new TaskRow(rs.getString("engine_task_id"), rs.getString("node_id"), rs.getString("label"),
                        rs.getString("candidate_group_key"), rs.getString("status"), Sql.instant(rs, "created_at"),
                        Sql.instant(rs, "ended_at"), Sql.instant(rs, "due_at"), Sql.instant(rs, "claimed_at"),
                        rs.getBoolean("assigned")))
                .list();
    }

    private void projectTasks(RunStore.RunRow run, List<RunEvents.EventRow> history, List<TaskRow> tasks) {
        Map<String, List<RunEvents.EventRow>> byNode = new HashMap<>();
        for (RunEvents.EventRow e : history) {
            if (e.nodeId() != null) {
                byNode.computeIfAbsent(e.nodeId(), k -> new ArrayList<>()).add(e);
            }
        }
        for (TaskRow t : tasks) {
            List<RunEvents.EventRow> nodeEvents = byNode.getOrDefault(t.nodeId(), List.of());
            boolean overlapping = tasks.stream().anyMatch(o -> o != t && o.nodeId().equals(t.nodeId()) && overlaps(o, t));
            AnalyticsTaskTimes.Times times = AnalyticsTaskTimes.compute(t.createdAt(), t.endedAt(), t.claimedAt(),
                    nodeEvents, overlapping);
            String outcome = outcome(t, nodeEvents);
            jdbc.sql("""
                    INSERT INTO analytics_task (task_id, run_id, workspace_id, workflow_id, release_id, node_id, label, group_key,
                                                status, outcome, assigned, created_at, ended_at, due_at, waiting_seconds,
                                                working_seconds, claims, releases, reassignments, sla, approximate)
                    VALUES (:task, :run, :ws, :wf, :rel, :node, :label, :grp, :status, :outcome, :assigned, :created, :ended,
                            :due, :waiting, :working, :claims, :releases, :reassignments, :sla, :approximate)
                    ON CONFLICT (task_id) DO NOTHING
                    """)
                    .param("task", t.taskId()).param("run", run.id()).param("ws", run.workspaceId())
                    .param("wf", run.workflowId()).param("rel", run.releaseId()).param("node", t.nodeId())
                    .param("label", t.label()).param("grp", t.group()).param("status", t.status()).param("outcome", outcome)
                    .param("assigned", t.assigned()).param("created", Sql.ts(t.createdAt()))
                    .param("ended", t.endedAt() == null ? null : Sql.ts(t.endedAt()))
                    .param("due", t.dueAt() == null ? null : Sql.ts(t.dueAt()))
                    .param("waiting", times.waitingSeconds()).param("working", times.workingSeconds())
                    .param("claims", times.claims()).param("releases", times.releases())
                    .param("reassignments", times.reassignments()).param("sla", sla(t, outcome))
                    .param("approximate", times.approximate()).update();
        }
    }

    private static boolean overlaps(TaskRow a, TaskRow b) {
        Instant aEnd = a.endedAt() == null ? Instant.MAX : a.endedAt();
        Instant bEnd = b.endedAt() == null ? Instant.MAX : b.endedAt();
        return a.createdAt().isBefore(bEnd) && b.createdAt().isBefore(aEnd);
    }

    /** Decided, ended by its time limit (TASK_TIMED_OUT of the step while it was open), or cancelled otherwise. */
    private static String outcome(TaskRow t, List<RunEvents.EventRow> nodeEvents) {
        if ("open".equals(t.status())) {
            return null;
        }
        if ("completed".equals(t.status())) {
            return "decided";
        }
        Instant limit = t.endedAt().plusSeconds(5);
        boolean timedOut = nodeEvents.stream().anyMatch(e -> "TASK_TIMED_OUT".equals(e.type())
                && !e.occurredAt().isBefore(t.createdAt()) && !e.occurredAt().isAfter(limit));
        return timedOut ? "timedOut" : "cancelled";
    }

    /** Met when decided by its due time; missed when decided late, timed out, or cancelled after it was due. */
    private static String sla(TaskRow t, String outcome) {
        if (t.dueAt() == null || outcome == null) {
            return null;
        }
        return switch (outcome) {
            case "decided" -> t.endedAt().isAfter(t.dueAt()) ? "missed" : "met";
            case "timedOut" -> "missed";
            default -> t.endedAt().isAfter(t.dueAt()) ? "missed" : null;
        };
    }
}
