package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.pagination.Limits;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Task inbox and task commands (S5 section 4, B04.1-B04.4). */
@Service
public class TaskService {

    public record Decided(RuntimeDtos.TaskDecision decision, boolean replayed) {
    }

    private final PolicyService policy;
    private final SubjectService subjects;
    private final TaskStore tasks;
    private final TaskQueries taskQueries;
    private final RunStore runs;
    private final RunEvents events;
    private final RuntimeViews views;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final RuntimeMetrics metrics;
    private final CursorCodec cursors;
    private final Json json;
    private final Clock clock;

    public TaskService(PolicyService policy, SubjectService subjects, TaskStore tasks, TaskQueries taskQueries,
            RunStore runs, RunEvents events, RuntimeViews views, ReleaseQueries releases, EngineCommands engine,
            IdempotencyService idempotency, AuditWriter audit, RuntimeMetrics metrics, CursorCodec cursors, Json json,
            Clock clock) {
        this.policy = policy;
        this.subjects = subjects;
        this.tasks = tasks;
        this.taskQueries = taskQueries;
        this.runs = runs;
        this.events = events;
        this.views = views;
        this.releases = releases;
        this.engine = engine;
        this.idempotency = idempotency;
        this.audit = audit;
        this.metrics = metrics;
        this.cursors = cursors;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public RuntimeDtos.TaskPage listTasks(Caller caller, UUID workspaceId, String view, String cursor, Integer limit) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (view == null || !List.of("assigned", "claimable", "completed").contains(view)) {
            throw ApiException.malformed("view must be assigned, claimable or completed.");
        }
        policy.require(access, Permission.TASK_ACT, "listTasks", false);
        int size = Limits.of(limit);
        String filterKey = "listTasks|" + workspaceId + "|" + view;
        CursorCodec.Position after = cursors.decode(cursor, filterKey);
        UUID me = caller.subjectId();
        List<TaskStore.TaskRow> rows = switch (view) {
            case "assigned" -> taskQueries.assigned(workspaceId, me, after, size + 1);
            case "claimable" -> taskQueries.claimable(workspaceId, me, access.groupKeys(), after, size + 1);
            default -> taskQueries.completed(workspaceId, me, after, size + 1);
        };
        boolean more = rows.size() > size;
        List<TaskStore.TaskRow> items = more ? rows.subList(0, size) : rows;
        Map<String, TaskQueries.DecisionRow> decisions = new HashMap<>();
        Map<UUID, RunStore.RunRow> runRows = new HashMap<>();
        for (TaskStore.TaskRow task : items) {
            taskQueries.decisionForTask(workspaceId, task.taskId()).ifPresent(d -> decisions.put(task.taskId(), d));
            runRows.computeIfAbsent(task.runId(), id -> runs.find(workspaceId, id).orElseThrow());
        }
        Map<UUID, String> names = views.names(List.copyOf(runRows.values()), items, List.copyOf(decisions.values()));
        List<RuntimeDtos.TaskSummary> summaries = items.stream()
                .map(t -> views.task(t, runRows.get(t.runId()), names, decisions.get(t.taskId()))).toList();
        String next = null;
        if (more) {
            TaskStore.TaskRow last = items.get(items.size() - 1);
            Instant at = "completed".equals(view) ? decisions.get(last.taskId()).decidedAt() : last.createdAt();
            next = cursors.encode(new CursorCodec.Position(at, last.taskId()), filterKey);
        }
        RuntimeDtos.TaskCounts counts = new RuntimeDtos.TaskCounts(taskQueries.countAssigned(workspaceId, me),
                taskQueries.countClaimable(workspaceId, me, access.groupKeys()));
        return new RuntimeDtos.TaskPage(summaries, next, counts);
    }

    @Transactional(readOnly = true)
    public RuntimeDtos.TaskDetail getTask(Caller caller, UUID workspaceId, String taskId) {
        VisibleTask visible = visible(caller, workspaceId, taskId);
        return detail(visible);
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    public RuntimeDtos.TaskDetail claim(Caller caller, UUID workspaceId, String taskId, int expectedVersion) {
        VisibleTask visible = visibleLocked(caller, workspaceId, taskId);
        TaskStore.TaskRow task = visible.task();
        WorkspaceAccess access = visible.access();
        if (!task.isOpen()) {
            throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task is no longer open.");
        }
        requireEligible(access, task, "claimTask");
        if (task.assignee() != null) {
            throw new ApiException(ProblemCode.TASK_ALREADY_CLAIMED, "Someone else already claimed this task.")
                    .with("currentVersion", task.version());
        }
        requireVersion(task, expectedVersion);
        Instant now = clock.instant();
        try {
            engine.claim(taskId, caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT).with("currentVersion", task.version());
        }
        if (!tasks.setAssignee(workspaceId, taskId, expectedVersion, caller.subjectId(), now)) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT).with("currentVersion", task.version());
        }
        runs.mutate(workspaceId, task.runId(), m -> m.updateStep(task.nodeId(),
                step -> step.put("assigneeId", caller.subjectId().toString())));
        events.append(workspaceId, task.runId(), "TASK_CLAIMED", task.nodeId(), caller.subjectId(),
                Map.of("assignee", caller.displayName()));
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "task.claimed",
                "task", taskId, AuditWriter.Outcome.SUCCESS, Map.of("taskId", taskId, "nodeId", task.nodeId())));
        return detail(visible(caller, workspaceId, taskId));
    }

    @Transactional
    public RuntimeDtos.TaskDetail release(Caller caller, UUID workspaceId, String taskId, int expectedVersion) {
        VisibleTask visible = visibleLocked(caller, workspaceId, taskId);
        TaskStore.TaskRow task = visible.task();
        if (!task.isOpen()) {
            throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task is no longer open.");
        }
        if (!caller.subjectId().equals(task.assignee())) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Only the assignee can return this task.");
        }
        requireVersion(task, expectedVersion);
        try {
            engine.unclaim(taskId, caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT).with("currentVersion", task.version());
        }
        if (!tasks.setAssignee(workspaceId, taskId, expectedVersion, null, null)) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT).with("currentVersion", task.version());
        }
        runs.mutate(workspaceId, task.runId(), m -> m.updateStep(task.nodeId(), step -> step.putNull("assigneeId")));
        events.append(workspaceId, task.runId(), "TASK_RELEASED", task.nodeId(), caller.subjectId(),
                Map.of("assignee", caller.displayName()));
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "task.released",
                "task", taskId, AuditWriter.Outcome.SUCCESS, Map.of("taskId", taskId, "nodeId", task.nodeId())));
        return detail(visible(caller, workspaceId, taskId));
    }

    /** Decide (S5 section 4): one transaction; 201 means the decision and the continuation are durable. */
    @Transactional
    public Decided decide(Caller caller, UUID workspaceId, String taskId, String idempotencyKey,
            RuntimeDtos.DecisionRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        VisibleTask visible = visibleLocked(caller, workspaceId, taskId);
        TaskStore.TaskRow task = visible.task();
        WorkspaceAccess access = visible.access();
        policy.require(access, Permission.TASK_ACT, "decideTask", false);
        if (!caller.subjectId().equals(task.assignee())) {
            throw new ApiException(ProblemCode.TASK_NOT_CLAIMED_BY_CALLER, "Claim the task before deciding.");
        }
        requireEligible(access, task, "decideTask");

        String hash = idempotency.requestHash("decideTask",
                Map.of("workspaceId", workspaceId.toString(), "taskId", taskId), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "decideTask", key, hash);
        if (claim.isReplay()) {
            TaskQueries.DecisionRow decision = taskQueries.decision(workspaceId,
                    UUID.fromString(claim.replay().resourceId())).orElseThrow();
            return new Decided(views.decision(decision, task.label(), names(decision)), true);
        }
        if (!task.isOpen()) {
            throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task was already decided.");
        }
        boolean reject = "reject".equals(request.action());
        if (reject && (request.reason() == null || request.reason().isBlank())) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Give a reason for the rejection.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/reason", "required", "A reason is required")));
        }
        if (!tasks.bumpVersion(workspaceId, taskId, request.expectedVersion())) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT, "The task changed since you loaded it.")
                    .with("currentVersion", task.version());
        }
        Instant now = clock.instant();
        boolean selfApproval = task.initiator().equals(caller.subjectId()) && task.selfApprovalAllowed();
        List<String> flags = selfApproval ? List.of("selfApprovalAllowed") : List.of();
        UUID decisionId = taskQueries.insertDecision(workspaceId, taskId, task.runId(), task.nodeId(),
                caller.subjectId(), request.action(), blankToNull(request.reason()), blankToNull(request.comment()),
                flags, now);
        if (decisionId == null) {
            throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task was already decided.");
        }
        ObjectNode output = json.object();
        output.put("decision", reject ? "rejected" : "approved");
        if (!blank(request.reason())) {
            output.put("reason", request.reason());
        }
        if (!blank(request.comment())) {
            output.put("comment", request.comment());
        }
        output.put("decidedBy", caller.subjectId().toString());
        output.put("decidedAt", now.toString());

        tasks.end(workspaceId, taskId, "completed", now);
        runs.mutate(workspaceId, task.runId(), m -> {
            m.removeStep(task.nodeId());
            m.settle();
        });
        Map<String, Object> decided = new LinkedHashMap<>();
        decided.put("action", request.action());
        decided.put("hasReason", !blank(request.reason()));
        decided.put("hasComment", !blank(request.comment()));
        decided.put("policyFlags", flags);
        events.append(workspaceId, task.runId(), "TASK_DECIDED", task.nodeId(), caller.subjectId(), decided);
        try {
            engine.complete(taskId, Map.of(ProcessData.stepVariable(task.nodeId()), json.write(output)),
                    caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task was already decided.");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", taskId);
        metadata.put("nodeId", task.nodeId());
        metadata.put("action", request.action());
        metadata.put("policyFlags", flags);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "task.decided",
                "task", taskId, AuditWriter.Outcome.SUCCESS, metadata));
        idempotency.complete(claim, "taskDecision", decisionId.toString(), 201);
        metrics.taskDecided(request.action());
        TaskQueries.DecisionRow decision = taskQueries.decision(workspaceId, decisionId).orElseThrow();
        return new Decided(views.decision(decision, task.label(), names(decision)), false);
    }

    @Transactional
    public RuntimeDtos.TaskDetail reassign(Caller caller, UUID workspaceId, String taskId,
            RuntimeDtos.ReassignRequest request) {
        VisibleTask visible = visibleLocked(caller, workspaceId, taskId);
        TaskStore.TaskRow task = visible.task();
        policy.require(visible.access(), Permission.RUN_OPERATE, "reassignTask", true);
        if (!task.isOpen()) {
            throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task is no longer open.");
        }
        requireVersion(task, request.expectedVersion());
        UUID target = request.assigneeSubjectId();
        if (target != null && !eligibleSubject(workspaceId, task, target)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The new assignee is not eligible for this task.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/assigneeSubjectId", "enum",
                            "Not an eligible member of the candidate group")));
        }
        try {
            engine.setAssignee(taskId, target, caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT).with("currentVersion", task.version());
        }
        if (!tasks.setAssignee(workspaceId, taskId, request.expectedVersion(), target, target == null ? null : clock.instant())) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT).with("currentVersion", task.version());
        }
        runs.mutate(workspaceId, task.runId(), m -> m.updateStep(task.nodeId(), step -> {
            if (target == null) {
                step.putNull("assigneeId");
            } else {
                step.put("assigneeId", target.toString());
            }
        }));
        Map<UUID, String> names = subjects.displayNames(java.util.Arrays.asList(task.assignee(), target));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", task.assignee() == null ? null : names.get(task.assignee()));
        data.put("to", target == null ? null : names.get(target));
        data.put("reason", request.reason());
        events.append(workspaceId, task.runId(), "TASK_REASSIGNED", task.nodeId(), caller.subjectId(), data);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", taskId);
        metadata.put("toSubjectId", target == null ? null : target.toString());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "task.reassigned",
                "task", taskId, AuditWriter.Outcome.SUCCESS, metadata));
        return detail(visible(caller, workspaceId, taskId));
    }

    // ---------------------------------------------------------------- helpers

    record VisibleTask(WorkspaceAccess access, TaskStore.TaskRow task, RunStore.RunRow run) {
    }

    private VisibleTask visible(Caller caller, UUID workspaceId, String taskId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        TaskStore.TaskRow task = tasks.find(workspaceId, taskId).orElseThrow(ApiException::notFound);
        return check(access, task);
    }

    private VisibleTask visibleLocked(Caller caller, UUID workspaceId, String taskId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        TaskStore.TaskRow task = tasks.lock(workspaceId, taskId).orElseThrow(ApiException::notFound);
        return check(access, task);
    }

    /** Visible to eligible candidates, the assignee, past deciders and run.read holders; otherwise 404. */
    private VisibleTask check(WorkspaceAccess access, TaskStore.TaskRow task) {
        UUID me = access.subjectId();
        boolean candidate = access.groupKeys().contains(task.candidateGroupKey()) && access.has(Permission.TASK_ACT);
        boolean visible = access.has(Permission.RUN_READ) || me.equals(task.assignee()) || candidate
                || taskQueries.decisionForTask(task.workspaceId(), task.taskId())
                        .map(d -> d.actor().equals(me)).orElse(false);
        if (!visible) {
            throw ApiException.notFound();
        }
        RunStore.RunRow run = runs.find(task.workspaceId(), task.runId()).orElseThrow();
        return new VisibleTask(access, task, run);
    }

    private void requireEligible(WorkspaceAccess access, TaskStore.TaskRow task, String operationId) {
        if (!access.has(Permission.TASK_ACT)) {
            throw policy.deny(access.workspaceId(), access.subjectId(), "task.act", operationId, false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        if (!access.groupKeys().contains(task.candidateGroupKey())) {
            throw policy.deny(access.workspaceId(), access.subjectId(), "task.act", operationId, false,
                    new ApiException(ProblemCode.NOT_ELIGIBLE, "You are not in the group for this task."));
        }
        if (!task.selfApprovalAllowed() && task.initiator().equals(access.subjectId())) {
            throw policy.deny(access.workspaceId(), access.subjectId(), "task.act", operationId, false,
                    new ApiException(ProblemCode.SELF_APPROVAL_DENIED, "You cannot approve your own request."));
        }
    }

    private static void requireVersion(TaskStore.TaskRow task, int expected) {
        if (task.version() != expected) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT, "The task changed since you loaded it.")
                    .with("currentVersion", task.version());
        }
    }

    /** Eligibility of another subject, using its stored IdP groups from the latest login (V4). */
    private boolean eligibleSubject(UUID workspaceId, TaskStore.TaskRow task, UUID subjectId) {
        if (!task.selfApprovalAllowed() && task.initiator().equals(subjectId)) {
            return false;
        }
        SubjectService.Subject subject = subjects.find(subjectId).orElse(null);
        if (subject == null || subject.disabled()) {
            return false;
        }
        Caller other = new Caller(subjectId, subject.displayName(), subject.email(), subject.issuer(),
                subject.externalSubject(), subjects.storedGroups(subjectId), false);
        WorkspaceAccess access = policy.access(other, workspaceId).orElse(null);
        return access != null && access.has(Permission.TASK_ACT)
                && policy.groupKeys(workspaceId, subjectId, other.groups()).contains(task.candidateGroupKey());
    }

    private RuntimeDtos.TaskDetail detail(VisibleTask visible) {
        TaskStore.TaskRow task = visible.task();
        RunStore.RunRow run = visible.run();
        WorkspaceAccess access = visible.access();
        ReleaseQueries.ReleaseView release = releases.require(task.workspaceId(), run.releaseId());
        List<TaskQueries.DecisionRow> history = taskQueries.decisionsForRun(task.workspaceId(), run.id());
        Map<UUID, String> names = views.names(List.of(run), List.of(task), history);
        TaskQueries.DecisionRow own = history.stream().filter(d -> d.taskId().equals(task.taskId())).findFirst()
                .orElse(null);
        RuntimeDtos.TaskSummary summary = views.task(task, run, names, own);
        boolean participant = !access.has(Permission.RUN_READ) || access.has(Permission.RUN_READ_DATA)
                || access.subjectId().equals(task.assignee()) || access.groupKeys().contains(task.candidateGroupKey())
                || run.initiator().equals(access.subjectId());
        JsonNode input = participant ? run.input() : json.object();
        JsonNode node = release.node(task.nodeId());
        boolean reasonRequired = node == null || node.path("config").path("rejectReasonRequired").asBoolean(true);
        List<RuntimeDtos.TaskDecision> historyViews = new ArrayList<>();
        for (TaskQueries.DecisionRow d : history) {
            String label = d.taskId().equals(task.taskId()) ? task.label() : nodeLabel(release, d.nodeId());
            historyViews.add(views.decision(d, label, names));
        }
        return new RuntimeDtos.TaskDetail(summary.id(), summary.runId(), summary.runReference(), summary.workflowName(),
                summary.businessKey(),
                summary.nodeId(), summary.label(), summary.requestTitle(), summary.requester(), summary.candidateGroup(),
                summary.assignee(), summary.status(), summary.dueAt(), summary.createdAt(), summary.version(),
                summary.decision(),
                new RuntimeDtos.TaskRequest(input, release.inputSchema(),
                        release.inputUi().isMissingNode() ? null : release.inputUi()),
                historyViews, new RuntimeDtos.TaskForm(reasonRequired), allowed(access, task),
                own == null ? null : views.ref(own.actor(), names));
    }

    private static String nodeLabel(ReleaseQueries.ReleaseView release, String nodeId) {
        JsonNode node = release.node(nodeId);
        return node == null ? null : node.path("label").asString(null);
    }

    private static RuntimeDtos.AllowedTaskActions allowed(WorkspaceAccess access, TaskStore.TaskRow task) {
        UUID me = access.subjectId();
        RuntimeDtos.ActionAvailability notOpen = new RuntimeDtos.ActionAvailability(false, "TASK_NOT_OPEN");
        if (!task.isOpen()) {
            return new RuntimeDtos.AllowedTaskActions(notOpen, notOpen, notOpen, notOpen);
        }
        String eligibility = !access.has(Permission.TASK_ACT) ? "FORBIDDEN"
                : !access.groupKeys().contains(task.candidateGroupKey()) ? "NOT_ELIGIBLE"
                : (!task.selfApprovalAllowed() && task.initiator().equals(me)) ? "SELF_APPROVAL_DENIED" : null;
        RuntimeDtos.ActionAvailability claim = eligibility != null ? new RuntimeDtos.ActionAvailability(false, eligibility)
                : task.assignee() != null ? new RuntimeDtos.ActionAvailability(false, "TASK_ALREADY_CLAIMED")
                : new RuntimeDtos.ActionAvailability(true, null);
        boolean assignee = me.equals(task.assignee());
        RuntimeDtos.ActionAvailability release = assignee ? new RuntimeDtos.ActionAvailability(true, null)
                : new RuntimeDtos.ActionAvailability(false, "TASK_NOT_CLAIMED_BY_CALLER");
        RuntimeDtos.ActionAvailability decide = !assignee
                ? new RuntimeDtos.ActionAvailability(false, "TASK_NOT_CLAIMED_BY_CALLER")
                : eligibility != null ? new RuntimeDtos.ActionAvailability(false, eligibility)
                : new RuntimeDtos.ActionAvailability(true, null);
        RuntimeDtos.ActionAvailability reassign = access.has(Permission.RUN_OPERATE)
                ? new RuntimeDtos.ActionAvailability(true, null) : new RuntimeDtos.ActionAvailability(false, "FORBIDDEN");
        return new RuntimeDtos.AllowedTaskActions(claim, release, decide, reassign);
    }

    private Map<UUID, String> names(TaskQueries.DecisionRow decision) {
        return subjects.displayNames(List.of(decision.actor()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value;
    }
}
