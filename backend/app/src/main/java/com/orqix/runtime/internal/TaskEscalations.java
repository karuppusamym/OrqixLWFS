package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.SubjectService;
import com.orqix.notification.NotificationService;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * CG-T04 (docs/build/S141): escalation policies of approval steps. When a task is created, every level of the step's
 * pinned policy is planned durably ({@code task_escalation}): working hours of the step's calendar when it has one,
 * otherwise elapsed hours, counted from creation. The worker dispatches a due level once, under the run and task locks
 * (taken without waiting), and rechecks at that moment that the task is still open and who may receive it:
 * <ul>
 * <li>{@code notify}: an in-app notice to the group's members who can see the workspace;</li>
 * <li>{@code reassign}: the task moves to the group's queue (assignee cleared) only when at least one person there is
 * eligible for it; otherwise the level is skipped with {@code noEligiblePerson};</li>
 * <li>{@code alertOperators}: an in-app notice to everyone holding {@code run.operate}.</li>
 * </ul>
 * Each level writes one {@code TASK_ESCALATED} run event and one {@code task.escalated} audit record; notices are
 * deduplicated per task, level and person. The due date is never changed (the SLA keeps running), a suspended request
 * postpones the level by an hour, and a closed task skips its remaining levels.
 */
@Service
@Profile("!migrate")
public class TaskEscalations {

    /** Most people one level notifies. */
    static final int MAX_RECIPIENTS = 50;

    private record Due(UUID workspace, String task, int level, String action, String group, int afterHours,
            boolean workingTime) {
    }

    private final JdbcClient jdbc;
    private final TaskStore tasks;
    private final TaskService taskService;
    private final RunStore runs;
    private final RunEvents events;
    private final RunSuspensions suspensions;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final NotificationService notifications;
    private final AuditWriter audit;
    private final TaskActionOutbox actions;
    private final Clock clock;

    public TaskEscalations(JdbcClient jdbc, TaskStore tasks, @org.springframework.context.annotation.Lazy TaskService taskService,
            RunStore runs, RunEvents events, RunSuspensions suspensions, ReleaseQueries releases, EngineCommands engine,
            NotificationService notifications, AuditWriter audit, TaskActionOutbox actions, Clock clock) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.taskService = taskService;
        this.runs = runs;
        this.events = events;
        this.suspensions = suspensions;
        this.releases = releases;
        this.engine = engine;
        this.notifications = notifications;
        this.audit = audit;
        this.actions = actions;
        this.clock = clock;
    }

    /** Plans the levels of the step's policy for a new task (nothing when the step has none). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int plan(UUID workspaceId, UUID runId, String taskId, String nodeId, Instant createdAt) {
        RunStore.RunRow run = runs.find(workspaceId, runId).orElse(null);
        if (run == null) {
            return 0;
        }
        JsonNode node = releases.require(workspaceId, run.releaseId()).node(nodeId);
        JsonNode levels = node == null ? null : node.path("config").path("escalationPolicy").path("levels");
        if (levels == null || !levels.isArray() || levels.isEmpty()) {
            return 0;
        }
        JsonNode calendar = node.path("config").path("calendar");
        com.orqix.compiler.BusinessCalendar working = calendar.isObject()
                ? com.orqix.compiler.BusinessCalendar.read(calendar) : null;
        int planned = 0;
        for (int i = 0; i < levels.size() && i < 5; i++) {
            JsonNode level = levels.get(i);
            int hours = level.path("afterHours").asInt();
            Instant due = working == null ? createdAt.plus(Duration.ofHours(hours))
                    : working.deadlineMinutes(createdAt, hours * 60);
            String action = level.path("action").asString();
            planned += jdbc.sql("""
                    INSERT INTO task_escalation (workspace_id, task_id, level, action, group_key, after_hours,
                                                 working_time, due_at, next_attempt_at)
                    VALUES (:ws, :task, :level, :action, :grp, :hours, :working, :due, :due)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("ws", workspaceId).param("task", taskId).param("level", i + 1).param("action", action)
                    .param("grp", "alertOperators".equals(action) ? null : level.path("groupRef").asString(null))
                    .param("hours", hours).param("working", working != null).param("due", Sql.ts(due)).update();
        }
        return planned;
    }

    @Scheduled(fixedDelayString = "${orqix.escalations.poll-delay:60s}")
    @Transactional
    public void dispatch() {
        dispatchDue();
    }

    /** Dispatches the levels due now; returns how many were settled (done or skipped). */
    @Transactional
    public int dispatchDue() {
        Instant now = clock.instant();
        List<Due> due = jdbc.sql("""
                SELECT workspace_id, task_id, level, action, group_key, after_hours, working_time FROM task_escalation
                WHERE status = 'pending' AND next_attempt_at <= :now
                ORDER BY next_attempt_at, task_id, level LIMIT 50 FOR UPDATE SKIP LOCKED
                """)
                .param("now", Sql.ts(now))
                .query((rs, n) -> new Due(Sql.uuid(rs, "workspace_id"), rs.getString("task_id"), rs.getInt("level"),
                        rs.getString("action"), rs.getString("group_key"), rs.getInt("after_hours"),
                        rs.getBoolean("working_time")))
                .list();
        int settled = 0;
        for (Due d : due) {
            TaskStore.TaskRow snapshot = tasks.find(d.workspace(), d.task()).orElse(null);
            if (snapshot == null) {
                continue;
            }
            // Same order as decisions and operator closure: the run first, then the task; never wait for either.
            if (jdbc.sql("SELECT id FROM workflow_run WHERE workspace_id = :ws AND id = :run FOR UPDATE SKIP LOCKED")
                    .param("ws", d.workspace()).param("run", snapshot.runId()).query(UUID.class).optional().isEmpty()) {
                continue;
            }
            TaskStore.TaskRow task = tasks.tryLock(d.workspace(), d.task()).orElse(null);
            if (task == null) {
                continue;
            }
            if (!task.isOpen()) {
                // The task was decided, timed out or cancelled: this and every later level end without acting.
                jdbc.sql("""
                        UPDATE task_escalation SET status = 'skipped', outcome = 'taskClosed', done_at = :now
                        WHERE workspace_id = :ws AND task_id = :task AND status = 'pending'
                        """).param("now", Sql.ts(now)).param("ws", d.workspace()).param("task", d.task()).update();
                settled++;
                continue;
            }
            if (suspensions.isSuspended(d.workspace(), task.runId())) {
                jdbc.sql("""
                        UPDATE task_escalation SET attempts = attempts + 1, next_attempt_at = :retry
                        WHERE workspace_id = :ws AND task_id = :task AND level = :level
                        """).param("retry", Sql.ts(now.plus(Duration.ofHours(1)))).param("ws", d.workspace())
                        .param("task", d.task()).param("level", d.level()).update();
                continue;
            }
            Result result = switch (d.action()) {
                case "notify" -> notifyGroup(d, task);
                case "reassign" -> reassign(d, task);
                default -> alertOperators(d, task);
            };
            finish(d, task, result, now);
            settled++;
        }
        return settled;
    }

    private record Result(String outcome, int recipients, boolean acted) {
    }

    private Result notifyGroup(Due d, TaskStore.TaskRow task) {
        int sent = notifyAll(d, task, taskService.groupMembers(d.workspace(), d.group(), MAX_RECIPIENTS),
                "Escalation: the task \"" + task.label() + "\" of request " + reference(task) + " has waited "
                        + hours(d) + " without a decision.");
        return new Result(sent == 0 ? "noRecipients" : "notified", sent, sent > 0);
    }

    private Result reassign(Due d, TaskStore.TaskRow task) {
        TaskStore.TaskRow moved = new TaskStore.TaskRow(task.taskId(), task.workspaceId(), task.runId(), task.nodeId(),
                task.label(), d.group(), task.initiator(), task.selfApprovalAllowed(), null, task.status(),
                task.version(), task.priority(), task.dueAt(), task.createdAt(), null, null);
        List<SubjectService.Subject> eligible = taskService.groupMembers(d.workspace(), d.group(), TaskDispatcher.MAX_CANDIDATES)
                .stream().filter(s -> taskService.eligibleSubject(d.workspace(), moved, s.id())).toList();
        if (eligible.isEmpty()) {
            return new Result("noEligiblePerson", 0, false);
        }
        if (task.assignee() != null) {
            try {
                engine.setAssignee(task.taskId(), null, null);
            } catch (EngineCommands.EngineConflictException e) {
                return new Result("engineConflict", 0, false);
            }
        }
        if (!tasks.moveToGroup(d.workspace(), task.taskId(), task.version(), d.group())) {
            return new Result("versionConflict", 0, false);
        }
        runs.mutate(d.workspace(), task.runId(), m -> m.updateStep(task.nodeId(), step -> {
            step.put("candidateGroup", d.group());
            step.putNull("assigneeId");
        }));
        List<SubjectService.Subject> told = eligible.stream().limit(MAX_RECIPIENTS).toList();
        int sent = notifyAll(d, task, told, "Escalation: the task \"" + task.label() + "\" of request "
                + reference(task) + " waited " + hours(d) + " and is now in your group's queue.");
        actions.enqueueForCandidates(d.workspace(), task.taskId(), "escalated-" + d.level());
        return new Result("reassigned", sent, true);
    }

    private Result alertOperators(Due d, TaskStore.TaskRow task) {
        int sent = notifyAll(d, task, taskService.operators(d.workspace(), MAX_RECIPIENTS),
                "Operator alert: the task \"" + task.label() + "\" of request " + reference(task) + " has waited "
                        + hours(d) + " without a decision.");
        return new Result(sent == 0 ? "noOperators" : "alerted", sent, sent > 0);
    }

    private int notifyAll(Due d, TaskStore.TaskRow task, List<SubjectService.Subject> recipients, String message) {
        int sent = 0;
        for (SubjectService.Subject person : recipients) {
            // Deduplicated per task, level and person: a repeated attempt never tells anyone twice.
            notifications.create(d.workspace(), person.id(), task.runId(), NotificationService.Kind.TASK_ESCALATED,
                    "task-escalation:" + d.workspace() + ":" + task.taskId() + ":" + d.level() + ":" + person.id(),
                    message);
            sent++;
        }
        return sent;
    }

    private void finish(Due d, TaskStore.TaskRow task, Result result, Instant now) {
        jdbc.sql("""
                UPDATE task_escalation SET status = :status, outcome = :outcome, recipients = :recipients,
                       attempts = attempts + 1, done_at = :now
                WHERE workspace_id = :ws AND task_id = :task AND level = :level
                """)
                .param("status", result.acted() ? "done" : "skipped").param("outcome", result.outcome())
                .param("recipients", result.recipients()).param("now", Sql.ts(now)).param("ws", d.workspace())
                .param("task", d.task()).param("level", d.level()).update();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", d.level());
        data.put("action", d.action());
        data.put("group", d.group());
        data.put("afterHours", d.afterHours());
        data.put("workingTime", d.workingTime());
        data.put("outcome", result.outcome());
        data.put("recipients", result.recipients());
        events.append(d.workspace(), task.runId(), "TASK_ESCALATED", task.nodeId(), null, data);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", task.taskId());
        metadata.put("nodeId", task.nodeId());
        metadata.put("level", d.level());
        metadata.put("action", d.action());
        metadata.put("groupKey", d.group());
        metadata.put("outcome", result.outcome());
        metadata.put("recipients", result.recipients());
        audit.write(new AuditWriter.Entry(d.workspace(), AuditWriter.ActorType.SYSTEM, null, "task.escalated", "task",
                task.taskId(), AuditWriter.Outcome.SUCCESS, metadata));
    }

    private String reference(TaskStore.TaskRow task) {
        return runs.find(task.workspaceId(), task.runId()).map(RunStore.RunRow::reference).orElse("");
    }

    private static String hours(Due d) {
        return d.afterHours() + (d.workingTime() ? " working hours" : " hours");
    }
}
