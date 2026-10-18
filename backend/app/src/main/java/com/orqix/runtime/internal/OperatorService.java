package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EffectJournal;
import com.orqix.notification.NotificationService;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Operator recovery (S5 section 7): retry, reconcile and cancel. Each requires {@code run.operate}, an idempotency key,
 * a reason or evidence, and writes run events and audit. Orqix never fabricates a success without operator evidence.
 */
@Service
public class OperatorService {

    public record Result<T>(T body, boolean replayed) {
    }

    private final PolicyService policy;
    private final RunAccess runAccess;
    private final RunStore runs;
    private final TaskStore tasks;
    private final RunEvents events;
    private final RunService runService;
    private final RuntimeViews views;
    private final ReleaseQueries releases;
    private final ConnectionService connections;
    private final EffectJournal journal;
    private final EngineCommands engine;
    private final NotificationService notifications;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;
    private final ChildRuns children;
    private final RunSuspensions suspensions;
    private final RunMigrations migrations;
    private final ExternalJobService externalJobs;
    private final RunRepair repairs;
    private final ConsistencyFindings consistency;
    private final RunConsistencyScan scan;

    public OperatorService(PolicyService policy, RunAccess runAccess, RunStore runs, TaskStore tasks, RunEvents events,
            RunService runService, RuntimeViews views, ReleaseQueries releases, ConnectionService connections,
            EffectJournal journal, EngineCommands engine, NotificationService notifications,
            IdempotencyService idempotency, AuditWriter audit, Json json, Clock clock, ChildRuns children,
            RunSuspensions suspensions, RunMigrations migrations, ExternalJobService externalJobs, RunRepair repairs,
            ConsistencyFindings consistency, java.util.Optional<RunConsistencyScan> scan) {
        this.repairs = repairs;
        this.consistency = consistency;
        this.scan = scan.orElse(null);
        this.externalJobs = externalJobs;
        this.migrations = migrations;
        this.suspensions = suspensions;
        this.policy = policy;
        this.runAccess = runAccess;
        this.runs = runs;
        this.tasks = tasks;
        this.events = events;
        this.runService = runService;
        this.views = views;
        this.releases = releases;
        this.connections = connections;
        this.journal = journal;
        this.engine = engine;
        this.notifications = notifications;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.children = children;
    }

    // ---------------------------------------------------------------- retry (S5 section 7.1)

    @Transactional
    public Result<RuntimeDtos.RunDetail> retry(Caller caller, UUID workspaceId, UUID runId, String idempotencyKey,
            RuntimeDtos.RetryRunRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "retryRun", true);
        String hash = idempotency.requestHash("retryRun", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "retryRun", key, hash);
        if (claim.isReplay()) {
            return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), List.of()), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        requireVersion(run, request.expectedVersion());
        if (!RunStore.NEEDS_ATTENTION.equals(run.lifecycle())) {
            throw new ApiException(run.isActive() ? ProblemCode.RUN_NOT_RECOVERABLE : ProblemCode.RUN_NOT_ACTIVE,
                    "Only runs that need attention can be retried.").with("currentVersion", run.version());
        }
        List<EffectJournal.Effect> effects = journal.forRun(workspaceId, runId);
        if (effects.stream().anyMatch(e -> EffectJournal.UNKNOWN.equals(e.state()))) {
            throw new ApiException(ProblemCode.EFFECT_NOT_RECONCILED,
                    "Check the external system and reconcile the step before retrying.");
        }
        if (externalJobs.retryFailed(workspaceId, runId)) {
            // A worker job ran out of attempts: offer it to the workers again from the first attempt.
            runs.mutate(workspaceId, runId, m -> {
                m.lifecycle = RunStore.RUNNING;
                m.attention = null;
                m.settle();
            });
            events.append(workspaceId, runId, "OPERATOR_RETRY", attentionNode(run), caller.subjectId(),
                    Map.of("reason", request.reason()));
            audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                    "run.retry-requested", "run", runId.toString(), AuditWriter.Outcome.SUCCESS, new LinkedHashMap<>()));
            idempotency.complete(claim, "run", runId.toString(), 200);
            return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), List.of()), false);
        }
        String jobId = engine.deadLetterJob(run.engineProcessInstanceId())
                .orElseThrow(() -> new ApiException(ProblemCode.RUN_NOT_RECOVERABLE, "Nothing is waiting to be retried."));
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, run.releaseId());
        runs.mutate(workspaceId, runId, m -> {
            m.lifecycle = RunStore.RUNNING;
            m.attention = null;
        });
        try {
            engine.moveDeadLetterToExecutable(jobId, Math.max(1, release.maxAttempts()));
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "The run is busy; try again shortly.");
        }
        events.append(workspaceId, runId, "OPERATOR_RETRY", attentionNode(run), caller.subjectId(),
                Map.of("reason", request.reason()));
        UUID effectId = request.effectId() != null ? request.effectId() : attentionEffect(run);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("effectId", effectId == null ? null : effectId.toString());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "run.retry-requested", "run", runId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        idempotency.complete(claim, "run", runId.toString(), 200);
        return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), List.of()), false);
    }

    // ---------------------------------------------------------------- reconcile (S5 section 7.2)

    @Transactional
    public Result<RuntimeDtos.Effect> reconcile(Caller caller, UUID workspaceId, UUID runId, UUID effectId,
            String idempotencyKey, RuntimeDtos.ReconcileEffectRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        EffectJournal.Effect effect = journal.find(workspaceId, effectId).filter(e -> e.runId().equals(runId))
                .orElseThrow(ApiException::notFound);
        policy.require(visible.access(), Permission.RUN_OPERATE, "reconcileEffect", true);
        String hash = idempotency.requestHash("reconcileEffect", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString(), "effectId", effectId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "reconcileEffect", key, hash);
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, visible.run().releaseId());
        if (claim.isReplay()) {
            return new Result<>(views.effect(journal.find(workspaceId, effectId).orElseThrow(),
                    RunService.label(release, effect.nodeId())), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        effect = journal.lock(workspaceId, effectId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run has ended.");
        }
        if (!EffectJournal.UNKNOWN.equals(effect.state()) && !EffectJournal.PERMANENT_FAILURE.equals(effect.state())) {
            throw new ApiException(ProblemCode.EFFECT_STATE_CONFLICT,
                    "Only unknown or failed external calls can be reconciled.");
        }
        Instant now = clock.instant();
        ObjectNode resolution = json.object();
        resolution.put("kind", request.resolution());
        resolution.put("by", caller.subjectId().toString());
        resolution.put("at", now.toString());
        resolution.put("note", request.evidence().note());
        if (request.evidence().remoteReference() != null) {
            resolution.put("remoteReference", request.evidence().remoteReference());
        }
        EffectJournal.Effect updated;
        if ("confirmedApplied".equals(request.resolution())) {
            ObjectNode result = validateResult(workspaceId, release, effect, request.result());
            updated = journal.reconcile(workspaceId, effectId, EffectJournal.SUCCEEDED, result,
                    request.evidence().remoteReference(), resolution);
            String jobId = engine.deadLetterJob(run.engineProcessInstanceId()).orElse(null);
            runs.mutate(workspaceId, runId, m -> {
                m.lifecycle = RunStore.RUNNING;
                m.attention = null;
            });
            if (jobId != null) {
                try {
                    engine.moveDeadLetterToExecutable(jobId, 1);
                } catch (EngineCommands.EngineConflictException e) {
                    throw new ApiException(ProblemCode.RUN_BUSY, "The run is busy; try again shortly.");
                }
            }
        } else {
            updated = journal.reconcile(workspaceId, effectId, EffectJournal.NOT_APPLIED, null,
                    request.evidence().remoteReference(), resolution);
            // Two deliberate steps: the run stays in needsAttention until an operator retries.
            runs.mutate(workspaceId, runId, m -> {
                if (m.attention instanceof ObjectNode attention) {
                    attention.put("message", "The external call was confirmed as not applied. An operator can retry.");
                }
            });
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resolution", request.resolution());
        data.put("remoteReference", request.evidence().remoteReference());
        events.append(workspaceId, runId, "EFFECT_RECONCILED", effect.nodeId(), caller.subjectId(), data);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("effectId", effectId.toString());
        metadata.put("resolution", request.resolution());
        metadata.put("remoteReference", request.evidence().remoteReference());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "effect.reconciled", "effect", effectId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        idempotency.complete(claim, "effect", effectId.toString(), 200);
        return new Result<>(views.effect(updated, RunService.label(release, effect.nodeId())), false);
    }

    /** confirmedApplied result: keys equal the node's resultMapping keys; values match the response types. */
    private ObjectNode validateResult(UUID workspaceId, ReleaseQueries.ReleaseView release, EffectJournal.Effect effect,
            Map<String, JsonNode> result) {
        JsonNode node = release.node(effect.nodeId());
        JsonNode mapping = node == null ? json.object() : node.path("config").path("resultMapping");
        List<String> expected = new ArrayList<>();
        mapping.propertyNames().forEach(expected::add);
        List<ApiException.FieldError> errors = new ArrayList<>();
        if (result == null || !result.keySet().equals(new java.util.HashSet<>(expected))) {
            errors.add(new ApiException.FieldError("/result", "required",
                    "Provide exactly these result fields: " + String.join(", ", expected)));
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The result does not match the step.")
                    .withFieldErrors(errors);
        }
        JsonNode responseSchema = connections.definition(workspaceId, effect.connectionKey(), effect.connectionVersion())
                .map(d -> d.definition().operation(effect.operation()).responseSchema()).orElse(json.object());
        ObjectNode values = json.object();
        for (String field : expected) {
            JsonNode value = result.get(field);
            String type = typeAt(responseSchema, mapping.path(field).asString());
            if (!matches(type, value)) {
                errors.add(new ApiException.FieldError("/result/" + field, "type", "Expected " + type));
            }
            values.set(field, value);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The result does not match the step.")
                    .withFieldErrors(errors);
        }
        return values;
    }

    private static String typeAt(JsonNode responseSchema, String pointer) {
        JsonNode schema = responseSchema;
        for (String segment : pointer.substring(1).split("/")) {
            schema = schema.path("properties").path(segment.replace("~1", "/").replace("~0", "~"));
        }
        return schema.path("type").asString("string");
    }

    private static boolean matches(String type, JsonNode value) {
        if (value == null) {
            return false;
        }
        return switch (type) {
            case "number" -> value.isNumber();
            case "integer" -> value.isNumber() && isIntegral(value.decimalValue());
            case "boolean" -> value.isBoolean();
            default -> value.isString();
        };
    }

    private static boolean isIntegral(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    // ---------------------------------------------------------------- cancel (S5 section 7.3)

    @Transactional
    public Result<RuntimeDtos.RunDetail> cancel(Caller caller, UUID workspaceId, UUID runId, String idempotencyKey,
            RuntimeDtos.CancelRunRequest request) {
        if (request.reason().isBlank()) throw new ApiException(ProblemCode.REASON_REQUIRED, "Supply a closure reason.");
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "cancelRun", true);
        String hash = idempotency.requestHash("cancelRun", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "cancelRun", key, hash);
        if (claim.isReplay()) {
            return new Result<>(detailWithEffects(caller, workspaceId, runId), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run has already ended.")
                    .with("currentVersion", run.version());
        }
        requireVersion(run, request.expectedVersion());
        JsonNode definition = releases.require(workspaceId, run.releaseId()).definition();
        String closure = definition.path("closurePolicy").asString(
                "tier1".equals(definition.path("acceptanceProfile").asString()) ? "REQUIRE_NO_OPEN_TASKS" : "CANCEL_OPEN_TASKS");
        var openTasks = tasks.forRun(workspaceId, runId).stream().filter(TaskStore.TaskRow::isOpen).map(TaskStore.TaskRow::taskId).toList();
        if ("REQUIRE_NO_OPEN_TASKS".equals(closure) && !openTasks.isEmpty())
            throw new ApiException(ProblemCode.RUN_HAS_OPEN_TASKS, "Resolve the open tasks before closing this case.")
                    .with("openTaskIds", openTasks).with("currentVersion", run.version());
        try {
            boolean orphan = run.attention() != null
                    && RunWatchdog.REASON.equals(run.attention().path("reason").asString());
            if (!orphan || engine.processExists(run.engineProcessInstanceId())) {
                engine.deleteProcessInstance(run.engineProcessInstanceId(), "Cancelled by operator", caller.subjectId());
            }
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "A step of this run is executing; try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        Instant now = clock.instant();
        suspensions.remove(workspaceId, runId);
        tasks.cancelOpenForRun(workspaceId, runId, now);
        runs.mutate(workspaceId, runId, m -> {
            m.lifecycle = RunStore.CANCELLED;
            m.attention = null;
            m.endedAt = now;
            m.steps.removeAll();
        });
        children.cancelActiveChildren(run, "The parent request was cancelled.");
        children.onRunEnded(runs.find(workspaceId, runId).orElseThrow());
        List<EffectJournal.Effect> succeeded = journal.forRun(workspaceId, runId).stream()
                .filter(e -> EffectJournal.SUCCEEDED.equals(e.state())).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", request.reason());
        data.put("completedEffects", succeeded.size());
        data.put("closurePolicy", closure);
        data.put("cancelledTaskIds", openTasks);
        events.append(workspaceId, runId, "RUN_CANCELLED", null, caller.subjectId(), data);
        if (notifications.create(workspaceId, run.initiator(), runId, NotificationService.Kind.WORKFLOW_MESSAGE,
                runId + ":cancelled:" + run.initiator(), "Your request was cancelled by an operator.")) {
            events.append(workspaceId, runId, "NOTIFICATION_CREATED", null, null,
                    Map.of("kind", NotificationService.Kind.WORKFLOW_MESSAGE.value(), "recipient", "initiator"));
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "run.cancelled",
                "run", runId.toString(), AuditWriter.Outcome.SUCCESS, Map.of("closurePolicy",closure,"cancelledTaskIds",openTasks)));
        idempotency.complete(claim, "run", runId.toString(), 200);
        return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), succeeded), false);
    }

    // ---------------------------------------------------------------- suspend and resume (docs/build/S37)

    @Transactional
    public Result<RuntimeDtos.RunDetail> suspend(Caller caller, UUID workspaceId, UUID runId, String idempotencyKey,
            RuntimeDtos.SuspendRunRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "suspendRun", true);
        String hash = idempotency.requestHash("suspendRun", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "suspendRun", key, hash);
        if (claim.isReplay()) {
            return new Result<>(detailWithEffects(caller, workspaceId, runId), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run has already ended.")
                    .with("currentVersion", run.version());
        }
        requireVersion(run, request.expectedVersion());
        if (suspensions.isSuspended(workspaceId, runId)) {
            throw new ApiException(ProblemCode.RUN_SUSPENDED, "The run is already suspended.")
                    .with("currentVersion", run.version());
        }
        String blocked = suspendBlocker(run, runs, engine);
        if (blocked != null) {
            throw new ApiException(ProblemCode.RUN_NOT_SUSPENDABLE, blocked).with("currentVersion", run.version());
        }
        try {
            engine.suspendProcessInstance(run.engineProcessInstanceId(), caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "A step of this run is executing; try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        suspensions.insert(workspaceId, runId, caller.subjectId(), request.reason());
        runs.mutate(workspaceId, runId, m -> { });
        events.append(workspaceId, runId, "RUN_SUSPENDED", null, caller.subjectId(), Map.of("reason", request.reason()));
        notifyInitiator(run, "suspended", "Your request was paused by an operator. It continues when it is resumed.");
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "run.suspended",
                "run", runId.toString(), AuditWriter.Outcome.SUCCESS, Map.of()));
        idempotency.complete(claim, "run", runId.toString(), 200);
        return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), List.of()), false);
    }

    /**
     * Sends a signal to one request (S55): only steps and handlers of this request that wait for it receive it; a
     * signal never reaches another request. Needs {@code run.operate}, an idempotency key, a reason and the current
     * version; refused with 409 SIGNAL_NOT_AWAITED when nothing in the request waits for it.
     */
    @Transactional
    public Result<RuntimeDtos.RunDetail> signal(Caller caller, UUID workspaceId, UUID runId, String idempotencyKey,
            RuntimeDtos.SendRunSignalRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "sendRunSignal", true);
        String hash = idempotency.requestHash("sendRunSignal", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "sendRunSignal", key, hash);
        if (claim.isReplay()) {
            return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), List.of()), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run has already ended.")
                    .with("currentVersion", run.version());
        }
        requireVersion(run, request.expectedVersion());
        suspensions.requireNotSuspended(workspaceId, runId);
        if (engine.signalSubscriptions(run.engineProcessInstanceId(), request.signal()).isEmpty()) {
            throw new ApiException(ProblemCode.SIGNAL_NOT_AWAITED, "No step or handler of this request is waiting for"
                    + " that signal now.").with("currentVersion", run.version());
        }
        runs.mutate(workspaceId, runId, m -> { });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("signal", request.signal());
        data.put("reason", request.reason());
        events.append(workspaceId, runId, "SIGNAL_SENT", null, caller.subjectId(), data);
        int delivered;
        try {
            delivered = engine.signal(run.engineProcessInstanceId(), request.signal(), caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "A step of this run is executing; try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "run.signal-sent",
                "run", runId.toString(), AuditWriter.Outcome.SUCCESS, Map.of("signal", request.signal(),
                        "delivered", delivered)));
        idempotency.complete(claim, "run", runId.toString(), 200);
        return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), List.of()), false);
    }

    @Transactional
    public Result<RuntimeDtos.RunDetail> resume(Caller caller, UUID workspaceId, UUID runId, String idempotencyKey,
            RuntimeDtos.ResumeRunRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "resumeRun", true);
        String hash = idempotency.requestHash("resumeRun", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "resumeRun", key, hash);
        if (claim.isReplay()) {
            return new Result<>(detailWithEffects(caller, workspaceId, runId), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run has already ended.")
                    .with("currentVersion", run.version());
        }
        requireVersion(run, request.expectedVersion());
        if (!suspensions.isSuspended(workspaceId, runId)) {
            throw new ApiException(ProblemCode.RUN_NOT_SUSPENDED, "The run is not suspended.")
                    .with("currentVersion", run.version());
        }
        try {
            if (engine.isSuspended(run.engineProcessInstanceId())) {
                engine.activateProcessInstance(run.engineProcessInstanceId(), caller.subjectId());
            }
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "The run is busy; try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        suspensions.remove(workspaceId, runId);
        runs.mutate(workspaceId, runId, m -> { });
        events.append(workspaceId, runId, "RUN_RESUMED", null, caller.subjectId(), Map.of("reason", request.reason()));
        notifyInitiator(run, "resumed", "Your request was resumed by an operator.");
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "run.resumed",
                "run", runId.toString(), AuditWriter.Outcome.SUCCESS, Map.of()));
        idempotency.complete(claim, "run", runId.toString(), 200);
        return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), List.of()), false);
    }

    // ---------------------------------------------------------------- consistency check and repair (docs/build/S46)

    @Transactional
    public RuntimeDtos.RunDiagnosis diagnosis(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "getRunDiagnosis", true);
        return repairs.diagnose(visible.run()).diagnosis();
    }

    /** Open findings of the periodic scan (docs/build/S67): run.operate in the workspace; read-only. */
    @Transactional(readOnly = true)
    public RuntimeDtos.ConsistencyFindings consistencyFindings(Caller caller, UUID workspaceId, Integer limit) {
        var access = policy.freshAccess(caller, workspaceId).filter(a -> a.visible()).orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, "listConsistencyFindings", true);
        int bounded = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        return consistency.list(workspaceId, scan != null && scan.enabled(), bounded);
    }

    /**
     * Rebuilds the open steps of a request (approvals, date and message waits, external-worker steps) from what the engine holds. The decision is re-made under the run
     * lock, so a request that progressed meanwhile is refused instead of overwritten; the engine is never touched.
     */
    @Transactional
    public Result<RuntimeDtos.RunDetail> repair(Caller caller, UUID workspaceId, UUID runId, String idempotencyKey,
            RuntimeDtos.RepairRunRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "repairRun", true);
        String hash = idempotency.requestHash("repairRun", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "repairRun", key, hash);
        if (claim.isReplay()) {
            return new Result<>(detailWithEffects(caller, workspaceId, runId), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run has already ended.")
                    .with("currentVersion", run.version());
        }
        requireVersion(run, request.expectedVersion());
        var verdict = repairs.diagnose(run);
        if (!verdict.diagnosis().repairable()) {
            throw new ApiException(ProblemCode.RUN_NOT_REPAIRABLE, verdict.diagnosis().message())
                    .with("currentVersion", run.version()).with("state", verdict.diagnosis().state());
        }
        runs.mutate(workspaceId, runId, m -> repairs.rebuild(m, verdict.rebuild()));
        consistency.forget(workspaceId, runId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", request.reason());
        data.put("before", verdict.diagnosis().projectedNodeIds());
        data.put("after", verdict.diagnosis().engineNodeIds());
        events.append(workspaceId, runId, "RUN_PROJECTION_REPAIRED", null, caller.subjectId(), data);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "run.projection-repaired", "run", runId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("restored", verdict.diagnosis().engineNodeIds().size(),
                        "removed", verdict.diagnosis().projectedNodeIds().size())));
        idempotency.complete(claim, "run", runId.toString(), 200);
        return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), List.of()), false);
    }

    // ---------------------------------------------------------------- release migration (docs/build/S38)

    @Transactional(readOnly = true)
    public RuntimeDtos.MigrationOptions migrationOptions(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "getRunMigrationOptions", true);
        return migrations.options(visible.run(), suspensions.isSuspended(workspaceId, runId));
    }

    @Transactional
    public Result<RuntimeDtos.RunDetail> migrate(Caller caller, UUID workspaceId, UUID runId, String idempotencyKey,
            RuntimeDtos.MigrateRunRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "migrateRun", true);
        String hash = idempotency.requestHash("migrateRun", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "migrateRun", key, hash);
        if (claim.isReplay()) {
            return new Result<>(detailWithEffects(caller, workspaceId, runId), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run has already ended.")
                    .with("currentVersion", run.version());
        }
        requireVersion(run, request.expectedVersion());
        if (!suspensions.isSuspended(workspaceId, runId)) {
            throw new ApiException(ProblemCode.RUN_NOT_SUSPENDED, "Suspend the request before moving it to another release.")
                    .with("currentVersion", run.version());
        }
        ReleaseQueries.ReleaseView source = releases.require(workspaceId, run.releaseId());
        ReleaseQueries.ReleaseView target = releases.find(workspaceId, request.targetReleaseId())
                .filter(r -> r.workflowId().equals(run.workflowId())).orElseThrow(ApiException::notFound);
        if (target.id().equals(source.id())) {
            throw new ApiException(ProblemCode.RUN_NOT_MIGRATABLE, "The request is already on that release.");
        }
        List<ReleaseCompatibility.Finding> findings = migrations.evaluate(run, source, target);
        if (!findings.isEmpty()) {
            throw new ApiException(ProblemCode.RUN_NOT_MIGRATABLE, findings.getFirst().message())
                    .with("findings", findings.stream()
                            .map(f -> new RuntimeDtos.MigrationFinding(f.code(), f.message(), f.nodeId())).toList());
        }
        try {
            List<String> engineProblems = engine.migrationProblems(run.engineProcessInstanceId(),
                    target.processDefinitionId());
            if (!engineProblems.isEmpty()) {
                throw new ApiException(ProblemCode.RUN_NOT_MIGRATABLE,
                        "The workflow engine cannot move this request: " + String.join("; ", engineProblems));
            }
            engine.migrateProcessInstance(run.engineProcessInstanceId(), target.processDefinitionId(), caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "The run is busy; try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        runs.setRelease(workspaceId, runId, target.id());
        runs.mutate(workspaceId, runId, m -> {
            for (var step : run.currentSteps()) {
                String nodeId = step.path("nodeId").asString("");
                String label = target.nodeLabel(nodeId);
                if (label != null) {
                    m.updateStep(nodeId, s -> s.put("label", label));
                    tasks.relabelOpenForNode(workspaceId, runId, nodeId, label);
                }
            }
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fromReleaseNumber", source.releaseNumber());
        data.put("toReleaseNumber", target.releaseNumber());
        data.put("reason", request.reason());
        events.append(workspaceId, runId, "RUN_MIGRATED", null, caller.subjectId(), data);
        notifyInitiator(run, "migrated", "Your request now follows version " + target.releaseNumber()
                + " of the workflow. Where it is waiting has not changed.");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fromRelease", source.releaseNumber());
        metadata.put("toRelease", target.releaseNumber());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "run.migrated",
                "run", runId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        idempotency.complete(claim, "run", runId.toString(), 200);
        return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), List.of()), false);
    }

    /** Why a run cannot be suspended now, or null. Shared with the allowed-actions view. */
    static String suspendBlocker(RunStore.RunRow run, RunStore runs, EngineCommands engine) {
        if (RunStore.NEEDS_ATTENTION.equals(run.lifecycle())) {
            return "A request that needs attention is recovered, not suspended.";
        }
        if (run.isChild()) {
            return "A child request follows its parent; suspend the parent request instead.";
        }
        if (runs.descendantsDeepestFirst(run.workspaceId(), run.id()).stream().anyMatch(RunStore.RunRow::isActive)) {
            return "A request with running child requests cannot be suspended.";
        }
        if (!engine.processExists(run.engineProcessInstanceId())) {
            return "The request has no live engine state to suspend.";
        }
        return null;
    }

    private void notifyInitiator(RunStore.RunRow run, String what, String message) {
        if (notifications.create(run.workspaceId(), run.initiator(), run.id(), NotificationService.Kind.WORKFLOW_MESSAGE,
                run.id() + ":" + what + ":" + clock.instant().toEpochMilli(), message)) {
            events.append(run.workspaceId(), run.id(), "NOTIFICATION_CREATED", null, null,
                    Map.of("kind", NotificationService.Kind.WORKFLOW_MESSAGE.value(), "recipient", "initiator"));
        }
    }

    private RuntimeDtos.RunDetail detailWithEffects(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        List<EffectJournal.Effect> succeeded = RunStore.CANCELLED.equals(visible.run().lifecycle())
                ? journal.forRun(workspaceId, runId).stream().filter(e -> EffectJournal.SUCCEEDED.equals(e.state())).toList()
                : List.of();
        return runService.detail(visible, succeeded);
    }

    private static void requireVersion(RunStore.RunRow run, int expected) {
        if (run.version() != expected) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run changed since you loaded it.")
                    .with("currentVersion", run.version());
        }
    }

    private static String attentionNode(RunStore.RunRow run) {
        return run.attention() == null ? null : run.attention().path("nodeId").asString(null);
    }

    private static UUID attentionEffect(RunStore.RunRow run) {
        String id = run.attention() == null ? null : run.attention().path("effectId").asString(null);
        return id == null ? null : UUID.fromString(id);
    }
}
