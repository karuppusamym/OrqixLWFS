package com.orqix.ai.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.ai.AiProperties;
import com.orqix.ai.api.AiDtos;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.DraftAuthoring;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

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
    private static final Duration REVIEW_NOTES_TTL = Duration.ofHours(1);
    private static final int REVIEW_NOTES_MAX = 1000;
    public static final String REASON_BUDGET = "The monthly AI budget is used up.";

    /** A generated (or replayed) draft. */
    public record Drafted(AiDtos.DraftResult result, boolean replayed) {
    }

    private record Gate(ReentrantLock lock, AtomicInteger users) {
    }

    /** Review notes kept in memory for idempotent replays (they are model output, so they are never persisted). */
    private record Notes(List<String> assumptions, List<AiDtos.MissingItem> missing, Instant expiresAt) {
    }

    private final AiProperties properties;
    private final ModelPolicy models;
    private final Generator generator;
    private final DraftRules rules;
    private final WorkspaceFacts facts;
    private final AiLedger ledger;
    private final DraftAuthoring drafts;
    private final PolicyService policy;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Json json;
    private final Clock clock;
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    private final Map<String, Notes> notes = new ConcurrentHashMap<>();

    public AiService(AiProperties properties, ModelPolicy models, Generator generator, WorkflowCompiler compiler,
            WorkspaceFacts facts, AiLedger ledger, DraftAuthoring drafts, PolicyService policy,
            IdempotencyService idempotency, AuditWriter audit, PlatformTransactionManager transactions,
            MeterRegistry meters, Json json, Clock clock) {
        this.properties = properties;
        this.models = models;
        this.generator = generator;
        this.rules = new DraftRules(json, compiler);
        this.facts = facts;
        this.ledger = ledger;
        this.drafts = drafts;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.meters = meters;
        this.json = json;
        this.clock = clock;
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
        String reason = models.disabledReason();
        List<AiDtos.Profile> profiles = properties.keyConfigured() ? models.enabledProfiles().stream()
                .map(p -> new AiDtos.Profile(p.id(), p.label(), p.model(), p.variablePrice())).toList() : List.of();
        if (reason == null && spent.compareTo(properties.budget().monthlyUsd()) >= 0) {
            reason = REASON_BUDGET;
        }
        String defaultProfile = properties.keyConfigured() ? models.defaultProfileId() : null;
        return new AiDtos.Status(reason == null, reason, defaultProfile, profiles, properties.excludedModels(),
                new AiDtos.MaxPrice(properties.maxPrice().promptPerMillion(),
                        properties.maxPrice().completionPerMillion()),
                new AiDtos.Budget(properties.budget().monthlyUsd(), usd(spent)));
    }

    // ---------------------------------------------------------------- drafts

    /** {@code generateAiDraft}: workflow.edit, Idempotency-Key required. */
    public Drafted generateDraft(Caller caller, UUID workspaceId, String idempotencyKey, JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, DRAFT_OPERATION, false);
        String key = IdempotencyService.requireKey(idempotencyKey);
        AiDtos.DraftRequest request = AiDtos.DraftRequest.parse(body);
        String hash = idempotency.requestHash(DRAFT_OPERATION, Map.of("workspaceId", workspaceId.toString()), request);
        String gateKey = workspaceId + "|" + caller.subjectId() + "|" + key;
        Gate gate = enter(gateKey);
        try {
            // A completed identical command is replayed before anything else (no provider call, no spend).
            AiDtos.DraftResult replay = tx.execute(status -> {
                IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), DRAFT_OPERATION,
                        key, hash);
                if (claim.isReplay()) {
                    return replayed(caller, workspaceId, UUID.fromString(claim.replay().resourceId()), gateKey);
                }
                status.setRollbackOnly();
                return null;
            });
            if (replay != null) {
                return new Drafted(replay, true);
            }
            ensureEnabled();
            ModelPolicy.ResolvedProfile profile = models.resolve(request.profile());
            BigDecimal budgetLeft = budgetLeft();
            DraftRules.Workspace workspace = facts.load(access.workspace());
            Generator.DraftOutcome outcome = generator.draft(profile, request.description(), request.name(),
                    workspace, rules, budgetLeft);
            return switch (outcome.status()) {
                case VALID -> persist(caller, workspaceId, key, hash, gateKey, outcome);
                case NOT_A_WORKFLOW -> {
                    recordFailure(caller, workspaceId, outcome.meter(), AiLedger.Outcome.NOT_A_WORKFLOW);
                    String reason = outcome.evaluation().notAWorkflow();
                    throw new ApiException(ProblemCode.AI_OUTPUT_INVALID,
                            "The description does not describe a business workflow"
                                    + (reason == null ? "." : ": " + reason))
                            .with("issues", List.of());
                }
                case INVALID -> {
                    recordFailure(caller, workspaceId, outcome.meter(), AiLedger.Outcome.INVALID_OUTPUT);
                    throw new ApiException(ProblemCode.AI_OUTPUT_INVALID, outcome.deadlineStop()
                            ? "The time limit for one generation was reached before a valid draft was produced. "
                                    + "Nothing was created. Try a shorter or more specific description."
                            : outcome.budgetStop()
                            ? "The AI budget for one request was reached before a valid draft was produced. "
                                    + "Nothing was created."
                            : "The AI could not produce a valid workflow from this description. Nothing was created. "
                                    + "Try a more specific description.")
                            .with("issues", issues(outcome.issues()));
                }
                case PROVIDER_FAILURE -> {
                    ProviderFailure failure = classify(outcome.failure());
                    recordFailure(caller, workspaceId, outcome.meter(), failure.outcome());
                    throw failure.problem();
                }
            };
        } finally {
            leave(gateKey, gate);
        }
    }

    private Drafted persist(Caller caller, UUID workspaceId, String key, String hash, String gateKey,
            Generator.DraftOutcome outcome) {
        DraftRules.Evaluation evaluation = outcome.evaluation();
        Generator.Meter meter = outcome.meter();
        ObjectNode definition = evaluation.definition();
        String description = definition.path("metadata").path("description").asString(null);
        if (description != null && description.length() > 2000) {
            description = description.substring(0, 2000);
        }
        String workflowDescription = description;
        try {
            return tx.execute(status -> {
                IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), DRAFT_OPERATION,
                        key, hash);
                if (claim.isReplay()) {
                    // Completed by a concurrent identical request on another instance; this generation is only metered.
                    ledger.record(entry(caller, workspaceId, "draft", meter, PromptLibrary.DRAFT_VERSION,
                            AiLedger.Outcome.SUCCESS, null, null, null));
                    return new Drafted(replayed(caller, workspaceId, UUID.fromString(claim.replay().resourceId()),
                            gateKey), true);
                }
                DraftAuthoring.Created created = drafts.createFromDefinition(caller, workspaceId,
                        new DraftAuthoring.NewDraft(definition.path("workflowKey").asString(),
                                definition.path("name").asString(), workflowDescription, definition,
                                DraftAuthoring.Source.AI, true));
                ledger.record(entry(caller, workspaceId, "draft", meter, PromptLibrary.DRAFT_VERSION,
                        AiLedger.Outcome.SUCCESS, created.workflowId(), null, null));
                audit.write(auditEntry(caller, workspaceId, "ai.draft.generated", created.workflowId(), meter,
                        PromptLibrary.DRAFT_VERSION, AiLedger.Outcome.SUCCESS, null, null));
                idempotency.complete(claim, "workflow", created.workflowId().toString(), 201);
                List<AiDtos.MissingItem> missing = evaluation.missing().stream()
                        .map(m -> new AiDtos.MissingItem(m.message(), m.nodeId(), m.code())).toList();
                remember(gateKey, evaluation.assumptions(), missing);
                observe("draft", meter, AiLedger.Outcome.SUCCESS);
                return new Drafted(new AiDtos.DraftResult(created.workflow(), created.draft(), created.report(),
                        evaluation.assumptions(), missing, generation(meter, PromptLibrary.DRAFT_VERSION)), false);
            });
        } catch (RuntimeException e) {
            recordFailure(caller, workspaceId, meter, AiLedger.Outcome.INVALID_OUTPUT);
            throw e;
        }
    }

    /** Replay view: current workflow, draft revision 1 and report; review notes from memory when still there. */
    private AiDtos.DraftResult replayed(Caller caller, UUID workspaceId, UUID workflowId, String gateKey) {
        DraftAuthoring.Created view = drafts.view(caller, workspaceId, workflowId, 1);
        Notes remembered = notes.get(gateKey);
        List<String> assumptions;
        List<AiDtos.MissingItem> missing;
        if (remembered != null && remembered.expiresAt().isAfter(clock.instant())) {
            assumptions = remembered.assumptions();
            missing = remembered.missing();
        } else {
            assumptions = List.of();
            List<ValidationIssue> blockers = new ArrayList<>();
            for (JsonNode issue : view.report().path("issues")) {
                if ("blocker".equals(issue.path("severity").asString(""))) {
                    blockers.add(new ValidationIssue(issue.path("code").asString(""), Severity.BLOCKER,
                            issue.path("message").asString(""), issue.path("path").asString(""),
                            issue.path("nodeId").asString(null), issue.path("transitionId").asString(null)));
                }
            }
            JsonNode definition = view.draft().path("definition");
            missing = rules.bindingItems(blockers, definition.isObject() ? (ObjectNode) definition : json.object())
                    .stream().map(m -> new AiDtos.MissingItem(m.message(), m.nodeId(), m.code())).toList();
        }
        AiDtos.Generation generation = ledger.draftGeneration(workspaceId, workflowId)
                .orElseGet(() -> new AiDtos.Generation("unknown", "unknown", PromptLibrary.DRAFT_VERSION, 0, false,
                        null, null, null, 0));
        return new AiDtos.DraftResult(view.workflow(), view.draft(), view.report(), assumptions, missing, generation);
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
        Generator.Explanation explanation;
        try {
            explanation = generator.explain(profile, definition.definition(), meter);
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
                generation(meter, PromptLibrary.EXPLAIN_VERSION));
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
                meter.outputTokens(), meter.cost(), meter.durationMs(), outcome, workflowId, revision, releaseNumber);
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
                meter.inputTokens(), meter.outputTokens(), usd(meter.cost()), meter.durationMs());
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

    // ---------------------------------------------------------------- per-key serialization and review notes

    private Gate enter(String key) {
        Gate gate = gates.compute(key, (k, existing) -> {
            Gate current = existing == null ? new Gate(new ReentrantLock(), new AtomicInteger()) : existing;
            current.users().incrementAndGet();
            return current;
        });
        gate.lock().lock();
        return gate;
    }

    private void leave(String key, Gate gate) {
        gate.lock().unlock();
        gates.computeIfPresent(key, (k, existing) -> existing.users().decrementAndGet() == 0 ? null : existing);
    }

    private void remember(String key, List<String> assumptions, List<AiDtos.MissingItem> missing) {
        Instant now = clock.instant();
        notes.values().removeIf(n -> !n.expiresAt().isAfter(now));
        if (notes.size() >= REVIEW_NOTES_MAX) {
            notes.entrySet().stream().min(Map.Entry.comparingByValue(
                    java.util.Comparator.comparing(Notes::expiresAt))).map(Map.Entry::getKey).ifPresent(notes::remove);
        }
        notes.put(key, new Notes(List.copyOf(assumptions), List.copyOf(missing), now.plus(REVIEW_NOTES_TTL)));
    }
}
