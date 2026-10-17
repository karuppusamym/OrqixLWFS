package com.orqix.runtime.internal;

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
import com.orqix.compiler.FormValidator;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.EffectJournal;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.pagination.Limits;
import com.orqix.shared.pagination.Page;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Start a run (S5 section 3), run lists, run detail and the run timeline (S3 section 7). */
@Service
public class RunService {

    public record Started(RuntimeDtos.RunSummary run, boolean replayed) {
    }

    private final PolicyService policy;
    private final ReleaseQueries releases;
    private final FormValidator formValidator;
    private final RunStore runs;
    private final RunEvents events;
    private final RunQueries runQueries;
    private final TaskStore tasks;
    private final TaskQueries taskQueries;
    private final RunAccess runAccess;
    private final RuntimeViews views;
    private final EngineCommands engine;
    private final EffectJournal journal;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final RuntimeMetrics metrics;
    private final CursorCodec cursors;
    private final OrqixProperties properties;
    private final Json json;
    private final RunAttachments attachments;
    private final RunStages stages;
    private final RunSuspensions suspensions;

    public RunService(PolicyService policy, ReleaseQueries releases, FormValidator formValidator, RunStore runs,
            RunEvents events, RunQueries runQueries, TaskStore tasks, TaskQueries taskQueries, RunAccess runAccess,
            RuntimeViews views, EngineCommands engine, EffectJournal journal, IdempotencyService idempotency,
            AuditWriter audit, RuntimeMetrics metrics, CursorCodec cursors, OrqixProperties properties, Json json,
            RunAttachments attachments, RunStages stages, RunSuspensions suspensions) {
        this.suspensions = suspensions;
        this.attachments = attachments;
        this.stages = stages;
        this.policy = policy;
        this.releases = releases;
        this.formValidator = formValidator;
        this.runs = runs;
        this.events = events;
        this.runQueries = runQueries;
        this.tasks = tasks;
        this.taskQueries = taskQueries;
        this.runAccess = runAccess;
        this.views = views;
        this.engine = engine;
        this.journal = journal;
        this.idempotency = idempotency;
        this.audit = audit;
        this.metrics = metrics;
        this.cursors = cursors;
        this.properties = properties;
        this.json = json;
    }

    // ---------------------------------------------------------------- start (S5 section 3)

    @Transactional
    public Started start(Caller caller, UUID workspaceId, String idempotencyKey, RuntimeDtos.StartRunRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        ReleaseQueries.WorkflowRef workflow = releases.workflow(workspaceId, request.workflowId())
                .orElseThrow(ApiException::notFound);
        if (!access.canStart(workflow.id())) {
            if (!access.has(Permission.WORKFLOW_READ)) {
                throw ApiException.notFound();
            }
            throw policy.deny(workspaceId, caller.subjectId(), "workflow.start", "startRun", false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        if (request.input() == null || !request.input().isObject()) {
            throw ApiException.malformed("input must be a JSON object.");
        }
        if (Json.depth(request.input()) > properties.limits().jsonMaxDepth()) {
            throw ApiException.malformed("input is nested too deeply.");
        }
        String hash = idempotency.requestHash("startRun", Map.of("workspaceId", workspaceId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "startRun", key, hash);
        if (claim.isReplay()) {
            RunStore.RunRow run = runs.find(workspaceId, UUID.fromString(claim.replay().resourceId())).orElseThrow();
            return new Started(summary(run, access), true);
        }

        workflow = releases.lockWorkflow(workspaceId, workflow.id()).orElseThrow(ApiException::notFound);
        if (workflow.activeReleaseId() == null) {
            throw new ApiException(ProblemCode.NO_ACTIVE_RELEASE, "This workflow has not been published yet.");
        }
        if (request.expectedReleaseId() != null && !request.expectedReleaseId().equals(workflow.activeReleaseId())) {
            throw new ApiException(ProblemCode.RELEASE_NOT_ACTIVE, "A newer release of this workflow is active; reload the form.");
        }
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, workflow.activeReleaseId());
        // Child workflows start on behalf of the requester, who therefore needs the right to start them (S12 section 4).
        for (JsonNode pin : release.manifest().path("workflows")) {
            ReleaseQueries.ReleaseView child = releases.require(workspaceId, UUID.fromString(pin.path("releaseId").asString()));
            if (!access.canStart(child.workflowId())) {
                throw policy.deny(workspaceId, caller.subjectId(), "workflow.start", "startRun", false,
                        new ApiException(ProblemCode.FORBIDDEN, "This request starts \"" + child.workflowName()
                                + "\" for you, and you are not allowed to start that workflow."));
            }
        }
        List<FormValidator.FieldError> errors = new java.util.ArrayList<>(formValidator.validate(
                json.write(release.inputSchema()), json.write(request.input()),json.write(release.inputUi())));
        if (errors.isEmpty()) {
            // A "wait until a date" step cannot wait further than its horizon (S12 section 10).
            errors.addAll(WaitDates.check(release.definition(), request.input(), WaitDates.today()));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some values are not valid.").withFieldErrors(
                    errors.stream().map(e -> new ApiException.FieldError("/input" + e.pointer(), e.code(), e.message()))
                            .toList());
        }
        // Files chosen on the form (fields of format attachment): must be the caller's own and fit the field (S17).
        RunAttachments.StartPlan files = attachments.planStart(caller, workspaceId, release.inputSchema(), request.input());
        if (runs.countActive(workspaceId, workflow.id()) >= release.maxConcurrentRuns()) {
            throw new ApiException(ProblemCode.QUOTA_EXCEEDED, "Too many requests of this workflow are in progress.")
                    .withHeader("Retry-After", "60");
        }

        UUID runId = Ids.newId();
        runs.insert(runId, workspaceId, workflow.id(), release.id(), request.businessKey(), caller.subjectId(),
                request.input());
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("releaseNumber", release.releaseNumber());
        started.put("businessKey", request.businessKey());
        events.append(workspaceId, runId, "RUN_STARTED", null, caller.subjectId(), started);
        attachments.attachAtStart(files, workspaceId, runId, caller.subjectId());

        Map<String, Object> variables = new HashMap<>();
        variables.put(ProcessData.RUN_ID, runId.toString());
        variables.put(ProcessData.WORKSPACE_ID, workspaceId.toString());
        variables.put(ProcessData.INITIATOR, caller.subjectId().toString());
        variables.put(ProcessData.INPUT, json.write(request.input()));
        String processInstanceId;
        try {
            processInstanceId = engine.startProcess(release.processDefinitionId(), access.workspace().engineTenantId(),
                    runId.toString(), variables, caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE).withHeader("Retry-After", "2");
        }
        runs.setEngineProcessInstance(workspaceId, runId, processInstanceId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowKey", workflow.key());
        metadata.put("releaseNumber", release.releaseNumber());
        audit.write(new AuditWriter.Entry(workspaceId, caller.machine() ? AuditWriter.ActorType.SERVICE
                : AuditWriter.ActorType.USER, caller.subjectId(), "run.started", "run", runId.toString(),
                AuditWriter.Outcome.SUCCESS, metadata));
        idempotency.complete(claim, "run", runId.toString(), 201);
        metrics.runStarted();
        return new Started(summary(runs.find(workspaceId, runId).orElseThrow(), access), false);
    }

    // ---------------------------------------------------------------- lists

    @Transactional(readOnly = true)
    public RuntimeDtos.RunPage listRuns(Caller caller, UUID workspaceId, RunQueries.Filter filter, String cursor,
            Integer limit) {
        WorkspaceAccess access = policy.freshAccess(caller,workspaceId).filter(WorkspaceAccess::visible).orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_READ, "listRuns", false);
        return page(access, filter, cursor, limit, "listRuns");
    }

    @Transactional(readOnly = true)
    public RuntimeDtos.RunPage listMyRequests(Caller caller, UUID workspaceId, String lifecycle, String cursor,
            Integer limit) {
        WorkspaceAccess access = policy.requireMember(caller, workspaceId);
        RunQueries.Filter filter = new RunQueries.Filter(null, lifecycle, null, null, null, null, caller.subjectId());
        return page(access, filter, cursor, limit, "listMyRequests");
    }

    private RuntimeDtos.RunPage page(WorkspaceAccess access, RunQueries.Filter filter, String cursor, Integer limit,
            String operation) {
        int size = Limits.of(limit);
        String filterKey = operation + "|" + access.workspaceId() + "|" + filter;
        List<RunStore.RunRow> rows = runQueries.list(access.workspaceId(), filter, cursors.decode(cursor, filterKey),
                size + 1);
        Page.Result<RunStore.RunRow> page = Page.of(rows, size,
                r -> new CursorCodec.Position(r.createdAt(), r.id().toString()), cursors, filterKey);
        Map<UUID, String> names = views.names(page.items(), List.of(), List.of());
        java.util.Set<UUID> titleVisible = dataVisibleRuns(access, page.items());
        return new RuntimeDtos.RunPage(page.items().stream()
                .map(r -> views.run(r, names, access.workspace().isTest(), titleVisible.contains(r.id()))).toList(),
                page.nextCursor());
    }

    /**
     * The runs whose request data the caller may read (the rule of {@link RunAccess.Visible#dataVisible()}), for list
     * views: run.read-data, the initiator, or a task participant, with a constant number of queries.
     */
    private java.util.Set<UUID> dataVisibleRuns(WorkspaceAccess access, List<RunStore.RunRow> rows) {
        java.util.Set<UUID> visible = new java.util.HashSet<>();
        if (access.has(Permission.RUN_READ_DATA)) {
            rows.forEach(r -> visible.add(r.id()));
            return visible;
        }
        List<RunStore.RunRow> others = new java.util.ArrayList<>();
        for (RunStore.RunRow run : rows) {
            if (run.initiator().equals(access.subjectId())) {
                visible.add(run.id());
            } else {
                others.add(run);
            }
        }
        visible.addAll(runAccess.participantRuns(access, others));
        return visible;
    }

    /**
     * Request titles of the given runs for a notification list: only for runs whose data the caller may read, absent
     * otherwise.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> requestTitles(WorkspaceAccess access, java.util.Collection<UUID> runIds) {
        List<RunStore.RunRow> rows = runs.findAll(access.workspaceId(), runIds);
        java.util.Set<UUID> visible = dataVisibleRuns(access, rows);
        Map<UUID, String> titles = new java.util.HashMap<>();
        for (RunStore.RunRow run : rows) {
            if (visible.contains(run.id())) {
                String title = RuntimeViews.requestTitle(run.input(), releases.require(run.workspaceId(), run.releaseId()));
                if (title != null) {
                    titles.put(run.id(), title);
                }
            }
        }
        return titles;
    }

    // ---------------------------------------------------------------- detail and timeline

    @Transactional(readOnly = true)
    public RuntimeDtos.RunDetail getRun(Caller caller, UUID workspaceId, UUID runId) {
        return detail(runAccess.require(caller, workspaceId, runId), List.of());
    }

    @Transactional(readOnly = true)
    public RuntimeDtos.EventPage events(Caller caller, UUID workspaceId, UUID runId, Long after, Integer limit) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        int size = Limits.of(limit);
        long from = after == null ? 0 : after;
        if (from < 0) {
            throw ApiException.malformed("after must be >= 0.");
        }
        List<RunEvents.EventRow> rows = events.list(workspaceId, runId, from, size + 1);
        boolean hasMore = rows.size() > size;
        List<RunEvents.EventRow> items = hasMore ? rows.subList(0, size) : rows;
        Map<UUID, String> names = views.namesOf(items.stream().map(RunEvents.EventRow::actorSubjectId)
                .filter(java.util.Objects::nonNull).toList());
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, visible.run().releaseId());
        List<RuntimeDtos.RunEvent> result = new ArrayList<>();
        for (RunEvents.EventRow row : items) {
            result.add(new RuntimeDtos.RunEvent(row.seq(), row.type(), row.nodeId(), release.nodeLabel(row.nodeId()),
                    row.actorSubjectId() == null ? null : views.ref(row.actorSubjectId(), names), row.occurredAt(),
                    eventData(row, visible.dataVisible())));
        }
        long nextAfter = items.isEmpty() ? from : items.get(items.size() - 1).seq();
        return new RuntimeDtos.EventPage(result, nextAfter, hasMore, visible.run().lifecycle());
    }

    /** Decision values belong to the same permission scope as request/output data. */
    private JsonNode eventData(RunEvents.EventRow row, boolean dataVisible) {
        if (!dataVisible && "INPUT_CORRECTED".equals(row.type())) {
            ObjectNode metadata=json.object(); metadata.set("fields",row.data().path("fields")); metadata.set("version",row.data().path("version"));
            metadata.put("detailsRedacted",true); return metadata;
        }
        if (!dataVisible && ("ATTACHMENT_ADDED".equals(row.type()) || "ATTACHMENT_REMOVED".equals(row.type()))) {
            // File names and descriptions belong to the request data; a reader without it sees only that a file changed.
            ObjectNode redacted = json.object();
            redacted.set("attachmentId", row.data().path("attachmentId"));
            redacted.put("detailsRedacted", true);
            return redacted;
        }
        if (dataVisible || !"DECISION_EVALUATED".equals(row.type())) return row.data();
        ObjectNode metadata = json.object();
        JsonNode data = row.data();
        if (data != null && data.isObject()) {
            if (data.has("decisionKey")) metadata.set("decisionKey", data.path("decisionKey"));
            if (data.has("version")) metadata.set("version", data.path("version"));
        }
        metadata.put("evidenceRedacted", true);
        return metadata;
    }

    /** RunDetail filtered by the caller's permissions (S3 section 3 rule 4). */
    public RuntimeDtos.RunDetail detail(RunAccess.Visible visible, List<EffectJournal.Effect> notReversed) {
        RunStore.RunRow run = visible.run();
        WorkspaceAccess access = visible.access();
        ReleaseQueries.ReleaseView release = releases.require(run.workspaceId(), run.releaseId());
        List<TaskStore.TaskRow> runTasks = tasks.forRun(run.workspaceId(), run.id());
        List<TaskQueries.DecisionRow> decisions = taskQueries.decisionsForRun(run.workspaceId(), run.id());
        Map<UUID, String> names = views.names(List.of(run), runTasks, decisions);
        Map<String, TaskQueries.DecisionRow> byTask = new HashMap<>();
        decisions.forEach(d -> byTask.put(d.taskId(), d));
        boolean data = visible.dataVisible();
        List<RuntimeDtos.TaskSummary> taskViews = runTasks.stream()
                .map(t -> views.task(t, run, names, byTask.get(t.taskId()), data)).toList();
        boolean operate = access.has(Permission.RUN_OPERATE);
        List<EffectJournal.Effect> effects = journal.forRun(run.workspaceId(), run.id());
        List<RuntimeDtos.Effect> effectViews = operate ? effects.stream()
                .map(e -> views.effect(e, label(release, e.nodeId()))).toList() : null;
        List<RuntimeDtos.Effect> reversedViews = notReversed.isEmpty() ? null : notReversed.stream()
                .map(e -> views.effect(e, label(release, e.nodeId()))).toList();
        RuntimeDtos.RunSummary summary = views.run(run, names, access.workspace().isTest(), data);
        RuntimeDtos.StageProgress stageProgress = stages.progress(run, release);
        RunSuspensions.Suspension suspended = suspensions.find(run.workspaceId(), run.id()).orElse(null);
        String suspendBlocker = operate && suspended == null && run.isActive()
                ? OperatorService.suspendBlocker(run, runs, engine) : null;
        return new RuntimeDtos.RunDetail(summary.id(), summary.reference(), summary.workflow(), summary.release(), summary.businessKey(),
                summary.requestTitle(),
                summary.initiator(), summary.lifecycle(), summary.outcome(), summary.outcomeKind(), summary.currentSteps(),
                summary.attention(), summary.createdAt(), summary.updatedAt(), summary.endedAt(), summary.isTest(),
                summary.version(), data ? run.input() : null, RuntimeViews.inputLabels(release),
                data ? run.output() : null, taskViews, effectViews, reversedViews,
                allowedActions(run, operate, effects, suspended != null, suspendBlocker), summary.parentRun(),
                views.children(run), stageProgress == null ? null : stageProgress.current(),
                stageProgress == null ? List.of() : stageProgress.stages(),
                suspended == null ? null : new RuntimeDtos.Suspension(suspended.suspendedAt(),
                        operate ? suspended.reason() : null));
    }

    static RuntimeDtos.AllowedRunActions allowedActions(RunStore.RunRow run, boolean operate,
            List<EffectJournal.Effect> effects, boolean suspended, String suspendBlocker) {
        RuntimeDtos.ActionAvailability forbidden = new RuntimeDtos.ActionAvailability(false, "FORBIDDEN");
        if (!operate) {
            return new RuntimeDtos.AllowedRunActions(forbidden, forbidden, forbidden, forbidden, forbidden);
        }
        RuntimeDtos.ActionAvailability suspend = !run.isActive()
                ? new RuntimeDtos.ActionAvailability(false, "RUN_NOT_ACTIVE")
                : suspended ? new RuntimeDtos.ActionAvailability(false, "RUN_SUSPENDED")
                : suspendBlocker != null ? new RuntimeDtos.ActionAvailability(false, "RUN_NOT_SUSPENDABLE")
                : new RuntimeDtos.ActionAvailability(true, null);
        RuntimeDtos.ActionAvailability resume = !run.isActive()
                ? new RuntimeDtos.ActionAvailability(false, "RUN_NOT_ACTIVE")
                : suspended ? new RuntimeDtos.ActionAvailability(true, null)
                : new RuntimeDtos.ActionAvailability(false, "RUN_NOT_SUSPENDED");
        RuntimeDtos.ActionAvailability cancel = run.isActive() ? new RuntimeDtos.ActionAvailability(true, null)
                : new RuntimeDtos.ActionAvailability(false, "RUN_NOT_ACTIVE");
        boolean unknown = effects.stream().anyMatch(e -> EffectJournal.UNKNOWN.equals(e.state()));
        RuntimeDtos.ActionAvailability retry;
        if (!RunStore.NEEDS_ATTENTION.equals(run.lifecycle())) {
            retry = new RuntimeDtos.ActionAvailability(false, run.isActive() ? "RUN_NOT_RECOVERABLE" : "RUN_NOT_ACTIVE");
        } else if (run.attention() != null && RunWatchdog.REASON.equals(run.attention().path("reason").asString())) {
            retry = new RuntimeDtos.ActionAvailability(false, "RUN_NOT_RECOVERABLE");
        } else if (unknown) {
            retry = new RuntimeDtos.ActionAvailability(false, "EFFECT_NOT_RECONCILED");
        } else {
            retry = new RuntimeDtos.ActionAvailability(true, null);
        }
        boolean reconcilable = effects.stream().anyMatch(e -> EffectJournal.UNKNOWN.equals(e.state())
                || EffectJournal.PERMANENT_FAILURE.equals(e.state()));
        RuntimeDtos.ActionAvailability reconcile = reconcilable && run.isActive()
                ? new RuntimeDtos.ActionAvailability(true, null)
                : new RuntimeDtos.ActionAvailability(false, "EFFECT_STATE_CONFLICT");
        if (suspended) {
            retry = new RuntimeDtos.ActionAvailability(false, "RUN_SUSPENDED");
            reconcile = new RuntimeDtos.ActionAvailability(false, "RUN_SUSPENDED");
        }
        return new RuntimeDtos.AllowedRunActions(cancel, retry, reconcile, suspend, resume);
    }

    static String label(ReleaseQueries.ReleaseView release, String nodeId) {
        JsonNode node = release.node(nodeId);
        return node == null ? null : node.path("label").asString(null);
    }

    RuntimeDtos.RunSummary summary(RunStore.RunRow run, WorkspaceAccess access) {
        // The caller started the run, so may read its request data.
        return views.run(run, views.names(List.of(run), List.of(), List.of()), access.workspace().isTest(), true);
    }

    @SuppressWarnings("unused")
    private static Instant now() {
        return Instant.now();
    }
}
