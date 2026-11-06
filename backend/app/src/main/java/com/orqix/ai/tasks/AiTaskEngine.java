package com.orqix.ai.tasks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.ai.internal.AiLedger;
import com.orqix.ai.internal.ModelPolicy;
import com.orqix.ai.internal.ProviderException;
import com.orqix.ai.provider.ModelProvider;
import com.orqix.ai.provider.ProviderRegistry;
import com.orqix.ai.skills.AiSkills;
import com.orqix.compiler.CompileContext;
import com.orqix.integration.AiTaskPort;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The AI task guardrail pipeline (CG-A01, doc 29 sections 7.4-7.6, docs/build/S134 section 4): admission,
 * minimization, prompt assembly from the pinned skill version, provider calls through the SPI (samples for
 * self-consistency, one repair turn per sample), validation, consistency, calibration on the version's evaluation
 * run, autonomy policy and routing. AI being unavailable never blocks work: every problem ends on {@code aiUnavailable}
 * (or, in shadow mode, simply continues). Completed turns are sealed into turn state between calls (CG-A05).
 */
@Component
public class AiTaskEngine implements AiTaskPort {

    private static final Logger log = LoggerFactory.getLogger(AiTaskEngine.class);
    static final String PROMPT_VERSION = "ai-task-v1";
    private static final List<String> AUTONOMY = List.of("shadow", "suggest", "autoWithReview", "auto");

    private final AiSkills skills;
    private final ProviderRegistry providers;
    private final ModelPolicy modelPolicy;
    private final AiLedger ledger;
    private final com.orqix.ai.AiProperties ai;
    private final AiTaskProperties properties;
    private final TurnVault vault;
    private final Json json;
    private final Clock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate independentTx;

    public AiTaskEngine(AiSkills skills, ProviderRegistry providers, ModelPolicy modelPolicy, AiLedger ledger,
            com.orqix.ai.AiProperties ai, AiTaskProperties properties, TurnVault vault, Json json, Clock clock,
            JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.skills = skills;
        this.providers = providers;
        this.modelPolicy = modelPolicy;
        this.ledger = ledger;
        this.ai = ai;
        this.properties = properties;
        this.vault = vault;
        this.json = json;
        this.clock = clock;
        this.jdbc = jdbc;
        this.independentTx = new TransactionTemplate(transactions);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Map<String, CompileContext.AiSkillInfo> skillFacts(UUID workspaceId) {
        return skills.facts(workspaceId);
    }

    // ================================================================== inference (shared with evaluations)

    /** Result of the provider stage for one input. */
    record Inference(List<SkillAnswers.Answer> answers, int samples, int repairs, int calls, long inputTokens,
            long outputTokens, BigDecimal costUsd, String modelUsed, Double tokenProbability, boolean schemaEnforced,
            String failure, boolean leaseLost) {

        boolean usable() {
            return !answers.isEmpty() && !leaseLost;
        }
    }

    /**
     * Calls the provider for {@code samples} answers (one repair turn each), stopping at the deadline, the cost
     * ceiling or a lost lease. Completed turns are read from and saved to turn state when {@code jobId} is set.
     */
    Inference infer(UUID workspaceId, AiSkills.Version version, ProviderRegistry.Resolved profile, ObjectNode sent,
            int samples, Instant deadline, BigDecimal maxCost, AiTaskPort.Lease lease, UUID jobId) {
        SkillAnswers prompts = new SkillAnswers(json, version.document());
        String system = prompts.system();
        String user = prompts.user(sent);
        ObjectNode schema = prompts.schema();
        int maxTokens = version.document().path("limits").path("maxOutputTokens").asInt(800);
        List<SkillAnswers.Answer> answers = new ArrayList<>();
        ArrayNode turns = jobId == null ? json.mapper().createArrayNode() : vault.turns(workspaceId, jobId);
        int repairs = 0;
        int calls = 0;
        long in = 0;
        long out = 0;
        BigDecimal cost = BigDecimal.ZERO;
        String model = null;
        Double probability = null;
        boolean enforced = false;
        String failure = null;
        // Turns completed before a crash count again without a new provider call.
        for (JsonNode turn : turns) {
            Object checked = prompts.check(turn.path("content").asString(null));
            if (checked instanceof SkillAnswers.Answer a) {
                answers.add(a);
            }
            repairs += turn.path("repaired").asBoolean(false) ? 1 : 0;
            in += turn.path("inputTokens").asLong(0);
            out += turn.path("outputTokens").asLong(0);
            cost = cost.add(turn.path("costUsd").decimalValue());
            model = turn.path("model").asString(model);
        }
        for (int sample = turns.size(); sample < samples; sample++) {
            if (lease != null && !lease.renew()) {
                return new Inference(answers, samples, repairs, calls, in, out, cost, model, probability, enforced,
                        "The task lost its lease.", true);
            }
            Duration left = Duration.between(clock.instant(), deadline);
            if (left.toMillis() < 1000) {
                failure = "The task reached its deadline.";
                break;
            }
            if (cost.compareTo(maxCost) >= 0) {
                failure = "The task reached its cost limit.";
                break;
            }
            Double temperature = sample == 0 ? Double.valueOf(0) : Double.valueOf(0.7);
            List<ModelProvider.Turn> conversation = new ArrayList<>(List.of(new ModelProvider.Turn("user", user)));
            ModelProvider.ModelResponse response;
            try {
                response = providers.complete(profile, new ModelProvider.ModelRequest(profile.model(), system,
                        conversation, "answer", schema, maxTokens, temperature, left), workspaceId);
                calls++;
            } catch (ProviderException e) {
                calls++;
                failure = providerFailure(e, profile);
                if (e.kind() != ProviderException.Kind.UNAVAILABLE) {
                    break;
                }
                continue;
            }
            in += nz(response.inputTokens());
            out += nz(response.outputTokens());
            cost = cost.add(response.costUsd() == null ? BigDecimal.ZERO : response.costUsd());
            model = response.model();
            enforced |= response.schemaEnforced();
            if (response.meanTokenProbability() != null) {
                probability = response.meanTokenProbability();
            }
            boolean repaired = false;
            String content = response.content();
            Object checked = prompts.check(content);
            if (checked instanceof String problem && Duration.between(clock.instant(), deadline).toMillis() > 1000) {
                // Stage 5: one repair turn, then the sample counts as invalid.
                repaired = true;
                repairs++;
                conversation.add(new ModelProvider.Turn("assistant", content == null ? "" : content));
                conversation.add(new ModelProvider.Turn("user", "Your answer was not valid: " + problem
                        + " Answer again with JSON that matches the schema only."));
                try {
                    ModelProvider.ModelResponse second = providers.complete(profile, new ModelProvider.ModelRequest(
                            profile.model(), system, conversation, "answer", schema, maxTokens, temperature,
                            Duration.between(clock.instant(), deadline)), workspaceId);
                    calls++;
                    in += nz(second.inputTokens());
                    out += nz(second.outputTokens());
                    cost = cost.add(second.costUsd() == null ? BigDecimal.ZERO : second.costUsd());
                    content = second.content();
                    checked = prompts.check(content);
                } catch (ProviderException e) {
                    calls++;
                    failure = providerFailure(e, profile);
                }
            }
            if (checked instanceof SkillAnswers.Answer a) {
                answers.add(a);
            } else if (failure == null) {
                failure = "The AI answer did not match the skill's output after a repair.";
            }
            if (jobId != null) {
                ObjectNode turn = turns.addObject();
                turn.put("content", checked instanceof SkillAnswers.Answer ? content : "");
                turn.put("repaired", repaired).put("inputTokens", nz(response.inputTokens()))
                        .put("outputTokens", nz(response.outputTokens()))
                        .put("costUsd", response.costUsd() == null ? BigDecimal.ZERO : response.costUsd());
                if (model != null) {
                    turn.put("model", model);
                }
                try {
                    vault.saveTurns(workspaceId, jobId, turns);
                } catch (RuntimeException e) {
                    log.warn("AI turn state not saved: {}", e.getClass().getSimpleName());
                }
            }
        }
        return new Inference(answers, samples, repairs, calls, in, out, cost, model, probability, enforced,
                answers.isEmpty() ? (failure == null ? "AI produced no answer." : failure) : null, false);
    }

    private String providerFailure(ProviderException e, ProviderRegistry.Resolved profile) {
        if (e.kind() == ProviderException.Kind.CREDENTIALS_REJECTED
                && com.orqix.ai.provider.OpenAiCompatibleProvider.OPENROUTER.equals(profile.endpointId())) {
            modelPolicy.credentialsRejected();
        }
        return switch (e.kind()) {
            case UNAVAILABLE -> e.timedOut() ? "The AI provider did not answer in time." : "The AI provider is unavailable.";
            case CREDENTIALS_REJECTED -> "The AI provider rejected the credentials.";
            case PAYMENT_REQUIRED -> "The AI provider account has no credit.";
            case REJECTED -> "The AI provider refused the request.";
        };
    }

    /** The chosen answer and its agreement across valid samples (stage 6). */
    record Consensus(SkillAnswers.Answer answer, double agreement) {
    }

    static Consensus consensus(List<SkillAnswers.Answer> answers) {
        Map<String, Integer> votes = new LinkedHashMap<>();
        answers.forEach(a -> votes.merge(a.vote(), 1, Integer::sum));
        String winner = votes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        SkillAnswers.Answer chosen = answers.stream().filter(a -> a.vote().equals(winner)).findFirst().orElseThrow();
        if ("score".equals(chosen.mode()) && answers.size() > 1) {
            List<BigDecimal> scores = answers.stream().map(SkillAnswers.Answer::score).sorted().toList();
            BigDecimal median = scores.get(scores.size() / 2);
            chosen = answers.stream().filter(a -> a.score().compareTo(median) == 0).findFirst().orElse(chosen);
        }
        return new Consensus(chosen, votes.get(winner) / (double) answers.size());
    }

    /** Stage 7 input: the raw confidence from signals Orqix can check (section 7.5). */
    static double rawConfidence(Inference inf, Consensus c) {
        double raw;
        if (inf.samples() > 1) {
            raw = c.agreement();
        } else if (inf.tokenProbability() != null) {
            raw = inf.tokenProbability();
        } else {
            raw = c.answer().reported() == null ? 0.5 : c.answer().reported();
        }
        if ("extract".equals(c.answer().mode()) && c.answer().fieldConfidence() != null
                && !c.answer().fieldConfidence().isEmpty()) {
            double lowest = 1;
            for (var e : c.answer().fieldConfidence().properties()) {
                lowest = Math.min(lowest, e.getValue().asDouble());
            }
            raw = Math.min(raw, lowest);
        }
        if (inf.repairs() > 0) {
            raw *= 0.9;
        }
        return Math.max(0, Math.min(1, raw));
    }

    /** Stage 7: the observed accuracy of the evaluation bucket the raw score falls in (5 or more cases), else raw. */
    record Calibrated(double value, UUID runId) {
    }

    Calibrated calibrate(AiSkills.Version version, String profile, double raw) {
        Optional<Map.Entry<UUID, String>> run = jdbc.sql("""
                SELECT id, calibration FROM ai_eval_run WHERE skill_version_id = :v AND model_profile = :p
                  AND status = 'passed' AND calibration IS NOT NULL ORDER BY finished_at DESC LIMIT 1
                """).param("v", version.id()).param("p", profile)
                .query((rs, n) -> Map.entry(Sql.uuid(rs, "id"), rs.getString("calibration"))).optional();
        if (run.isEmpty()) {
            return new Calibrated(raw, null);
        }
        for (JsonNode bucket : json.parse(run.get().getValue())) {
            double from = bucket.path("from").asDouble();
            double to = bucket.path("to").asDouble();
            if (raw >= from && (raw < to || to >= 1 && raw <= 1) && bucket.path("cases").asInt() >= 5) {
                return new Calibrated(bucket.path("accuracy").asDouble(), run.get().getKey());
            }
        }
        return new Calibrated(raw, run.get().getKey());
    }

    // ================================================================== the pipeline

    @Override
    public Outcome giveUp(TaskRequest request, String reason) {
        AiSkills.Version version = skills.version(request.workspaceId(), request.skillKey(), request.skillVersion())
                .orElse(null);
        AiSkills.Policy policy = skills.policy(request.workspaceId());
        ObjectNode guardrails = json.object();
        Minimizer.Result minimized = Minimizer.apply(json, version == null ? json.object()
                : version.document().path("input"), request.input(), policy, request.redaction());
        Context ctx = new Context(request, version, policy, minimized, json.canonicalSha256(request.input()),
                guardrails, System.nanoTime());
        return unavailable(ctx, null, null, reason);
    }

    @Override
    public Outcome run(TaskRequest request, Lease lease) {
        long started = System.nanoTime();
        Optional<AiSkills.Version> found = skills.version(request.workspaceId(), request.skillKey(), request.skillVersion());
        AiSkills.Policy policy = skills.policy(request.workspaceId());
        AiSkills.Version version = found.orElse(null);
        ObjectNode guardrails = json.object();
        JsonNode contract = version == null ? json.object() : version.document().path("input");
        Minimizer.Result minimized = Minimizer.apply(json, contract, request.input(), policy, request.redaction());
        ObjectNode actions = guardrails.putObject("minimization");
        minimized.actions().forEach(actions::put);
        String digest = json.canonicalSha256(request.input());
        Context ctx = new Context(request, version, policy, minimized, digest, guardrails, started);

        // Stage 1: admission.
        if (version == null || !"approved".equals(version.status())) {
            return unavailable(ctx, null, null, "The AI skill version is not approved (or was retired).");
        }
        if (request.contentHash() != null && !request.contentHash().equals(version.contentHash())) {
            return unavailable(ctx, null, null, "The AI skill version differs from the one this release pinned.");
        }
        if (minimized.problem() != null) {
            return unavailable(ctx, null, null, minimized.problem());
        }
        ProviderRegistry.Resolved profile;
        try {
            profile = providers.resolve(version.modelProfile(), policy.residency());
        } catch (ProviderRegistry.ProfileRefusedException e) {
            guardrails.putObject("admission").put("refused", e.code());
            return unavailable(ctx, null, null, e.getMessage());
        }
        boolean shadow = "shadow".equals(request.autonomy());
        if (!shadow && !gate(version, profile.profileId())) {
            return unavailable(ctx, profile, null, "The AI skill version has no passing evaluation on its profile"
                    + " in the last 30 days.");
        }
        BigDecimal maxCost = version.document().path("limits").has("maxCostUsd")
                ? version.document().path("limits").path("maxCostUsd").decimalValue() : properties.defaultMaxCostUsd();
        BigDecimal budgetLeft = ai.budget().monthlyUsd().subtract(ledger.monthToDate());
        if (budgetLeft.compareTo(BigDecimal.ZERO) <= 0) {
            return unavailable(ctx, profile, null, "The AI budget for this month is used up.");
        }
        maxCost = maxCost.min(budgetLeft).min(ai.budget().perRequestMaxUsd());
        String effective = effectiveAutonomy(ctx, version, policy);
        ctx.effective = effective;

        // Stages 3-6: prompt, provider, validation, consistency.
        JsonNode confidence = version.document().path("confidence");
        int samples = "selfConsistency".equals(confidence.path("method").asString("single"))
                ? Math.max(1, confidence.path("samples").asInt(3)) : 1;
        int timeout = version.document().path("limits").path("timeoutSeconds")
                .asInt((int) properties.defaultTimeout().toSeconds());
        Instant deadline = clock.instant().plusSeconds(Math.min(timeout, properties.maxTimeout().toSeconds()));
        if (lease.deadline() != null && lease.deadline().isBefore(deadline)) {
            deadline = lease.deadline();
        }
        guardrails.put("deadlineSeconds", timeout);
        Inference inf = infer(request.workspaceId(), version, profile, minimized.sent(), samples, deadline,
                maxCost, lease, request.jobId());
        ctx.inference = inf;
        if (inf.leaseLost()) {
            // Another worker owns the job now; it records the decision. Nothing is metered twice.
            return new Outcome(null, null, null);
        }
        meter(ctx, profile, inf);
        guardrails.put("samples", samples).put("validSamples", inf.answers().size()).put("repairs", inf.repairs())
                .put("providerCalls", inf.calls()).put("schemaEnforced", inf.schemaEnforced());
        if (!inf.usable()) {
            return unavailable(ctx, profile, inf, inf.failure());
        }
        Consensus consensus = consensus(inf.answers());
        guardrails.put("agreement", round(consensus.agreement()));

        // Stage 7: calibration.
        double raw = rawConfidence(inf, consensus);
        Calibrated calibrated = calibrate(version, profile.profileId(), raw);
        guardrails.put("calibrated", calibrated.runId() != null);
        if (calibrated.runId() != null) {
            guardrails.put("calibrationRunId", calibrated.runId().toString());
        }

        // Stage 8: routing.
        return route(ctx, profile, consensus, raw, calibrated.value());
    }

    /** Per-task facts collected through the stages. */
    private static final class Context {
        final TaskRequest request;
        final AiSkills.Version version;
        final AiSkills.Policy policy;
        final Minimizer.Result minimized;
        final String digest;
        final ObjectNode guardrails;
        final long started;
        final ArrayNode downgrades;
        String effective;
        Inference inference;

        Context(TaskRequest request, AiSkills.Version version, AiSkills.Policy policy, Minimizer.Result minimized,
                String digest, ObjectNode guardrails, long started) {
            this.request = request;
            this.version = version;
            this.policy = policy;
            this.minimized = minimized;
            this.digest = digest;
            this.guardrails = guardrails;
            this.started = started;
            this.downgrades = guardrails.putArray("downgrades");
            this.effective = request.autonomy();
        }
    }

    /** Effective autonomy: the step's, capped by the version's promotion, drift fallback and the workspace policy. */
    private String effectiveAutonomy(Context ctx, AiSkills.Version version, AiSkills.Policy policy) {
        String autonomy = ctx.request.autonomy();
        if ("shadow".equals(autonomy) || "suggest".equals(autonomy)) {
            return autonomy;
        }
        if (AUTONOMY.indexOf(autonomy) > AUTONOMY.indexOf(version.autonomyCeiling())) {
            ctx.downgrades.add("notPromoted");
            autonomy = version.autonomyCeiling();
        }
        if (version.driftFallback() && !"suggest".equals(autonomy)) {
            ctx.downgrades.add("drift");
            autonomy = "suggest";
        }
        if ("autoWithReview".equals(autonomy) && !policy.allowAutoWithReview()) {
            ctx.downgrades.add("policy");
            autonomy = "suggest";
        }
        return autonomy;
    }

    private Outcome route(Context ctx, ProviderRegistry.Resolved profile, Consensus consensus, double raw,
            double confidence) {
        TaskRequest request = ctx.request;
        SkillAnswers.Answer answer = consensus.answer();
        BigDecimal threshold = request.minConfidence();
        boolean below = BigDecimal.valueOf(confidence).compareTo(threshold) < 0;
        String person = request.hasNeedsReview() ? "needsReview" : "lowConfidence";
        String route;
        String status;
        boolean sampled = false;
        if ("shadow".equals(ctx.effective)) {
            route = "next";
            status = "shadow";
        } else {
            switch (request.mode()) {
                case "classify" -> {
                    JsonNode label = labelFacts(ctx.version.document(), answer.label());
                    if (below) {
                        route = "lowConfidence";
                        status = "lowConfidence";
                    } else if ("suggest".equals(ctx.effective)) {
                        route = person;
                        status = "needsReview";
                    } else if (ctx.policy.regulated() && label.path("adverse").asBoolean(false)) {
                        // D-CG3: adverse outcomes in regulated workspaces always need a person.
                        ctx.downgrades.add("adverseRegulated");
                        route = person;
                        status = "needsReview";
                    } else if ("auto".equals(ctx.effective)
                            && !ctx.policy.lowRiskLabels().contains(request.skillKey() + ":" + answer.label())) {
                        // D-CG3: auto only for labels the policy marks low-risk.
                        ctx.downgrades.add("notLowRisk");
                        route = person;
                        status = "needsReview";
                    } else {
                        route = "label:" + answer.label();
                        status = "decided";
                        sampled = "autoWithReview".equals(ctx.effective) && sampled(request);
                    }
                }
                case "extract" -> {
                    boolean missing = false;
                    for (JsonNode f : ctx.version.document().path("fields")) {
                        if (f.path("required").asBoolean(false) && !answer.fields().has(f.path("name").asString())) {
                            missing = true;
                        }
                    }
                    if (below || missing || "suggest".equals(ctx.effective)) {
                        route = "needsReview";
                        status = "needsReview";
                        if (missing) {
                            ctx.downgrades.add("missingField");
                        }
                    } else {
                        route = "next";
                        status = "decided";
                        sampled = "autoWithReview".equals(ctx.effective) && sampled(request);
                    }
                }
                default -> {
                    route = "next";
                    status = "decided";
                }
            }
        }
        ObjectNode outputs = baseOutputs(status, request.skillVersion());
        outputs.put("confidence", round(confidence));
        outputs.put("modelUsed", ctx.inference.modelUsed() == null ? profile.model() : ctx.inference.modelUsed());
        ObjectNode output = json.object();
        switch (request.mode()) {
            case "classify" -> {
                outputs.put("label", answer.label());
                output.put("label", answer.label());
            }
            case "extract" -> {
                answer.fields().properties().forEach(e -> outputs.set(e.getKey(), e.getValue()));
                output.set("fields", answer.fields());
                output.set("fieldConfidence", answer.fieldConfidence());
            }
            case "score" -> {
                outputs.put("score", answer.score());
                output.put("score", answer.score());
            }
            case "recommend" -> {
                outputs.put("recommendation", answer.recommendation()).put("rationale", answer.text());
                output.put("recommendation", answer.recommendation()).put("rationale", answer.text());
            }
            default -> {
                outputs.put("summary", answer.text());
                output.put("summary", answer.text());
            }
        }
        if ("classify".equals(request.mode()) || "score".equals(request.mode())) {
            ArrayNode reasons = outputs.putArray("reasons");
            answer.reasons().forEach(reasons::add);
            output.set("reasons", reasons.deepCopy());
            output.set("evidence", evidence(ctx.minimized.sent(), answer.quotes()));
        }
        ctx.guardrails.put("threshold", threshold.doubleValue()).put("route", route);
        DecisionRecord record = record(ctx, profile, status, output, raw, confidence, route, null, sampled);
        return new Outcome(route, outputs, record);
    }

    private Outcome unavailable(Context ctx, ProviderRegistry.Resolved profile, Inference inf, String reason) {
        TaskRequest request = ctx.request;
        boolean shadow = "shadow".equals(request.autonomy());
        String route = shadow ? "next" : "aiUnavailable";
        String status = shadow ? "shadow" : "unavailable";
        if (inf != null && ctx.inference == null) {
            ctx.inference = inf;
        }
        ObjectNode outputs = baseOutputs(status, request.skillVersion());
        ctx.guardrails.put("route", route);
        DecisionRecord record = record(ctx, profile, status, null, null, null, route, reason, false);
        return new Outcome(route, outputs, record);
    }

    private ObjectNode baseOutputs(String status, int skillVersion) {
        ObjectNode outputs = json.object();
        outputs.put("status", status);
        outputs.put("skillVersion", skillVersion);
        return outputs;
    }

    private DecisionRecord record(Context ctx, ProviderRegistry.Resolved profile, String status, JsonNode output,
            Double raw, Double confidence, String route, String reason, boolean sampled) {
        TaskRequest r = ctx.request;
        Inference inf = ctx.inference;
        JsonNode values = switch (ctx.policy.inputRetention()) {
            case "fullValues" -> r.input();
            case "redactedValues" -> ctx.minimized.sent();
            default -> null;
        };
        String promptHash = ctx.version == null ? null : Json.sha256Hex(new SkillAnswers(json, ctx.version.document())
                .system() + "\n" + json.write(new SkillAnswers(json, ctx.version.document()).schema()));
        List<String> fields = new ArrayList<>();
        r.input().propertyNames().forEach(fields::add);
        return new DecisionRecord(r.workspaceId(), r.runId(), r.nodeId(), r.occurrence(), r.skillKey(), r.skillVersion(),
                ctx.version == null ? r.contentHash() : ctx.version.contentHash(), r.mode(), r.autonomy(),
                ctx.effective == null ? r.autonomy() : ctx.effective, profile == null ? null : profile.profileId(),
                profile == null ? null : profile.kind().json() + ":" + profile.endpointId(),
                inf == null ? null : inf.modelUsed(), promptHash, fields, ctx.digest, values,
                ctx.policy.inputRetention(), ctx.policy.retentionDays(), status, output,
                raw == null ? null : BigDecimal.valueOf(round(raw)), confidence == null ? null
                : BigDecimal.valueOf(round(confidence)), r.minConfidence(), route, ctx.guardrails,
                reason == null ? null : AiSkillsText.cut(reason, 300), sampled,
                inf == null ? null : inf.inputTokens(), inf == null ? null : inf.outputTokens(),
                inf == null ? null : inf.costUsd(), (System.nanoTime() - ctx.started) / 1_000_000);
    }

    /** Quotes located in the sent text values: field and character offsets (section 7.8 evidence spans). */
    private ArrayNode evidence(ObjectNode sent, List<String> quotes) {
        ArrayNode spans = json.mapper().createArrayNode();
        for (String quote : quotes) {
            if (quote == null || quote.isBlank()) {
                continue;
            }
            for (var e : sent.properties()) {
                if (e.getValue().isString()) {
                    int at = e.getValue().asString().indexOf(quote);
                    if (at >= 0) {
                        spans.addObject().put("field", e.getKey()).put("start", at).put("end", at + quote.length());
                        break;
                    }
                }
            }
        }
        return spans;
    }

    private static JsonNode labelFacts(JsonNode skill, String label) {
        for (JsonNode l : skill.path("labels")) {
            if (label.equals(l.path("key").asString())) {
                return l;
            }
        }
        return tools.jackson.databind.node.MissingNode.getInstance();
    }

    /** Deterministic review sample (section 7.6): the same occurrence is always in or out. */
    private static boolean sampled(TaskRequest r) {
        int bucket = Math.floorMod((r.runId() + "/" + r.nodeId() + "/" + r.occurrence()).hashCode(), 100);
        return bucket < r.reviewSamplePercent();
    }

    private boolean gate(AiSkills.Version version, String profile) {
        return jdbc.sql("""
                SELECT count(*) FROM ai_eval_run WHERE skill_version_id = :v AND model_profile = :p AND status = 'passed'
                  AND finished_at >= :since
                """).param("v", version.id()).param("p", profile)
                .param("since", Sql.ts(clock.instant().minus(AiSkills.GATE_WINDOW))).query(Integer.class).single() > 0;
    }

    private void meter(Context ctx, ProviderRegistry.Resolved profile, Inference inf) {
        if (inf.calls() == 0) {
            return;
        }
        TaskRequest r = ctx.request;
        try {
            independentTx.executeWithoutResult(status -> ledger.record(new AiLedger.Entry(r.workspaceId(),
                    r.initiatorId(), "aiTask", AiSkillsText.cut(profile.profileId(), 32),
                    inf.modelUsed() == null ? profile.model() : inf.modelUsed(), profile.model(), PROMPT_VERSION,
                    Math.min(20, inf.calls()), false, inf.inputTokens(), inf.outputTokens(), inf.costUsd(),
                    (System.nanoTime() - ctx.started) / 1_000_000,
                    inf.usable() ? AiLedger.Outcome.SUCCESS : AiLedger.Outcome.PROVIDER_ERROR, r.workflowId(), null,
                    null)));
        } catch (RuntimeException e) {
            log.warn("AI task metering failed: {}", e.getClass().getSimpleName());
        }
    }

    // ================================================================== records

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(DecisionRecord r) {
        UUID id = Ids.newId();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO ai_decision_record (id, workspace_id, run_id, node_id, occurrence, skill_key, skill_version,
                    skill_hash, mode, autonomy, effective_autonomy, profile, provider, model_used, prompt_hash,
                    input_fields, input_digest, input_values, input_retention, status, output, raw_confidence,
                    confidence, threshold, route, guardrails, unavailable_reason, review_sampled, input_tokens,
                    output_tokens, cost_usd, latency_ms, created_at, retain_until)
                VALUES (:id, :ws, :run, :node, :occ, :skill, :version, :hash, :mode, :autonomy, :effective, :profile,
                    :provider, :model, :prompt, :fields, :digest, CAST(:values AS jsonb), :retention, :status,
                    CAST(:output AS jsonb), :raw, :confidence, :threshold, :route, CAST(:guardrails AS jsonb), :reason,
                    :sampled, :in, :out, :cost, :latency, :now, :until)
                ON CONFLICT (run_id, node_id, occurrence) DO NOTHING
                """).param("id", id).param("ws", r.workspaceId()).param("run", r.runId()).param("node", r.nodeId())
                .param("occ", r.occurrence()).param("skill", r.skillKey()).param("version", r.skillVersion())
                .param("hash", r.skillHash()).param("mode", r.mode()).param("autonomy", r.autonomy())
                .param("effective", r.effectiveAutonomy()).param("profile", r.profile()).param("provider", r.provider())
                .param("model", r.modelUsed()).param("prompt", r.promptHash())
                .param("fields", r.inputFields().toArray(String[]::new)).param("digest", r.inputDigest())
                .param("values", r.inputValues() == null ? null : json.write(r.inputValues()))
                .param("retention", r.inputRetention()).param("status", r.status())
                .param("output", r.output() == null ? null : json.write(r.output()))
                .param("raw", r.rawConfidence()).param("confidence", r.confidence()).param("threshold", r.threshold())
                .param("route", r.route()).param("guardrails", json.write(r.guardrails()))
                .param("reason", r.unavailableReason()).param("sampled", r.reviewSampled())
                .param("in", r.inputTokens()).param("out", r.outputTokens()).param("cost", r.costUsd())
                .param("latency", r.latencyMs()).param("now", Sql.ts(now))
                .param("until", Sql.ts(now.plus(Duration.ofDays(r.retentionDays())))).update();
        return id;
    }

    @Override
    public JsonNode evaluationEvidence(UUID workspaceId, UUID evaluationRunId) {
        return jdbc.sql("""
                SELECT r.id, r.status, r.model_profile, e.key AS suite_key, r.suite_hash, r.case_count,
                       r.metrics ->> 'accuracy' AS accuracy, r.finished_at
                FROM ai_eval_run r JOIN ai_eval_suite e ON e.id = r.suite_id
                WHERE r.workspace_id = :ws AND r.id = :id
                """).param("ws", workspaceId).param("id", evaluationRunId).query((rs, n) -> {
            ObjectNode o = json.object();
            o.put("evaluationRunId", Sql.uuid(rs, "id").toString()).put("status", rs.getString("status"))
                    .put("modelProfile", rs.getString("model_profile")).put("suite", rs.getString("suite_key"))
                    .put("suiteHash", rs.getString("suite_hash")).put("cases", rs.getInt("case_count"))
                    .put("accuracy", rs.getString("accuracy"));
            Instant finished = Sql.instant(rs, "finished_at");
            o.put("finishedAt", finished == null ? null : finished.toString());
            return (JsonNode) o;
        }).optional().orElse(null);
    }

    @Override
    public void endTurns(UUID workspaceId, UUID jobId) {
        vault.delete(jobId);
    }

    @Override
    public String seal(UUID workspaceId, UUID jobId, ObjectNode input) {
        return vault.seal(workspaceId, jobId, "input", json.write(input));
    }

    @Override
    public ObjectNode open(UUID workspaceId, UUID jobId, String sealed) {
        JsonNode input = json.parse(vault.open(workspaceId, jobId, "input", sealed));
        if (!input.isObject()) {
            throw new IllegalStateException("AI job input is not an object");
        }
        return (ObjectNode) input;
    }

    @Override
    public List<DecisionView> decisions(UUID workspaceId, UUID runId, boolean withValues) {
        return jdbc.sql("""
                SELECT * FROM ai_decision_record WHERE workspace_id = :ws AND run_id = :run ORDER BY created_at, node_id
                """).param("ws", workspaceId).param("run", runId).query((rs, n) -> view(rs, withValues)).list();
    }

    private DecisionView view(ResultSet rs, boolean withValues) throws SQLException {
        String values = rs.getString("input_values");
        String output = rs.getString("output");
        return new DecisionView(Sql.uuid(rs, "id"), rs.getString("node_id"), rs.getInt("occurrence"),
                rs.getString("skill_key"), rs.getInt("skill_version"), rs.getString("skill_hash"), rs.getString("mode"),
                rs.getString("autonomy"), rs.getString("effective_autonomy"), rs.getString("profile"),
                rs.getString("provider"), rs.getString("model_used"), rs.getString("prompt_hash"),
                AiSkillsText.array(rs.getArray("input_fields")), rs.getString("input_digest"),
                withValues && values != null ? json.parse(values) : null, rs.getString("input_retention"),
                rs.getString("status"), output == null ? null : json.parse(output), rs.getBigDecimal("raw_confidence"),
                rs.getBigDecimal("confidence"), rs.getBigDecimal("threshold"), rs.getString("route"),
                json.parse(rs.getString("guardrails")), rs.getString("unavailable_reason"),
                rs.getBoolean("review_sampled"), rs.getString("human_outcome"), rs.getString("human_value"),
                rs.getString("human_reason"), Sql.uuid(rs, "human_by"), Sql.instant(rs, "human_at"),
                (Long) rs.getObject("input_tokens"), (Long) rs.getObject("output_tokens"), rs.getBigDecimal("cost_usd"),
                rs.getLong("latency_ms"), Sql.instant(rs, "created_at"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordOutcome(UUID workspaceId, UUID runId, UUID decisionId, HumanOutcome outcome) {
        var found = jdbc.sql("""
                SELECT skill_key, mode, input_values FROM ai_decision_record
                WHERE workspace_id = :ws AND run_id = :run AND id = :id FOR UPDATE
                """).param("ws", workspaceId).param("run", runId).param("id", decisionId)
                .query((rs, n) -> new String[] {rs.getString("skill_key"), rs.getString("mode"),
                        rs.getString("input_values")}).optional();
        if (found.isEmpty()) {
            return false;
        }
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE ai_decision_record SET human_outcome = :outcome, human_value = :value, human_reason = :reason,
                       human_by = :by, human_at = :now
                WHERE id = :id
                """).param("outcome", outcome.outcome()).param("value", outcome.value())
                .param("reason", AiSkillsText.cut(outcome.reason(), 1000)).param("by", outcome.decidedBy())
                .param("now", Sql.ts(now)).param("id", decisionId).update();
        if ("overridden".equals(outcome.outcome()) && outcome.value() != null) {
            ObjectNode expected = json.object();
            String mode = found.get()[1];
            expected.put("recommend".equals(mode) ? "outcome" : "label", outcome.value());
            jdbc.sql("""
                    INSERT INTO ai_eval_candidate (id, workspace_id, skill_key, decision_id, input, expected, status,
                                                   created_at)
                    VALUES (:id, :ws, :skill, :decision, CAST(:input AS jsonb), CAST(:expected AS jsonb), 'pending', :now)
                    ON CONFLICT (decision_id) DO UPDATE SET expected = EXCLUDED.expected, status = 'pending'
                    """).param("id", Ids.newId()).param("ws", workspaceId).param("skill", found.get()[0])
                    .param("decision", decisionId).param("input", found.get()[2])
                    .param("expected", json.write(expected)).param("now", Sql.ts(now)).update();
        }
        return true;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
