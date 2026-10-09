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

    public OperatorService(PolicyService policy, RunAccess runAccess, RunStore runs, TaskStore tasks, RunEvents events,
            RunService runService, RuntimeViews views, ReleaseQueries releases, ConnectionService connections,
            EffectJournal journal, EngineCommands engine, NotificationService notifications,
            IdempotencyService idempotency, AuditWriter audit, Json json, Clock clock, ChildRuns children) {
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
        try {
            engine.deleteProcessInstance(run.engineProcessInstanceId(), "Cancelled by operator", caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "A step of this run is executing; try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        Instant now = clock.instant();
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
        events.append(workspaceId, runId, "RUN_CANCELLED", null, caller.subjectId(), data);
        if (notifications.create(workspaceId, run.initiator(), runId, NotificationService.Kind.WORKFLOW_MESSAGE,
                runId + ":cancelled:" + run.initiator(), "Your request was cancelled by an operator.")) {
            events.append(workspaceId, runId, "NOTIFICATION_CREATED", null, null,
                    Map.of("kind", NotificationService.Kind.WORKFLOW_MESSAGE.value(), "recipient", "initiator"));
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "run.cancelled",
                "run", runId.toString(), AuditWriter.Outcome.SUCCESS, Map.of()));
        idempotency.complete(claim, "run", runId.toString(), 200);
        return new Result<>(runService.detail(runAccess.require(caller, workspaceId, runId), succeeded), false);
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
