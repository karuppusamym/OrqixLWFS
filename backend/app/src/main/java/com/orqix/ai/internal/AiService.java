package com.orqix.ai.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.ai.AiProperties;
import com.orqix.ai.api.AiDtos;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.DraftAuthoring;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.identity.WorkspaceFeatures;
import com.orqix.shared.Ids;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.CorrelationId;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI assistance use cases (S10 sections 3-5): status, draft generation and explanation. Authorization uses the
 * current policy like the catalog endpoints; provider calls run outside any database transaction, and only the final
 * write (workflow + draft revision 1 through {@link DraftAuthoring}, {@code ai_generation} row, audit, idempotency
 * record) is transactional. Nothing is created when generation fails. Descriptions, prompts and model output are never
 * logged, audited or stored.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final String DRAFT_OPERATION = "generateAiDraft";
    private static final String EXPLAIN_OPERATION = "explainWorkflow";
    private static final String STATUS_OPERATION = "getAiStatus";
    private static final String GENERATION_OPERATION = "getAiGeneration";
    private static final String FORM_OPERATION = "suggestFormChanges";
    private static final String DRAFT_EDIT_OPERATION = "proposeDraftEdits";
    public static final String REASON_BUDGET = "The monthly AI budget is used up.";
    /** S127: a workspace administrator switched AI assistance off in Settings, Features. */
    public static final String REASON_SWITCHED_OFF = "AI assistance is switched off in this workspace.";

    /** An accepted generation: the job as it is now, and whether this response replays an earlier identical request. */
    public record Accepted(AiDtos.Job job, boolean replayed) {
    }

    /** What a background run needs; the description lives only here, in memory, never in the database. */
    private record Ctx(Caller caller, UUID workspaceId, UUID jobId, AiDtos.DraftRequest request, String correlationId) {
    }

    private final AiProperties properties;
    private final ModelPolicy models;
    private final Generator generator;
    private final DraftRules rules;
    private final WorkspaceFacts facts;
    private final AiLedger ledger;
    private final AiJobStore jobs;
    private final AiJobRunner runner;
    private final DraftAuthoring drafts;
    private final PolicyService policy;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Json json;
    private final WorkflowCompiler compiler;
    private final Clock clock;
    private final FormSuggestions formRules;
    private final java.util.concurrent.ConcurrentHashMap<UUID, Boolean> formsInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final DraftEdits editRules;
    private final java.util.concurrent.ConcurrentHashMap<UUID, Boolean> editsInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final WorkspaceFeatures features;

    public AiService(AiProperties properties, ModelPolicy models, Generator generator, WorkflowCompiler compiler,
            WorkspaceFacts facts, AiLedger ledger, AiJobStore jobs, AiJobRunner runner, DraftAuthoring drafts,
            PolicyService policy, IdempotencyService idempotency, AuditWriter audit,
            PlatformTransactionManager transactions, MeterRegistry meters, Json json, Clock clock,
            WorkspaceFeatures features) {
        this.features = features;
        this.properties = properties;
        this.models = models;
        this.generator = generator;
        this.rules = new DraftRules(json, compiler);
        this.facts = facts;
        this.ledger = ledger;
        this.jobs = jobs;
        this.runner = runner;
        this.drafts = drafts;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.meters = meters;
        this.json = json;
        this.compiler = compiler;
        this.clock = clock;
        this.formRules = new FormSuggestions(json, compiler);
        this.editRules = new DraftEdits(json, compiler);
    }

    // ---------------------------------------------------------------- status

    /** {@code getAiStatus}: workflow.read or workflow.edit (workspace administrators may read it for Settings). */
    public AiDtos.Status status(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.WORKFLOW_READ) && !access.has(Permission.WORKFLOW_EDIT)
                && !access.has(Permission.WORKSPACE_MANAGE_ACCESS)) {
            throw policy.deny(workspaceId, caller.subjectId(), Permission.WORKFLOW_READ.value(), STATUS_OPERATION,
                    false, ApiException.forbidden());
        }
        BigDecimal spent = ledger.monthToDate();
        boolean switchedOff = !features.enabled(workspaceId, WorkspaceFeatures.Feature.AI);
        String reason = switchedOff ? REASON_SWITCHED_OFF : models.disabledReason();
        List<AiDtos.Profile> profiles = properties.keyConfigured() && !switchedOff ? models.enabledProfiles().stream()
                .map(p -> new AiDtos.Profile(p.id(), p.label(), p.model(), p.variablePrice())).toList() : List.of();
        if (reason == null && spent.compareTo(properties.budget().monthlyUsd()) >= 0) {
            reason = REASON_BUDGET;
        }
        String defaultProfile = properties.keyConfigured() ? models.defaultProfileId() : null;
        return new AiDtos.Status(reason == null, reason, defaultProfile, profiles, properties.excludedModels(),
                new AiDtos.MaxPrice(properties.maxPrice().promptPerMillion(),
                        properties.maxPrice().completionPerMillion()),
                new AiDtos.Budget(properties.budget().monthlyUsd(), usd(spent)),
                properties.keyConfigured() ? models.trippedProfiles().stream()
                        .map(t -> new AiDtos.DisabledProfile(t.profileId(), t.label(), t.model(), t.reason(), t.until()))
                        .toList() : List.of());
    }

    // ---------------------------------------------------------------- drafts (async jobs)

    /**
     * {@code generateAiDraft}: workflow.edit, Idempotency-Key required. Validates and authorizes synchronously (400, 403,
     * 404, 422 AI_NOT_CONFIGURED / AI_MODEL_NOT_ALLOWED, 429 budget or rate limit), then persists a queued job and runs
     * it on the bounded executor; the answer is the job (202). An identical replay returns the job as it is now (200).
     */
    public Accepted generateDraft(Caller caller, UUID workspaceId, String idempotencyKey, JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, DRAFT_OPERATION, false);
        String key = IdempotencyService.requireKey(idempotencyKey);
        AiDtos.DraftRequest request = AiDtos.DraftRequest.parse(body);
        String hash = idempotency.requestHash(DRAFT_OPERATION, Map.of("workspaceId", workspaceId.toString()), request);
        String correlationId = CorrelationId.current();
        Ctx[] started = new Ctx[1];
        Accepted accepted = tx.execute(status -> {
            IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), DRAFT_OPERATION, key,
                    hash);
            if (claim.isReplay()) {
                AiJobStore.Job existing = jobs.find(workspaceId, UUID.fromString(claim.replay().resourceId()))
                        .orElseThrow(ApiException::notFound);
                return new Accepted(view(caller, existing), true);
            }
            ensureEnabled();
            ModelPolicy.ResolvedProfile profile = models.resolve(request.profile());
            budgetLeft();
            jobs.lockSubject(caller.subjectId());
            if (jobs.countActive(workspaceId, caller.subjectId()) >= properties.jobs().activePerUser()) {
                throw rateLimited("You already have " + properties.jobs().activePerUser()
                        + " drafts being generated. Wait for one to finish.");
            }
            if (!runner.hasCapacity()) {
                throw rateLimited("The AI service is busy generating other drafts. Try again in a moment.");
            }
            UUID jobId = Ids.newId();
            // Queue allowance: a job may wait for a thread for as long as one generation may run.
            Instant deadline = clock.instant().plus(properties.generationDeadline().multipliedBy(2))
                    .plus(properties.jobs().orphanMargin());
            jobs.create(jobId, workspaceId, caller.subjectId(), profile.id(), correlationId, deadline);
            idempotency.complete(claim, "aiGenerationJob", jobId.toString(), 202);
            started[0] = new Ctx(caller, workspaceId, jobId, request, correlationId);
            AiJobStore.Job queued = jobs.find(workspaceId, jobId).orElseThrow();
            return new Accepted(view(caller, queued), false);
        });
        if (started[0] != null) {
            Ctx ctx = started[0];
            try {
                runner.submit(() -> run(ctx));
            } catch (RejectedExecutionException e) {
                ApiException busy = rateLimited("The AI service is busy generating other drafts. Try again in a moment.");
                jobs.markFailed(ctx.jobId(), problemBody(busy, ctx.correlationId()));
                throw busy;
            }
        }
        return accepted;
    }

    /**
     * {@code getAiGeneration}: workflow.read or workflow.edit. Visibility: a person always sees their own jobs; jobs of
     * other people are visible only to holders of workflow.edit in the workspace (they can open every draft there
     * anyway). Anyone else gets 404. A job whose pod died is failed here once its deadline has passed.
     */
    public AiDtos.Job getGeneration(Caller caller, UUID workspaceId, UUID generationId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.WORKFLOW_READ) && !access.has(Permission.WORKFLOW_EDIT)) {
            throw policy.deny(workspaceId, caller.subjectId(), Permission.WORKFLOW_READ.value(), GENERATION_OPERATION,
                    false, ApiException.forbidden());
        }
        jobs.expireOrphans();
        AiJobStore.Job job = jobs.find(workspaceId, generationId).orElseThrow(ApiException::notFound);
        if (!job.subjectId().equals(caller.subjectId()) && !access.has(Permission.WORKFLOW_EDIT)) {
            throw ApiException.notFound();
        }
        return view(caller, job);
    }

    /** Background run of one accepted job. Never throws: every outcome ends in the job row. */
    private void run(Ctx ctx) {
        MDC.put(CorrelationId.MDC_KEY, ctx.correlationId());
        MDC.put("workspaceId", ctx.workspaceId().toString());
        MDC.put("subjectId", ctx.caller().subjectId().toString());
        try {
            Instant deadline = clock.instant().plus(properties.generationDeadline())
                    .plus(properties.jobs().orphanMargin());
            if (!jobs.markRunning(ctx.jobId(), deadline)) {
                return; // already ended (reaped while it waited)
            }
            try {
                ensureEnabled();
                ModelPolicy.ResolvedProfile profile = models.resolve(ctx.request().profile());
                BigDecimal budgetLeft = budgetLeft();
                WorkspaceAccess access = policy.requireVisible(ctx.caller(), ctx.workspaceId());
                policy.require(access, Permission.WORKFLOW_EDIT, DRAFT_OPERATION, false);
                DraftRules.Workspace workspace = facts.load(access.workspace());
                Generator.DraftOutcome outcome = generator.draft(profile, ctx.request().description(),
                        ctx.request().name(), workspace, rules, budgetLeft);
                finish(ctx, outcome);
            } catch (ApiException e) {
                failJob(ctx, e);
            } catch (RuntimeException e) {
                log.error("AI generation job {} failed unexpectedly: {}", ctx.jobId(), e.getClass().getSimpleName(), e);
                failJob(ctx, new ApiException(ProblemCode.AI_PROVIDER_UNAVAILABLE,
                        "The generation failed unexpectedly. Nothing was created; try again."));
            }
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
            MDC.remove("workspaceId");
            MDC.remove("subjectId");
        }
    }

    private void finish(Ctx ctx, Generator.DraftOutcome outcome) {
        Caller caller = ctx.caller();
        UUID workspaceId = ctx.workspaceId();
        switch (outcome.status()) {
            case VALID -> persist(ctx, outcome);
            case NOT_A_WORKFLOW -> {
                recordFailure(caller, workspaceId, outcome.meter(), AiLedger.Outcome.NOT_A_WORKFLOW);
                String reason = outcome.evaluation().notAWorkflow();
                failJob(ctx, new ApiException(ProblemCode.AI_OUTPUT_INVALID,
                        "The description does not describe a business workflow" + (reason == null ? "." : ": " + reason))
                        .with("issues", List.of()));
            }
            case INVALID -> {
                recordFailure(caller, workspaceId, outcome.meter(), AiLedger.Outcome.INVALID_OUTPUT);
                failJob(ctx, new ApiException(ProblemCode.AI_OUTPUT_INVALID, outcome.deadlineStop()
                        ? "The time limit for one generation was reached before a valid draft was produced. "
                                + "Nothing was created. Try a shorter or more specific description."
                        : outcome.budgetStop()
                        ? "The AI budget for one request was reached before a valid draft was produced. "
                                + "Nothing was created."
                        : "The AI could not produce a valid workflow from this description. Nothing was created. "
                                + "Try a more specific description.")
                        .with("issues", issues(outcome.issues())));
            }
            case PROVIDER_FAILURE -> {
                ProviderFailure failure = classify(outcome.failure());
                recordFailure(caller, workspaceId, outcome.meter(), failure.outcome());
                failJob(ctx, failure.problem());
            }
        }
    }

    private void persist(Ctx ctx, Generator.DraftOutcome outcome) {
        Caller caller = ctx.caller();
        UUID workspaceId = ctx.workspaceId();
        DraftRules.Evaluation evaluation = outcome.evaluation();
        Generator.Meter meter = outcome.meter();
        ObjectNode definition = evaluation.definition();
        String description = definition.path("metadata").path("description").asString(null);
        if (description != null && description.length() > 2000) {
            description = description.substring(0, 2000);
        }
        String workflowDescription = description;
        List<AiDtos.MissingItem> missing = evaluation.missing().stream()
                .map(m -> new AiDtos.MissingItem(m.message(), m.nodeId(), m.code())).toList();
        try {
            tx.executeWithoutResult(status -> {
                AiJobStore.Job locked = jobs.lock(ctx.jobId()).orElse(null);
                if (locked == null || !"running".equals(locked.status())) {
                    // The job was failed while it ran (its deadline passed): the provider call is spent, nothing is created.
                    ledger.record(entry(caller, workspaceId, "draft", meter, PromptLibrary.DRAFT_VERSION,
                            AiLedger.Outcome.INVALID_OUTPUT, null, null, null));
                    return;
                }
                DraftAuthoring.Created created = drafts.createFromDefinition(caller, workspaceId,
                        new DraftAuthoring.NewDraft(definition.path("workflowKey").asString(),
                                definition.path("name").asString(), workflowDescription, definition,
                                DraftAuthoring.Source.AI, true));
                ledger.record(entry(caller, workspaceId, "draft", meter, PromptLibrary.DRAFT_VERSION,
                        AiLedger.Outcome.SUCCESS, created.workflowId(), null, null));
                audit.write(auditEntry(caller, workspaceId, "ai.draft.generated", created.workflowId(), meter,
                        PromptLibrary.DRAFT_VERSION, AiLedger.Outcome.SUCCESS, null, null));
                ObjectNode review = json.object();
                review.set("assumptions", json.toTree(evaluation.assumptions()));
                review.set("missingConfiguration", json.toTree(missing));
                if (!jobs.markSucceeded(ctx.jobId(), created.workflowId(), review)) {
                    throw new IllegalStateException("Generation job ended while it was being completed");
                }
            });
        } catch (RuntimeException e) {
            recordFailure(caller, workspaceId, meter, AiLedger.Outcome.INVALID_OUTPUT);
            throw e;
        }
        observe("draft", meter, AiLedger.Outcome.SUCCESS);
    }

    private void failJob(Ctx ctx, ApiException problem) {
        try {
            jobs.markFailed(ctx.jobId(), problemBody(problem, ctx.correlationId()));
        } catch (RuntimeException e) {
            log.warn("Could not record the failure of AI generation job {}: {}", ctx.jobId(), e.getClass().getSimpleName());
        }
    }

    private ObjectNode problemBody(ApiException e, String correlationId) {
        ObjectNode problem = json.object();
        problem.put("type", e.code().type());
        problem.put("title", e.code().title());
        problem.put("status", e.code().status());
        if (e.detail() != null) {
            problem.put("detail", e.detail());
        }
        problem.put("code", e.code().name());
        problem.put("correlationId", correlationId);
        e.extensions().forEach((name, value) -> problem.set(name, json.toTree(value)));
        return problem;
    }

    private ApiException rateLimited(String detail) {
        return new ApiException(ProblemCode.RATE_LIMITED, detail).withHeader("Retry-After", "15");
    }

    /** The job as the API shows it. A succeeded job carries the draft as created (revision 1) with its review notes. */
    private AiDtos.Job view(Caller caller, AiJobStore.Job job) {
        AiDtos.DraftResult result = null;
        if ("succeeded".equals(job.status())) {
            DraftAuthoring.Created view = drafts.view(caller, job.workspaceId(), job.workflowId(), 1);
            List<String> assumptions = new ArrayList<>();
            job.review().path("assumptions").forEach(a -> assumptions.add(a.asString("")));
            List<AiDtos.MissingItem> missing = new ArrayList<>();
            job.review().path("missingConfiguration").forEach(m -> missing.add(new AiDtos.MissingItem(
                    m.path("message").asString(""), m.path("nodeId").asString(null), m.path("code").asString(null))));
            AiDtos.Generation generation = ledger.draftGeneration(job.workspaceId(), job.workflowId())
                    .orElseGet(() -> new AiDtos.Generation(job.profile(), "unknown", PromptLibrary.DRAFT_VERSION, 0,
                            false, null, null, null, 0));
            result = new AiDtos.DraftResult(view.workflow(), view.draft(), view.report(), assumptions, missing,
                    generation);
        }
        return new AiDtos.Job(job.id(), job.status(), job.startedAt(), job.finishedAt(), job.profile(), result,
                "failed".equals(job.status()) ? job.problem() : null);
    }

    // ---------------------------------------------------------------- explanation

    /** {@code explainWorkflow}: workflow.read; a draft revision or the active release. Not stored. */
    public AiDtos.Explanation explain(Caller caller, UUID workspaceId, UUID workflowId, JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_READ, EXPLAIN_OPERATION, false);
        AiDtos.ExplainRequest request = AiDtos.ExplainRequest.parse(body);
        DraftAuthoring.Definition definition = drafts.definition(caller, workspaceId, workflowId, request.revision());
        ensureEnabled();
        ModelPolicy.ResolvedProfile profile = models.resolve(request.profile());
        budgetLeft();
        Generator.Meter meter = generator.meter(profile);
        var report = compiler.validate(json.write(definition.definition()), facts.load(access.workspace()).compileContext());
        JsonNode validation = json.toTree(Map.of("publishable", report.publishable(), "issues", issues(report.issues())));
        Generator.Explanation explanation;
        try {
            explanation = generator.explain(profile, definition.definition(), validation, meter);
        } catch (ProviderException e) {
            ProviderFailure failure = classify(e);
            recordExplain(caller, workspaceId, definition, meter, failure.outcome());
            throw failure.problem();
        }
        if (explanation == null) {
            recordExplain(caller, workspaceId, definition, meter, AiLedger.Outcome.INVALID_OUTPUT);
            throw new ApiException(ProblemCode.AI_OUTPUT_INVALID,
                    "The AI could not explain this workflow. Try again.");
        }
        recordExplain(caller, workspaceId, definition, meter, AiLedger.Outcome.SUCCESS);
        return new AiDtos.Explanation(explanation.summary(),
                explanation.steps().stream().map(s -> new AiDtos.Step(s.nodeId(), s.text())).toList(),
                generation(meter, PromptLibrary.EXPLAIN_VERSION), validation);
    }

    private void recordExplain(Caller caller, UUID workspaceId, DraftAuthoring.Definition definition,
            Generator.Meter meter, AiLedger.Outcome outcome) {
        try {
            tx.executeWithoutResult(status -> {
                ledger.record(entry(caller, workspaceId, "explain", meter, PromptLibrary.EXPLAIN_VERSION, outcome,
                        definition.workflowId(), definition.revision(), definition.releaseNumber()));
                audit.write(auditEntry(caller, workspaceId, "ai.workflow.explained", definition.workflowId(), meter,
                        PromptLibrary.EXPLAIN_VERSION, outcome, definition.revision(), definition.releaseNumber()));
            });
        } catch (RuntimeException e) {
            if (outcome == AiLedger.Outcome.SUCCESS) {
                throw e;
            }
            log.warn("Could not record a failed AI explanation: {}", e.getClass().getSimpleName());
        }
        observe("explain", meter, outcome);
    }

    // ---------------------------------------------------------------- form suggestions (S90, G4)

    /**
     * {@code suggestFormChanges}: workflow.edit on a visible workflow. Synchronous like an explanation (a form is small)
     * and never stored: the suggestion is model output, which S10 never persists, so it is returned once for review.
     * Every change is validated by {@link FormSuggestions}; nothing is applied to the draft. One suggestion at a time per
     * person on a pod (429 RATE_LIMITED). Metering and audit hold counts only, never the description, form or fields.
     */
    public AiDtos.FormSuggestion suggestForm(Caller caller, UUID workspaceId, UUID workflowId, JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, FORM_OPERATION, false);
        drafts.requireWorkflow(caller, workspaceId, workflowId);
        AiDtos.FormSuggestRequest request = AiDtos.FormSuggestRequest.parse(body, json::write);
        ensureEnabled();
        ModelPolicy.ResolvedProfile profile = models.resolve(request.profile());
        budgetLeft();
        if (formsInFlight.putIfAbsent(caller.subjectId(), Boolean.TRUE) != null) {
            throw rateLimited("A form suggestion is already being prepared for you. Wait for it to finish.");
        }
        try {
            Generator.Meter meter = generator.meter(profile);
            JsonNode current = formRules.normalized(request.inputSchema());
            JsonNode reply;
            try {
                reply = generator.suggestForm(profile, json.write(current), request.description(), meter);
            } catch (ProviderException e) {
                ProviderFailure failure = classify(e);
                recordForm(caller, workspaceId, workflowId, meter, failure.outcome(), 0, 0);
                throw failure.problem();
            }
            if (reply == null) {
                recordForm(caller, workspaceId, workflowId, meter, AiLedger.Outcome.INVALID_OUTPUT, 0, 0);
                throw new ApiException(ProblemCode.AI_OUTPUT_INVALID,
                        "The AI could not suggest form changes. Nothing was changed; try again.");
            }
            FormSuggestions.Result result = formRules.evaluate(reply, current);
            recordForm(caller, workspaceId, workflowId, meter, AiLedger.Outcome.SUCCESS, result.changes().size(),
                    result.rejected().size());
            return new AiDtos.FormSuggestion(
                    result.changes().stream().map(c -> new AiDtos.FormChange(c.op(), c.key(), c.field(), c.required(),
                            c.reason(), c.before(), c.wasRequired())).toList(),
                    result.rejected().stream().map(r -> new AiDtos.FormRejected(r.op(), r.key(), r.reasons())).toList(),
                    result.notes(), result.notAForm(), generation(meter, PromptLibrary.FORM_VERSION));
        } finally {
            formsInFlight.remove(caller.subjectId());
        }
    }

    private void recordForm(Caller caller, UUID workspaceId, UUID workflowId, Generator.Meter meter,
            AiLedger.Outcome outcome, int accepted, int rejected) {
        try {
            tx.executeWithoutResult(status -> {
                ledger.record(entry(caller, workspaceId, "form", meter, PromptLibrary.FORM_VERSION, outcome, workflowId,
                        null, null));
                AuditWriter.Entry base = auditEntry(caller, workspaceId, "ai.form.suggested", workflowId, meter,
                        PromptLibrary.FORM_VERSION, outcome, null, null);
                Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
                metadata.put("changesSuggested", accepted);
                metadata.put("changesRejected", rejected);
                audit.write(new AuditWriter.Entry(base.workspaceId(), base.actorType(), base.actorSubject(),
                        base.action(), base.resourceType(), base.resourceId(), base.outcome(), metadata));
            });
        } catch (RuntimeException e) {
            if (outcome == AiLedger.Outcome.SUCCESS) {
                throw e;
            }
            log.warn("Could not record a failed AI form suggestion: {}", e.getClass().getSimpleName());
        }
        observe("form", meter, outcome);
    }

    // ---------------------------------------------------------------- conversational draft edits (STU02)

    /**
     * {@code proposeDraftEdits}: workflow.edit on a visible workflow. Synchronous like a form suggestion and never
     * stored. The model sees a bounded, redacted summary of the latest draft revision (which must be the revision the
     * author is looking at: otherwise 409), the last turns and the instruction; it answers with structured edit
     * operations that {@link DraftEdits} applies to a candidate copy and validates with the real compiler. Nothing is
     * applied to the draft. One proposal at a time per person on a pod (429). Metering and audit hold counts only.
     */
    public AiDtos.DraftEditProposal proposeDraftEdits(Caller caller, UUID workspaceId, UUID workflowId,
            JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, DRAFT_EDIT_OPERATION, false);
        DraftAuthoring.Definition latest = drafts.latestDraft(caller, workspaceId, workflowId);
        AiDtos.DraftEditRequest request = AiDtos.DraftEditRequest.parse(body);
        ensureEnabled();
        ModelPolicy.ResolvedProfile profile = models.resolve(request.profile());
        budgetLeft();
        if (latest.revision() != request.revision()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The draft was saved again (revision "
                    + latest.revision() + ") since revision " + request.revision()
                    + ". Reload the studio, then ask again. Nothing was sent to the AI.")
                    .with("currentRevision", latest.revision());
        }
        if (editsInFlight.putIfAbsent(caller.subjectId(), Boolean.TRUE) != null) {
            throw rateLimited("A proposal is already being prepared for you. Wait for it to finish.");
        }
        DraftEdits.Mode mode = "regenerate".equals(request.mode()) ? DraftEdits.Mode.REGENERATE : DraftEdits.Mode.EDIT;
        try {
            DraftRules.Workspace workspace = facts.load(access.workspace());
            Generator.Meter meter = generator.meter(profile);
            List<PromptLibrary.Turn> history = request.history().stream()
                    .map(t -> new PromptLibrary.Turn(t.role(), t.text())).toList();
            String user = PromptLibrary.draftEditUser(editRules.summary(latest.definition(), workspace.groups()),
                    history, request.instruction(), mode.value());
            JsonNode reply;
            try {
                reply = generator.editDraft(profile, user, meter);
            } catch (ProviderException e) {
                ProviderFailure failure = classify(e);
                recordEdits(caller, workspaceId, workflowId, meter, failure.outcome(), latest.revision(), mode,
                        history.size(), null);
                throw failure.problem();
            }
            if (reply == null) {
                recordEdits(caller, workspaceId, workflowId, meter, AiLedger.Outcome.INVALID_OUTPUT,
                        latest.revision(), mode, history.size(), null);
                throw new ApiException(ProblemCode.AI_OUTPUT_INVALID,
                        "The AI could not propose changes. Nothing was changed; try again or rephrase.");
            }
            DraftEdits.Result result = editRules.evaluate(reply, latest.definition(), mode,
                    workspace.compileContext());
            recordEdits(caller, workspaceId, workflowId, meter, AiLedger.Outcome.SUCCESS, latest.revision(), mode,
                    history.size(), result);
            return new AiDtos.DraftEditProposal(latest.revision(), mode.value(), result.message(),
                    result.changes().stream().map(c -> new AiDtos.DraftEditChange(c.id(), c.summary(),
                            c.operations().stream().map(o -> (JsonNode) o).toList(), c.dependsOn())).toList(),
                    result.rejected().stream().map(r -> new AiDtos.DraftEditRejected(r.summary(), r.reasons()))
                            .toList(),
                    result.notes(), result.notAnEdit(), result.definition(),
                    generation(meter, PromptLibrary.DRAFT_EDIT_VERSION));
        } finally {
            editsInFlight.remove(caller.subjectId());
        }
    }

    private void recordEdits(Caller caller, UUID workspaceId, UUID workflowId, Generator.Meter meter,
            AiLedger.Outcome outcome, int revision, DraftEdits.Mode mode, int turns, DraftEdits.Result result) {
        try {
            tx.executeWithoutResult(status -> {
                ledger.record(entry(caller, workspaceId, "draft-edit", meter, PromptLibrary.DRAFT_EDIT_VERSION, outcome,
                        workflowId, revision, null));
                AuditWriter.Entry base = auditEntry(caller, workspaceId, "ai.draft.edits.proposed", workflowId, meter,
                        PromptLibrary.DRAFT_EDIT_VERSION, outcome, revision, null);
                Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
                metadata.put("mode", mode.value());
                metadata.put("turns", turns);
                metadata.put("changesSuggested", result == null ? 0 : result.changes().size());
                metadata.put("changesRejected", result == null ? 0 : result.rejected().size());
                metadata.put("operationsSuggested", result == null ? 0 : result.operationCount());
                audit.write(new AuditWriter.Entry(base.workspaceId(), base.actorType(), base.actorSubject(),
                        base.action(), base.resourceType(), base.resourceId(), base.outcome(), metadata));
            });
        } catch (RuntimeException e) {
            if (outcome == AiLedger.Outcome.SUCCESS) {
                throw e;
            }
            log.warn("Could not record a failed AI draft-edit proposal: {}", e.getClass().getSimpleName());
        }
        observe("draft-edit", meter, outcome);
    }

    // ---------------------------------------------------------------- policy helpers

    private void ensureEnabled() {
        String reason = models.disabledReason();
        if (reason != null) {
            throw new ApiException(ProblemCode.AI_NOT_CONFIGURED, reason);
        }
    }

    /** Remaining monthly budget; 429 AI_BUDGET_EXCEEDED (Retry-After = start of next month) when none is left. */
    private BigDecimal budgetLeft() {
        BigDecimal left = properties.budget().monthlyUsd().subtract(ledger.monthToDate());
        if (left.signum() <= 0) {
            throw new ApiException(ProblemCode.AI_BUDGET_EXCEEDED, "The monthly AI budget of USD "
                    + properties.budget().monthlyUsd().toPlainString() + " is used up. AI assistance is available "
                    + "again next month; templates and the studio keep working.")
                    .withHeader("Retry-After", Long.toString(ledger.secondsUntilNextMonth()));
        }
        return left;
    }

    private record ProviderFailure(ApiException problem, AiLedger.Outcome outcome) {
    }

    private ProviderFailure classify(ProviderException e) {
        return switch (e.kind()) {
            case CREDENTIALS_REJECTED -> {
                models.credentialsRejected();
                yield new ProviderFailure(new ApiException(ProblemCode.AI_NOT_CONFIGURED,
                        ModelPolicy.REASON_CREDENTIALS), AiLedger.Outcome.CREDENTIALS_REJECTED);
            }
            case PAYMENT_REQUIRED -> new ProviderFailure(new ApiException(ProblemCode.AI_PROVIDER_UNAVAILABLE,
                    "The AI provider account has no credit left. Nothing was created."),
                    AiLedger.Outcome.PROVIDER_ERROR);
            case REJECTED -> e.status() == 404
                    ? new ProviderFailure(new ApiException(ProblemCode.AI_MODEL_NOT_ALLOWED,
                            "No provider offers this model within the configured price caps."),
                            AiLedger.Outcome.MODEL_NOT_ALLOWED)
                    : new ProviderFailure(new ApiException(ProblemCode.AI_PROVIDER_UNAVAILABLE,
                            "The AI provider refused the request. Nothing was created."),
                            AiLedger.Outcome.PROVIDER_ERROR);
            case UNAVAILABLE -> {
                ApiException problem = new ApiException(ProblemCode.AI_PROVIDER_UNAVAILABLE, e.status() == 0
                        ? "The AI provider did not answer in time. Nothing was created; try again."
                        : "The AI provider is unavailable. Nothing was created; try again shortly.");
                Integer retryAfter = e.retryAfterSeconds() != null ? e.retryAfterSeconds()
                        : e.status() == 429 ? Integer.valueOf(30) : null;
                if (retryAfter != null) {
                    problem.withHeader("Retry-After", retryAfter.toString());
                }
                yield new ProviderFailure(problem, AiLedger.Outcome.PROVIDER_ERROR);
            }
        };
    }

    // ---------------------------------------------------------------- metering

    private void recordFailure(Caller caller, UUID workspaceId, Generator.Meter meter, AiLedger.Outcome outcome) {
        try {
            tx.executeWithoutResult(status -> {
                ledger.record(entry(caller, workspaceId, "draft", meter, PromptLibrary.DRAFT_VERSION, outcome, null,
                        null, null));
                audit.write(auditEntry(caller, workspaceId, "ai.draft.generated", null, meter,
                        PromptLibrary.DRAFT_VERSION, outcome, null, null));
            });
        } catch (RuntimeException e) {
            log.warn("Could not record a failed AI generation: {}", e.getClass().getSimpleName());
        }
        observe("draft", meter, outcome);
    }

    private AiLedger.Entry entry(Caller caller, UUID workspaceId, String kind, Generator.Meter meter,
            String promptVersion, AiLedger.Outcome outcome, UUID workflowId, Integer revision, Integer releaseNumber) {
        return new AiLedger.Entry(workspaceId, caller.subjectId(), kind, meter.profile(), meter.model(),
                meter.requestedModel(), promptVersion, meter.attempts(), meter.escalated(), meter.inputTokens(),
                meter.outputTokens(), meter.cost(), meter.durationMs(), outcome, workflowId, revision, releaseNumber,
                meter.violation());
    }

    private static AuditWriter.Entry auditEntry(Caller caller, UUID workspaceId, String action, UUID workflowId,
            Generator.Meter meter, String promptVersion, AiLedger.Outcome outcome, Integer revision,
            Integer releaseNumber) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("profile", meter.profile());
        metadata.put("model", meter.model());
        metadata.put("promptVersion", promptVersion);
        metadata.put("attempts", meter.attempts());
        metadata.put("escalated", meter.escalated());
        metadata.put("inputTokens", meter.inputTokens());
        metadata.put("outputTokens", meter.outputTokens());
        metadata.put("costUsd", usd(meter.cost()));
        metadata.put("durationMs", meter.durationMs());
        metadata.put("outcome", outcome.value());
        metadata.put("workflowId", workflowId == null ? null : workflowId.toString());
        metadata.put("revision", revision);
        metadata.put("releaseNumber", releaseNumber);
        return new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), action, "workflow",
                workflowId == null ? null : workflowId.toString(),
                outcome == AiLedger.Outcome.SUCCESS ? AuditWriter.Outcome.SUCCESS : AuditWriter.Outcome.FAILED,
                metadata);
    }

    private static AiDtos.Generation generation(Generator.Meter meter, String promptVersion) {
        return new AiDtos.Generation(meter.profile(), meter.model(), promptVersion, meter.attempts(), meter.escalated(),
                meter.inputTokens(), meter.outputTokens(), usd(meter.cost()), meter.durationMs(), meter.violation());
    }

    private void observe(String kind, Generator.Meter meter, AiLedger.Outcome outcome) {
        Counter.builder("orqix_ai_generations_total").tag("kind", kind).tag("profile", meter.profile())
                .tag("outcome", outcome.value()).register(meters).increment();
        if (meter.cost() != null) {
            Counter.builder("orqix_ai_cost_usd_total").tag("kind", kind).tag("profile", meter.profile())
                    .register(meters).increment(meter.cost().doubleValue());
        }
        log.info("AI {} generation: profile={} model={} attempts={} escalated={} outcome={} costUsd={} durationMs={}",
                kind, meter.profile(), meter.model(), meter.attempts(), meter.escalated(), outcome.value(),
                usd(meter.cost()), meter.durationMs());
    }

    /** USD with at most 8 decimals, plain; null stays null (never shown as zero). */
    static BigDecimal usd(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal scaled = value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        return scaled.scale() < 0 ? scaled.setScale(0) : scaled;
    }

    private static List<Map<String, Object>> issues(List<ValidationIssue> issues) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ValidationIssue issue : issues) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", issue.code());
            item.put("severity", issue.severity().apiValue());
            item.put("message", issue.message());
            item.put("path", issue.path() == null ? "" : issue.path());
            item.put("nodeId", issue.nodeId());
            item.put("transitionId", issue.transitionId());
            result.add(item);
            if (result.size() >= 50) {
                break;
            }
        }
        return result;
    }
}
