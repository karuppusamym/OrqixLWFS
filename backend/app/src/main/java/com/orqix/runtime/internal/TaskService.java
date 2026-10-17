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
    private final RunSuspensions suspensions;
    private final com.orqix.compiler.FormValidator formValidator;

    public TaskService(PolicyService policy, SubjectService subjects, TaskStore tasks, TaskQueries taskQueries,
            RunStore runs, RunEvents events, RuntimeViews views, ReleaseQueries releases, EngineCommands engine,
            IdempotencyService idempotency, AuditWriter audit, RuntimeMetrics metrics, CursorCodec cursors, Json json,
            Clock clock, RunSuspensions suspensions, com.orqix.compiler.FormValidator formValidator) {
        this.formValidator = formValidator;
        this.suspensions = suspensions;
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
        return listTasks(caller, workspaceId, view, cursor, limit, null, null);
    }

    @Transactional(readOnly = true)
    public RuntimeDtos.TaskPage listTasks(Caller caller, UUID workspaceId, String view, String cursor, Integer limit,
            String requestedSort, Integer requestedMinPriority) {
        WorkspaceAccess access = freshVisible(caller, workspaceId);
        String sort = requestedSort == null ? "priority" : requestedSort;
        int minPriority = requestedMinPriority == null ? 0 : requestedMinPriority;
        if (!List.of("priority", "oldest", "newest").contains(sort) || minPriority < 0 || minPriority > 100)
            throw ApiException.malformed("sort must be priority, oldest or newest; minPriority must be 0..100.");
        if ("completed".equals(view) && (requestedSort != null || requestedMinPriority != null))
            throw ApiException.malformed("Completed tasks use decision date ordering and do not support priority filters.");
        if (view == null || !List.of("assigned", "claimable", "completed").contains(view)) {
            throw ApiException.malformed("view must be assigned, claimable or completed.");
        }
        policy.require(access, Permission.TASK_ACT, "listTasks", false);
        int size = Limits.of(limit);
        String filterKey = "listTasks|" + workspaceId + "|" + caller.subjectId() + "|" + view + "|v2|" + sort + "|" + minPriority;
        CursorCodec.Position after = cursors.decode(cursor, filterKey);
        UUID me = caller.subjectId();
        List<TaskStore.TaskRow> rows = switch (view) {
            case "assigned" -> taskQueries.open(workspaceId, me, access.groupKeys(), true, after, size + 1, sort, minPriority);
            case "claimable" -> taskQueries.open(workspaceId, me, access.groupKeys(), false, after, size + 1, sort, minPriority);
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
            next = cursors.encode(new CursorCodec.Position(at, !"completed".equals(view) && "priority".equals(sort) ? last.priority() + ":" + last.taskId() : last.taskId()), filterKey);
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
        suspensions.requireNotSuspended(workspaceId, task.runId());
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
        suspensions.requireNotSuspended(workspaceId, task.runId());
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
        suspensions.requireNotSuspended(workspaceId, task.runId());
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
        UUID decisionId;
        try {
            decisionId = taskQueries.insertDecision(workspaceId, taskId, task.runId(), task.nodeId(),
                    caller.subjectId(), request.action(), blankToNull(request.reason()), blankToNull(request.comment()),
                    flags, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Two approvals of one step decided by the same person at once (S51): the database keeps them apart.
            throw policy.deny(workspaceId, caller.subjectId(), "task.act", "decideTask", false,
                    new ApiException(ProblemCode.SEPARATION_OF_DUTIES,
                            "You already decided this step, so another person must decide this one."));
        }
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
        // A rejection ends a step with several approvers at once: the other approvers' open tasks are cancelled (S51).
        int cancelledSiblings = reject ? tasks.cancelOpenForNode(workspaceId, task.runId(), task.nodeId(), now) : 0;
        runs.mutate(workspaceId, task.runId(), m -> {
            if (reject) {
                m.removeStep(task.nodeId());
            } else {
                m.removeStepOfTask(task.nodeId(), taskId);
            }
            m.settle();
        });
        Map<String, Object> decided = new LinkedHashMap<>();
        decided.put("action", request.action());
        decided.put("hasReason", !blank(request.reason()));
        decided.put("hasComment", !blank(request.comment()));
        decided.put("policyFlags", flags);
        if (cancelledSiblings > 0) {
            decided.put("otherApproversCancelled", cancelledSiblings);
        }
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
        return changeAssignee(caller, workspaceId, taskId, request, false);
    }

    @Transactional
    public RuntimeDtos.TaskDetail delegate(Caller caller, UUID workspaceId, String taskId,
            RuntimeDtos.ReassignRequest request) {
        return changeAssignee(caller, workspaceId, taskId, request, true);
    }

    @Transactional(readOnly = true)
    public List<SubjectService.SubjectRef> delegates(Caller caller, UUID workspaceId, String taskId) {
        var visible = visible(caller, workspaceId, taskId);
        requireEligible(visible.access(), visible.task(), "listTaskDelegates");
        if (!visible.task().isOpen() || !caller.subjectId().equals(visible.task().assignee()))
            throw ApiException.forbidden();
        return subjects.knownSubjects(500).stream().filter(s -> !s.id().equals(caller.subjectId()))
                .filter(s -> eligibleSubject(workspaceId, visible.task(), s.id()))
                .limit(100).map(s -> new SubjectService.SubjectRef(s.id(), s.displayName())).toList();
    }

    private RuntimeDtos.TaskDetail changeAssignee(Caller caller, UUID workspaceId, String taskId,
            RuntimeDtos.ReassignRequest request, boolean delegation) {
        VisibleTask visible = visibleLocked(caller, workspaceId, taskId);
        TaskStore.TaskRow task = visible.task();
        if (delegation) {
            requireEligible(visible.access(), task, "delegateTask");
            if (!caller.subjectId().equals(task.assignee()) || request.assigneeSubjectId() == null
                    || caller.subjectId().equals(request.assigneeSubjectId())) throw ApiException.forbidden();
        } else policy.require(visible.access(), Permission.RUN_OPERATE, "reassignTask", true);
        if (!task.isOpen()) {
            throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task is no longer open.");
        }
        suspensions.requireNotSuspended(workspaceId, task.runId());
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
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), delegation ? "task.delegated" : "task.reassigned",
                "task", taskId, AuditWriter.Outcome.SUCCESS, metadata));
        return detail(visible(caller, workspaceId, taskId));
    }

    @Transactional
    public RuntimeDtos.TaskDetail priority(Caller caller, UUID workspaceId, String taskId, RuntimeDtos.PriorityRequest request) {
        VisibleTask visible = visibleLocked(caller, workspaceId, taskId);
        TaskStore.TaskRow task = visible.task();
        if (!visible.access().has(Permission.RUN_OPERATE)) {
            requireEligible(visible.access(), task, "setTaskPriority");
            if (!caller.subjectId().equals(task.assignee())) throw ApiException.forbidden();
        }
        if (!task.isOpen()) throw new ApiException(ProblemCode.TASK_NOT_OPEN);
        requireVersion(task, request.expectedVersion());
        if (!tasks.setPriority(workspaceId, taskId, request.expectedVersion(), request.priority()))
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT).with("currentVersion", task.version());
        Map<String, Object> data = Map.of("taskId", taskId, "from", task.priority(), "to", request.priority(), "reason", request.reason());
        events.append(workspaceId, task.runId(), "TASK_PRIORITY_CHANGED", task.nodeId(), caller.subjectId(), data);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "task.priority-changed",
                "task", taskId, AuditWriter.Outcome.SUCCESS, data));
        return detail(visible(caller, workspaceId, taskId));
    }

    // ---------------------------------------------------------------- helpers

    record VisibleTask(WorkspaceAccess access, TaskStore.TaskRow task, RunStore.RunRow run) {
    }

    private VisibleTask visible(Caller caller, UUID workspaceId, String taskId) {
        WorkspaceAccess access = freshVisible(caller, workspaceId);
        TaskStore.TaskRow task = tasks.find(workspaceId, taskId).orElseThrow(ApiException::notFound);
        return check(access, task);
    }

    private VisibleTask visibleLocked(Caller caller, UUID workspaceId, String taskId) {
        WorkspaceAccess access = freshVisible(caller, workspaceId);
        TaskStore.TaskRow task = tasks.lock(workspaceId, taskId).orElseThrow(ApiException::notFound);
        return check(access, task);
    }

    /** Visible to eligible candidates, the assignee, past deciders and run.read holders; otherwise 404. */
    private VisibleTask check(WorkspaceAccess access, TaskStore.TaskRow task) {
        UUID me = access.subjectId();
        boolean candidate = access.groupKeys().contains(task.candidateGroupKey()) && hasSkills(access,task) && access.has(Permission.TASK_ACT);
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
        if (!access.groupKeys().contains(task.candidateGroupKey()) || !hasSkills(access,task)) {
            throw policy.deny(access.workspaceId(), access.subjectId(), "task.act", operationId, false,
                    new ApiException(ProblemCode.NOT_ELIGIBLE, "This task requires its candidate group and every required skill group."));
        }
        if (!task.selfApprovalAllowed() && task.initiator().equals(access.subjectId())) {
            throw policy.deny(access.workspaceId(), access.subjectId(), "task.act", operationId, false,
                    new ApiException(ProblemCode.SELF_APPROVAL_DENIED, "You cannot approve your own request."));
        }
        if (tasks.separatedFrom(task.workspaceId(), task.taskId(), access.subjectId())) {
            throw policy.deny(access.workspaceId(), access.subjectId(), "task.act", operationId, false,
                    new ApiException(ProblemCode.SEPARATION_OF_DUTIES,
                            "You decided an earlier step of this request, so another person must decide this one."));
        }
    }

    private static void requireVersion(TaskStore.TaskRow task, int expected) {
        if (task.version() != expected) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT, "The task changed since you loaded it.")
                    .with("currentVersion", task.version());
        }
    }

    /** Eligibility of another subject, using its stored IdP groups from the latest login (V4). */
    boolean eligibleSubject(UUID workspaceId, TaskStore.TaskRow task, UUID subjectId) {
        if (!task.selfApprovalAllowed() && task.initiator().equals(subjectId)) {
            return false;
        }
        if (tasks.separatedFrom(task.workspaceId(), task.taskId(), subjectId)) {
            return false;
        }
        SubjectService.Subject subject = subjects.find(subjectId).orElse(null);
        if (subject == null || subject.disabled()) {
            return false;
        }
        Caller other = new Caller(subjectId, subject.displayName(), subject.email(), subject.issuer(),
                subject.externalSubject(), subjects.storedGroups(subjectId), false);
        WorkspaceAccess access = policy.freshAccess(other, workspaceId).orElse(null);
        return access != null && access.has(Permission.TASK_ACT)
                && hasSkills(access,task)
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
        boolean participant = !access.has(Permission.RUN_READ) || access.has(Permission.RUN_READ_DATA)
                || access.subjectId().equals(task.assignee()) || access.groupKeys().contains(task.candidateGroupKey()) && hasSkills(access,task)
                || run.initiator().equals(access.subjectId());
        RuntimeDtos.TaskSummary summary = views.task(task, run, names, own, participant);
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
                summary.assignee(), summary.status(), summary.dueAt(), summary.createdAt(), summary.version(), summary.priority(),
                summary.decision(),
                new RuntimeDtos.TaskRequest(input, release.inputSchema(),
                        release.inputUi().isMissingNode() ? null : release.inputUi()),
                historyViews, new RuntimeDtos.TaskForm(reasonRequired, correctableFor(access, task, node, run)),
                allowed(access, task, suspensions.isSuspended(task.workspaceId(), task.runId())),
                own == null ? null : views.ref(own.actor(), names));
    }

    /** The request fields the caller may correct on this open task now: the holder of the task, or an operator who can see the data. */
    private List<String> correctableFor(WorkspaceAccess access, TaskStore.TaskRow task, JsonNode node, RunStore.RunRow run) {
        if (node == null || !task.isOpen() || suspensions.isSuspended(task.workspaceId(), task.runId())) {
            return List.of();
        }
        boolean holder = access.subjectId().equals(task.assignee()) && access.has(Permission.TASK_ACT);
        boolean operator = access.has(Permission.RUN_OPERATE) && access.has(Permission.RUN_READ_DATA);
        if (!holder && !operator) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        node.path("config").path("correctable").forEach(f -> fields.add(f.asString()));
        return fields;
    }

    // ---------------------------------------------------------------- correction by the task holder (S42)

    /**
     * Corrects declared request fields while the task is open. The workflow author named the fields and publishing proved
     * that no earlier step has used them; this adds the runtime guards (holder or operator, open task, not suspended,
     * current version) and records before/after values with the reason.
     */
    @Transactional
    public RuntimeDtos.TaskDetail correctInput(Caller caller, UUID workspaceId, String taskId, String idempotencyKey,
            RuntimeDtos.CorrectTaskInputRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        VisibleTask visible = visibleLocked(caller, workspaceId, taskId);
        TaskStore.TaskRow task = visible.task();
        WorkspaceAccess access = visible.access();
        boolean holder = caller.subjectId().equals(task.assignee());
        boolean operator = access.has(Permission.RUN_OPERATE) && access.has(Permission.RUN_READ_DATA);
        if (!holder && !operator) {
            throw ApiException.forbidden();
        }
        if (holder) {
            requireEligible(access, task, "correctTaskInput");
        }
        String hash = idempotency.requestHash("correctTaskInput",
                Map.of("workspaceId", workspaceId.toString(), "taskId", taskId), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "correctTaskInput", key, hash);
        if (claim.isReplay()) {
            return detail(visible(caller, workspaceId, taskId));
        }
        if (!task.isOpen()) {
            throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This task is no longer open.");
        }
        suspensions.requireNotSuspended(workspaceId, task.runId());
        requireVersion(task, request.expectedVersion());
        RunStore.RunRow run = runs.lock(workspaceId, task.runId()).orElseThrow(ApiException::notFound);
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, run.releaseId());
        JsonNode node = release.node(task.nodeId());
        List<String> allowed = new ArrayList<>();
        if (node != null) {
            node.path("config").path("correctable").forEach(f -> allowed.add(f.asString()));
        }
        if (request.changes().isEmpty() || request.changes().size() > 10) {
            throw ApiException.malformed("changes must contain 1..10 request fields.");
        }
        tools.jackson.databind.node.ObjectNode corrected = (tools.jackson.databind.node.ObjectNode) run.input().deepCopy();
        tools.jackson.databind.node.ObjectNode before = json.object();
        tools.jackson.databind.node.ObjectNode after = json.object();
        for (var change : request.changes().entrySet()) {
            JsonNode value = change.getValue();
            if (!allowed.contains(change.getKey()) || value == null || !value.isValueNode() || value.isNull()) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED,
                        "Only the fields this step lets you correct can change, and only to a single value.")
                        .withFieldErrors(List.of(new ApiException.FieldError("/changes/" + change.getKey(), "enum",
                                "Not a field this step lets you correct")));
            }
            before.set(change.getKey(), corrected.path(change.getKey()));
            after.set(change.getKey(), value);
            corrected.set(change.getKey(), value);
        }
        if (corrected.equals(run.input())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Change at least one value.");
        }
        var errors = formValidator.validate(json.write(release.inputSchema()), json.write(corrected),
                json.write(release.inputUi()));
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The corrected request does not match its form.")
                    .withFieldErrors(errors.stream()
                            .map(e -> new ApiException.FieldError("/changes" + e.pointer(), e.code(), e.message())).toList());
        }
        try {
            engine.correctInput(run.engineProcessInstanceId(), json.write(corrected), caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "The request is busy; try again shortly.");
        }
        int version = runs.correctInputWhileActive(workspaceId, run.id(), run.version(), corrected);
        // A decision loaded before the correction must not go through on the old data.
        tasks.bumpVersion(workspaceId, taskId, task.version());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", before);
        data.put("after", after);
        data.put("reason", request.reason().trim());
        data.put("fields", request.changes().size());
        data.put("version", version);
        events.append(workspaceId, run.id(), "INPUT_CORRECTED", task.nodeId(), caller.subjectId(), data);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", taskId);
        metadata.put("nodeId", task.nodeId());
        metadata.put("fields", request.changes().size());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "task.input-corrected", "task", taskId, AuditWriter.Outcome.SUCCESS, metadata));
        idempotency.complete(claim, "taskInputCorrection", Integer.toString(version), 200);
        return detail(visible(caller, workspaceId, taskId));
    }

    private static String nodeLabel(ReleaseQueries.ReleaseView release, String nodeId) {
        JsonNode node = release.node(nodeId);
        return node == null ? null : node.path("label").asString(null);
    }

    private boolean hasSkills(WorkspaceAccess access, TaskStore.TaskRow task) {
        return access.groupKeys().containsAll(tasks.requiredSkills(task.workspaceId(),task.taskId()));
    }

    private WorkspaceAccess freshVisible(Caller caller, UUID workspaceId) {
        return policy.freshAccess(caller,workspaceId).filter(WorkspaceAccess::visible).orElseThrow(ApiException::notFound);
    }

    private RuntimeDtos.AllowedTaskActions allowed(WorkspaceAccess access, TaskStore.TaskRow task, boolean suspended) {
        UUID me = access.subjectId();
        RuntimeDtos.ActionAvailability notOpen = new RuntimeDtos.ActionAvailability(false, "TASK_NOT_OPEN");
        if (!task.isOpen()) {
            return new RuntimeDtos.AllowedTaskActions(notOpen, notOpen, notOpen, notOpen, notOpen);
        }
        if (suspended) {
            RuntimeDtos.ActionAvailability frozen = new RuntimeDtos.ActionAvailability(false, "RUN_SUSPENDED");
            return new RuntimeDtos.AllowedTaskActions(frozen, frozen, frozen, frozen,
                    access.has(Permission.RUN_OPERATE) ? new RuntimeDtos.ActionAvailability(true, null)
                            : new RuntimeDtos.ActionAvailability(false, "FORBIDDEN"));
        }
        String eligibility = !access.has(Permission.TASK_ACT) ? "FORBIDDEN"
                : !access.groupKeys().contains(task.candidateGroupKey()) || !hasSkills(access,task) ? "NOT_ELIGIBLE"
                : (!task.selfApprovalAllowed() && task.initiator().equals(me)) ? "SELF_APPROVAL_DENIED"
                : tasks.separatedFrom(task.workspaceId(), task.taskId(), me) ? "SEPARATION_OF_DUTIES" : null;
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
        return new RuntimeDtos.AllowedTaskActions(claim, release, decide, reassign, access.has(Permission.RUN_OPERATE) || assignee && eligibility == null ? new RuntimeDtos.ActionAvailability(true, null) : new RuntimeDtos.ActionAvailability(false, "FORBIDDEN"));
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
