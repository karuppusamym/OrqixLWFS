package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.notification.NotificationService;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Case stages (docs/build/S17 section 4): a presentation and monitoring layer over the current steps. A stage is
 * entered when the first of its steps becomes current, completed when no step of it is current any more, and has an
 * optional time limit (sla) counted from the moment it was entered. Nothing here changes the process: the state is
 * derived from the run projection, in the transaction that changed it (see {@link #touch}), so it survives a restart and
 * is the same on every pod.
 *
 * <p>Overdue is shown at read time (now is past the due time of an open stage) and recorded once by
 * {@link #detectOverdue()}: STAGE_OVERDUE on the timeline and in-app notifications to the initiator and to the people
 * who may operate runs. The scheduled check takes a row lock with SKIP LOCKED and marks the stage before it writes, in
 * one transaction, so two pods never emit the event twice.
 */
@Service
public class RunStages {

    /** A stage of the pinned release. */
    record Def(int index, String id, String label, List<String> nodes, String sla, Duration slaDuration) {
    }

    private record Row(String stageId, int cycle, Instant enteredAt, Instant dueAt, Instant completedAt,
            Instant overdueAt) {
        boolean open() {
            return completedAt == null;
        }
    }

    /** At most this many operators are notified per overdue stage. */
    static final int MAX_OPERATOR_RECIPIENTS = 25;
    private static final int BATCH = 100;

    private final JdbcClient jdbc;
    private final RunStore runs;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final NotificationService notifications;
    private final SubjectService subjects;
    private final PolicyService policy;
    private final Clock clock;

    public RunStages(JdbcClient jdbc, @org.springframework.context.annotation.Lazy RunStore runs, RunEvents events,
            ReleaseQueries releases, NotificationService notifications, SubjectService subjects, PolicyService policy,
            Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.events = events;
        this.releases = releases;
        this.notifications = notifications;
        this.subjects = subjects;
        this.policy = policy;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- definitions

    static List<Def> defs(ReleaseQueries.ReleaseView release) {
        JsonNode stages = release.definition().path("stages");
        if (!stages.isArray() || stages.isEmpty()) {
            return List.of();
        }
        List<Def> list = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            JsonNode s = stages.get(i);
            List<String> nodes = new ArrayList<>();
            s.path("nodes").forEach(n -> nodes.add(n.asString()));
            String sla = s.path("sla").asString(null);
            // The compiler guarantees a day/hour/minute duration of PT1M to P90D (WF_STAGE_SLA_*).
            list.add(new Def(i, s.path("id").asString(), s.path("label").asString(), List.copyOf(nodes), sla,
                    sla == null ? null : Duration.parse(sla)));
        }
        return List.copyOf(list);
    }

    /** The stage of a step. Generated helper elements are mapped back to the canonical step first. */
    private static Def stageOf(List<Def> defs, ReleaseQueries.ReleaseView release, String nodeId) {
        String canonical = release.sourceMap() == null ? nodeId
                : release.sourceMap().path("bpmnToCanonical").path(nodeId).asString(nodeId);
        for (Def d : defs) {
            if (d.nodes().contains(canonical)) {
                return d;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- recording

    /**
     * Called whenever the run projection changes. Registers one reconcile per transaction and run, executed just before
     * commit, when every step the transaction removed or added is known; so a stage is not "completed" and "entered"
     * again by the two halves of one move from step to step.
     */
    public void touch(UUID workspaceId, UUID runId, UUID releaseId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || defs(releases.require(workspaceId, releaseId)).isEmpty()) {
            return;
        }
        String key = "orqix.stages." + runId;
        if (TransactionSynchronizationManager.hasResource(key)) {
            return;
        }
        TransactionSynchronizationManager.bindResource(key, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                reconcile(workspaceId, runId);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(key);
            }
        });
    }

    /** Brings the stage rows in line with the run's current steps; emits STAGE_ENTERED / STAGE_COMPLETED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(UUID workspaceId, UUID runId) {
        RunStore.RunRow run = runs.find(workspaceId, runId).orElse(null);
        if (run == null) {
            return;
        }
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, run.releaseId());
        List<Def> defs = defs(release);
        if (defs.isEmpty()) {
            return;
        }
        Set<String> active = new LinkedHashSet<>();
        if (run.isActive()) {
            for (JsonNode step : run.currentSteps()) {
                Def d = stageOf(defs, release, step.path("nodeId").asString(""));
                if (d != null) {
                    active.add(d.id());
                }
            }
        }
        Map<String, Row> rows = rows(runId);
        Instant now = clock.instant();
        boolean cancelled = RunStore.CANCELLED.equals(run.lifecycle());
        for (Def d : defs) {
            Row row = rows.get(d.id());
            if (row != null && row.open() && !active.contains(d.id())) {
                jdbc.sql("UPDATE run_stage SET completed_at = :now WHERE run_id = :run AND stage_id = :stage AND completed_at IS NULL")
                        .param("now", Sql.ts(now)).param("run", runId).param("stage", d.id()).update();
                if (!cancelled) {
                    events.append(workspaceId, runId, "STAGE_COMPLETED", null, null,
                            data(d, defs.size(), row.cycle(), row.enteredAt(), row.dueAt(), now));
                }
            }
        }
        for (Def d : defs) {
            if (!active.contains(d.id())) {
                continue;
            }
            Row row = rows.get(d.id());
            if (row != null && row.open()) {
                continue;
            }
            Instant due = d.slaDuration() == null ? null : now.plus(d.slaDuration());
            int cycle = row == null ? 1 : row.cycle() + 1;
            if (row == null) {
                jdbc.sql("""
                        INSERT INTO run_stage (workspace_id, run_id, stage_id, cycle, entered_at, due_at)
                        VALUES (:ws, :run, :stage, 1, :now, :due)
                        """).param("ws", workspaceId).param("run", runId).param("stage", d.id())
                        .param("now", Sql.ts(now)).param("due", Sql.ts(due)).update();
            } else {
                jdbc.sql("""
                        UPDATE run_stage SET cycle = :cycle, entered_at = :now, due_at = :due, completed_at = NULL,
                                             overdue_at = NULL
                        WHERE run_id = :run AND stage_id = :stage
                        """).param("cycle", cycle).param("now", Sql.ts(now)).param("due", Sql.ts(due))
                        .param("run", runId).param("stage", d.id()).update();
            }
            events.append(workspaceId, runId, "STAGE_ENTERED", null, null, data(d, defs.size(), cycle, now, due, null));
        }
    }

    private static Map<String, Object> data(Def d, int total, int cycle, Instant entered, Instant due, Instant completed) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stageId", d.id());
        data.put("label", d.label());
        data.put("index", d.index() + 1);
        data.put("total", total);
        data.put("cycle", cycle);
        data.put("enteredAt", entered.toString());
        data.put("dueAt", due == null ? null : due.toString());
        if (completed != null) {
            data.put("completedAt", completed.toString());
            data.put("late", due != null && completed.isAfter(due));
        }
        return data;
    }

    private Map<String, Row> rows(UUID runId) {
        Map<String, Row> rows = new HashMap<>();
        jdbc.sql("SELECT stage_id, cycle, entered_at, due_at, completed_at, overdue_at FROM run_stage WHERE run_id = :run")
                .param("run", runId).query((rs, n) -> new Row(rs.getString("stage_id"), rs.getInt("cycle"),
                        Sql.instant(rs, "entered_at"), Sql.instant(rs, "due_at"), Sql.instant(rs, "completed_at"),
                        Sql.instant(rs, "overdue_at")))
                .list().forEach(r -> rows.put(r.stageId(), r));
        return rows;
    }

    // ---------------------------------------------------------------- views

    /** The stages of a run with their state and the current one; null when the workflow has no stages. */
    @Transactional(readOnly = true)
    public RuntimeDtos.StageProgress progress(RunStore.RunRow run, ReleaseQueries.ReleaseView release) {
        List<Def> defs = defs(release);
        if (defs.isEmpty()) {
            return null;
        }
        Map<String, Row> rows = rows(run.id());
        Instant now = clock.instant();
        List<RuntimeDtos.StageView> views = new ArrayList<>();
        for (Def d : defs) {
            views.add(view(d, defs.size(), rows.get(d.id()), now));
        }
        RuntimeDtos.StageView current = null;
        for (RuntimeDtos.StageView v : views) {
            if ("active".equals(v.state())) {
                current = v;
                break;
            }
        }
        if (current == null) {
            // Between stages (or finished): the most recently entered stage.
            for (RuntimeDtos.StageView v : views) {
                if (v.enteredAt() != null && (current == null || v.enteredAt().isAfter(current.enteredAt()))) {
                    current = v;
                }
            }
        }
        return new RuntimeDtos.StageProgress(current, List.copyOf(views));
    }

    /** The current stage only (list views); null when the workflow has no stages or none was entered. */
    @Transactional(readOnly = true)
    public RuntimeDtos.StageView current(RunStore.RunRow run, ReleaseQueries.ReleaseView release) {
        RuntimeDtos.StageProgress progress = progress(run, release);
        return progress == null ? null : progress.current();
    }

    /** The stage a step belongs to, with the run's timing for it; null when the step is in no stage. */
    @Transactional(readOnly = true)
    public RuntimeDtos.StageView ofStep(RunStore.RunRow run, ReleaseQueries.ReleaseView release, String nodeId) {
        List<Def> defs = defs(release);
        Def d = defs.isEmpty() ? null : stageOf(defs, release, nodeId);
        if (d == null) {
            return null;
        }
        return view(d, defs.size(), rows(run.id()).get(d.id()), clock.instant());
    }

    private static RuntimeDtos.StageView view(Def d, int total, Row row, Instant now) {
        String state = row == null ? "notStarted" : row.open() ? "active" : "completed";
        boolean overdue = row != null && row.open() && row.dueAt() != null && now.isAfter(row.dueAt());
        return new RuntimeDtos.StageView(d.id(), d.label(), d.index() + 1, total, state,
                row == null ? null : row.enteredAt(), row == null ? null : row.dueAt(),
                row == null ? null : row.completedAt(), overdue, d.sla());
    }

    // ---------------------------------------------------------------- overdue

    /**
     * Records every stage that is open past its due time, once: STAGE_OVERDUE on the timeline and a notification to the
     * initiator and to people who hold {@code run.operate} (at most 25). Called by the scheduler; safe on several pods.
     *
     * @return the number of stages recorded as overdue
     */
    @Transactional
    public int detectOverdue() {
        Instant now = clock.instant();
        record Candidate(UUID workspaceId, UUID runId, String stageId) {
        }
        List<Candidate> candidates = jdbc.sql("""
                SELECT workspace_id, run_id, stage_id FROM run_stage
                WHERE completed_at IS NULL AND overdue_at IS NULL AND due_at IS NOT NULL AND due_at <= :now
                ORDER BY due_at LIMIT :limit
                """).param("now", Sql.ts(now)).param("limit", BATCH)
                .query((rs, n) -> new Candidate(Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"), rs.getString("stage_id")))
                .list();
        int recorded = 0;
        for (Candidate c : candidates) {
            // Lock order is run, then stage (the same as every projection change). A run another pod is changing is
            // skipped and found again on the next poll.
            RunStore.RunRow run = jdbc.sql("SELECT " + RunStore.columns() + """
                     FROM workflow_run WHERE workspace_id = :ws AND id = :id FOR UPDATE SKIP LOCKED
                    """).param("ws", c.workspaceId()).param("id", c.runId()).query(runs::map).optional().orElse(null);
            if (run == null) {
                continue;
            }
            Row row = jdbc.sql("""
                    SELECT stage_id, cycle, entered_at, due_at, completed_at, overdue_at FROM run_stage
                    WHERE run_id = :run AND stage_id = :stage AND completed_at IS NULL AND overdue_at IS NULL
                      AND due_at <= :now FOR UPDATE
                    """).param("run", c.runId()).param("stage", c.stageId()).param("now", Sql.ts(now))
                    .query((rs, n) -> new Row(rs.getString("stage_id"), rs.getInt("cycle"), Sql.instant(rs, "entered_at"),
                            Sql.instant(rs, "due_at"), Sql.instant(rs, "completed_at"), Sql.instant(rs, "overdue_at")))
                    .optional().orElse(null);
            if (row == null || !run.isActive()) {
                continue;
            }
            jdbc.sql("UPDATE run_stage SET overdue_at = :now WHERE run_id = :run AND stage_id = :stage")
                    .param("now", Sql.ts(now)).param("run", c.runId()).param("stage", c.stageId()).update();
            ReleaseQueries.ReleaseView release = releases.require(c.workspaceId(), run.releaseId());
            List<Def> defs = defs(release);
            Def def = defs.stream().filter(d -> d.id().equals(c.stageId())).findFirst().orElse(null);
            if (def == null) {
                continue;
            }
            Map<String, Object> data = data(def, defs.size(), row.cycle(), row.enteredAt(), row.dueAt(), null);
            data.put("sla", def.sla());
            events.append(c.workspaceId(), c.runId(), "STAGE_OVERDUE", null, null, data);
            notifyOverdue(run, def, row);
            recorded++;
        }
        return recorded;
    }

    private void notifyOverdue(RunStore.RunRow run, Def def, Row row) {
        String message = "Request " + run.reference() + " is overdue at the stage \"" + def.label() + "\".";
        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.add(run.initiator());
        recipients.addAll(operators(run.workspaceId()));
        for (UUID recipient : recipients) {
            notifications.create(run.workspaceId(), recipient, run.id(), NotificationService.Kind.WORKFLOW_MESSAGE,
                    "stage-overdue:" + run.id() + ":" + def.id() + ":" + row.cycle() + ":" + recipient, message);
        }
    }

    /** People who may operate runs in the workspace (bounded). */
    private List<UUID> operators(UUID workspaceId) {
        List<UUID> result = new ArrayList<>();
        for (SubjectService.Subject s : subjects.knownSubjects(500)) {
            if (s.disabled()) {
                continue;
            }
            Caller other = new Caller(s.id(), s.displayName(), s.email(), s.issuer(), s.externalSubject(),
                    subjects.storedGroups(s.id()), false);
            var access = policy.freshAccess(other, workspaceId).orElse(null);
            if (access != null && access.has(Permission.RUN_OPERATE)) {
                result.add(s.id());
                if (result.size() >= MAX_OPERATOR_RECIPIENTS) {
                    break;
                }
            }
        }
        return result;
    }
}
