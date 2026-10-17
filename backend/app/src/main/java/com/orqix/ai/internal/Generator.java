package com.orqix.ai.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.orqix.ai.AiProperties;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Provider conversations (S10 sections 3 and 4). Drafts: first attempt, up to {@code max-repair-attempts} repair turns
 * with the compiler issues, then one escalation attempt with the escalation profile when configured and within
 * budget. Every call is metered; a generation stops when the per-request budget is reached. Explanations: one call and
 * one retry for unusable output. Nothing here touches the database.
 */
@Component
public class Generator {

    private static final double TEMPERATURE = 0.2;
    private static final int EXPLAIN_MAX_TOKENS = 4000;
    private static final int MAX_ECHO_CHARS = 120_000;

    /** Metering of one generation (all attempts). */
    public static final class Meter {
        private final long startedNanos = System.nanoTime();
        private int attempts;
        private boolean escalated;
        private Long inputTokens;
        private Long outputTokens;
        private BigDecimal cost;
        private String model;
        private String profile;
        private String requestedModel;
        private String violation;

        Meter(ModelPolicy.ResolvedProfile profile) {
            use(profile);
        }

        void use(ModelPolicy.ResolvedProfile resolved) {
            this.profile = resolved.id();
            this.requestedModel = resolved.model();
        }

        void add(OpenRouterClient.ChatResult result) {
            attempts++;
            model = result.model() == null || result.model().isBlank() ? requestedModel : result.model();
            if (result.promptTokens() != null) {
                inputTokens = (inputTokens == null ? 0 : inputTokens) + result.promptTokens();
            }
            if (result.completionTokens() != null) {
                outputTokens = (outputTokens == null ? 0 : outputTokens) + result.completionTokens();
            }
            if (result.cost() != null) {
                cost = (cost == null ? BigDecimal.ZERO : cost).add(result.cost());
            }
        }

        public int attempts() {
            return attempts;
        }

        /** Why a routed model was refused by the policy (S10 section 1), or null. */
        public String violation() {
            return violation;
        }

        public boolean escalated() {
            return escalated;
        }

        public Long inputTokens() {
            return inputTokens;
        }

        public Long outputTokens() {
            return outputTokens;
        }

        /** Provider-reported cost; null when no call reported one. */
        public BigDecimal cost() {
            return cost;
        }

        public BigDecimal costOrZero() {
            return cost == null ? BigDecimal.ZERO : cost;
        }

        /** Model of the last answer (routers report the routed model), else the requested model. */
        public String model() {
            return model == null ? requestedModel : model;
        }

        public String profile() {
            return profile;
        }

        public String requestedModel() {
            return requestedModel;
        }

        public long durationMs() {
            return (System.nanoTime() - startedNanos) / 1_000_000;
        }
    }

    public enum DraftStatus {
        VALID, INVALID, NOT_A_WORKFLOW, PROVIDER_FAILURE
    }

    /**
     * @param evaluation   last evaluation (null when the provider failed before any usable reply)
     * @param issues       blockers of the last attempt for AI_OUTPUT_INVALID
     * @param budgetStop   true when the per-request or monthly budget ended the attempts
     * @param deadlineStop true when {@code orqix.ai.generation-deadline} ended the attempts
     */
    public record DraftOutcome(DraftStatus status, DraftRules.Evaluation evaluation, List<ValidationIssue> issues,
            Meter meter, ProviderException failure, boolean budgetStop, boolean deadlineStop) {
    }

    /**
     * Wall-clock budget of one generation (all attempts). A new attempt starts only when the time left is at least the
     * longest attempt so far; every call's timeout is capped by the time left.
     */
    static final class Deadline {
        private static final long MIN_ATTEMPT_NANOS = Duration.ofSeconds(1).toNanos();
        private final long endNanos;
        private long longestNanos;

        Deadline(Duration budget) {
            this.endNanos = System.nanoTime() + budget.toNanos();
        }

        Duration remaining() {
            return Duration.ofNanos(Math.max(0, endNanos - System.nanoTime()));
        }

        boolean allowsAnother() {
            long left = endNanos - System.nanoTime();
            return left >= MIN_ATTEMPT_NANOS && left >= longestNanos;
        }

        /** Per-call timeout: the configured one, or the time left when that is shorter. */
        Duration callTimeout(Duration perCall) {
            Duration left = remaining();
            return left.compareTo(perCall) < 0 ? left : perCall;
        }

        void attempted(long nanos) {
            longestNanos = Math.max(longestNanos, nanos);
        }
    }

    /** Explanation reply. {@code steps} only reference nodes of the definition. */
    public record Explanation(String summary, List<Step> steps) {
    }

    public record Step(String nodeId, String text) {
    }

    private final OpenRouterClient client;
    private final ModelPolicy policy;
    private final PromptLibrary prompts;
    private final AiProperties properties;
    private final Json json;

    public Generator(OpenRouterClient client, ModelPolicy policy, PromptLibrary prompts, AiProperties properties,
            Json json) {
        this.client = client;
        this.policy = policy;
        this.prompts = prompts;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Runs the draft conversation.
     *
     * @param budgetLeft remaining monthly budget at the start (USD)
     */
    public DraftOutcome draft(ModelPolicy.ResolvedProfile profile, String description, String name,
            DraftRules.Workspace workspace, DraftRules rules, BigDecimal budgetLeft) {
        Meter meter = new Meter(profile);
        BigDecimal perRequest = properties.budget().perRequestMaxUsd();
        String user = PromptLibrary.draftUser(rules.workspaceContext(workspace), description);
        List<OpenRouterClient.Message> messages = new ArrayList<>();
        messages.add(new OpenRouterClient.Message("system", prompts.draftSystem()));
        messages.add(new OpenRouterClient.Message("user", user));
        ModelPolicy.ResolvedProfile current = profile;
        int repairsLeft = properties.maxRepairAttempts();
        Deadline deadline = new Deadline(properties.generationDeadline());
        DraftRules.Evaluation last = null;
        List<ValidationIssue> lastIssues = List.of();
        while (true) {
            if (last != null && !deadline.allowsAnother()) {
                // Another attempt could not finish before the generation deadline.
                return new DraftOutcome(DraftStatus.INVALID, last, lastIssues, meter, null, false, true);
            }
            Duration callTimeout = deadline.callTimeout(properties.timeout());
            boolean cappedByDeadline = callTimeout.compareTo(properties.timeout()) < 0;
            long started = System.nanoTime();
            OpenRouterClient.ChatResult result;
            try {
                result = call(current, messages, true, properties.maxOutputTokens(), callTimeout);
            } catch (ProviderException e) {
                boolean deadlineHit = cappedByDeadline && e.timedOut();
                if (last != null && (deadlineHit || meter.escalated())) {
                    // The model answered before, but the time (or the extra escalation chance) ran out: report the
                    // last findings instead of the outage.
                    return new DraftOutcome(DraftStatus.INVALID, last, lastIssues, meter, e, false, deadlineHit);
                }
                return new DraftOutcome(DraftStatus.PROVIDER_FAILURE, last, lastIssues, meter, e, false, deadlineHit);
            }
            deadline.attempted(System.nanoTime() - started);
            meter.add(result);
            checkRouted(current, result, meter);
            DraftRules.Evaluation evaluation = rules.evaluate(result.content(), name, workspace);
            if (evaluation.status() == DraftRules.Status.VALID) {
                return new DraftOutcome(DraftStatus.VALID, evaluation, List.of(), meter, null, false, false);
            }
            if (evaluation.status() == DraftRules.Status.NOT_A_WORKFLOW) {
                return new DraftOutcome(DraftStatus.NOT_A_WORKFLOW, evaluation, List.of(), meter, null, false, false);
            }
            last = evaluation;
            List<ValidationIssue> issues = new ArrayList<>(evaluation.repairable());
            if ("length".equals(result.finishReason())) {
                issues.add(0, new ValidationIssue("WF_SCHEMA_INVALID", Severity.BLOCKER,
                        "The reply was cut off at the output limit. Use fewer, simpler steps and shorter texts.", "",
                        null, null));
            }
            lastIssues = List.copyOf(issues);
            if (meter.violation() != null && policy.tripped(current.id()).isPresent()) {
                // The router chose a refused model: no further attempts on a paused profile.
                return new DraftOutcome(DraftStatus.INVALID, evaluation, issues, meter, null, false, false);
            }
            boolean budgetReached = meter.costOrZero().compareTo(perRequest) >= 0
                    || meter.costOrZero().compareTo(budgetLeft) >= 0;
            if (budgetReached) {
                return new DraftOutcome(DraftStatus.INVALID, evaluation, issues, meter, null, true, false);
            }
            String repair = DraftRules.repairMessage(issues);
            String echo = result.content() == null ? "" : result.content();
            if (echo.length() > MAX_ECHO_CHARS) {
                echo = echo.substring(0, MAX_ECHO_CHARS);
            }
            if (repairsLeft > 0) {
                repairsLeft--;
                messages.add(new OpenRouterClient.Message("assistant", echo));
                messages.add(new OpenRouterClient.Message("user", repair));
                continue;
            }
            Optional<ModelPolicy.ResolvedProfile> escalation = meter.escalated() ? Optional.empty()
                    : policy.escalation(profile.id());
            if (escalation.isEmpty()) {
                return new DraftOutcome(DraftStatus.INVALID, evaluation, issues, meter, null, false, false);
            }
            // One escalation attempt: the original request, the last reply and its problems, on the stronger profile.
            current = escalation.get();
            meter.escalated = true;
            meter.use(current);
            List<OpenRouterClient.Message> escalated = new ArrayList<>(messages.subList(0, 2));
            escalated.add(new OpenRouterClient.Message("assistant", echo));
            escalated.add(new OpenRouterClient.Message("user", repair));
            messages = escalated;
        }
    }

    /** Explains a canonical definition; one retry when the reply cannot be used. */
    public Explanation explain(ModelPolicy.ResolvedProfile profile, JsonNode definition, JsonNode validation, Meter meter) {
        List<OpenRouterClient.Message> messages = new ArrayList<>();
        messages.add(new OpenRouterClient.Message("system", prompts.explainSystem()));
        messages.add(new OpenRouterClient.Message("user", prompts.explainUser(definition, validation)));
        List<String> nodeIds = new ArrayList<>();
        definition.path("nodes").forEach(n -> nodeIds.add(n.path("id").asString("")));
        Deadline deadline = new Deadline(properties.generationDeadline());
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0 && !deadline.allowsAnother()) {
                break;
            }
            long started = System.nanoTime();
            OpenRouterClient.ChatResult result = call(profile, messages, false, EXPLAIN_MAX_TOKENS,
                    deadline.callTimeout(properties.timeout()));
            deadline.attempted(System.nanoTime() - started);
            meter.add(result);
            checkRouted(profile, result, meter);
            Explanation explanation = parseExplanation(result.content(), nodeIds);
            if (explanation != null) {
                return explanation;
            }
            if (meter.costOrZero().compareTo(properties.budget().perRequestMaxUsd()) >= 0) {
                break;
            }
            messages.add(new OpenRouterClient.Message("assistant", result.content() == null ? "" : result.content()));
            messages.add(new OpenRouterClient.Message("user", "That reply cannot be used. Reply with only the JSON "
                    + "object {\"summary\": \"...\", \"steps\": [{\"nodeId\": \"...\", \"text\": \"...\"}]}."));
        }
        return null;
    }

    /**
     * Checks the model a provider reports against the policy (a router may pick any model): the violation is recorded
     * on the generation and trips the circuit breaker of the profile for the rest of the UTC day.
     */
    private void checkRouted(ModelPolicy.ResolvedProfile profile, OpenRouterClient.ChatResult result, Meter meter) {
        policy.routedViolation(profile.model(), result.model()).ifPresent(reason -> {
            if (meter.violation == null) {
                meter.violation = reason;
            }
            policy.tripBreaker(profile.id(), result.model(), reason);
        });
    }

    /** New meter for an explanation. */
    public Meter meter(ModelPolicy.ResolvedProfile profile) {
        return new Meter(profile);
    }

    private OpenRouterClient.ChatResult call(ModelPolicy.ResolvedProfile profile, List<OpenRouterClient.Message> messages,
            boolean draft, int maxTokens, Duration timeout) {
        JsonNode schema = draft ? prompts.draftEnvelopeSchema() : prompts.explanationSchema();
        String schemaName = draft ? "orqix_workflow_draft" : "orqix_workflow_explanation";
        OpenRouterClient.Format format = profile.structuredOutputs() ? OpenRouterClient.Format.JSON_SCHEMA
                : OpenRouterClient.Format.JSON_OBJECT;
        try {
            return client.chat(new OpenRouterClient.ChatRequest(profile.model(), List.copyOf(messages), format,
                    schemaName, schema, maxTokens, TEMPERATURE, timeout));
        } catch (ProviderException e) {
            if (e.kind() != ProviderException.Kind.REJECTED || format != OpenRouterClient.Format.JSON_SCHEMA) {
                throw e;
            }
            // Structured outputs refused by the routed provider (not billed): fall back to plain JSON mode once.
            return client.chat(new OpenRouterClient.ChatRequest(profile.model(), List.copyOf(messages),
                    OpenRouterClient.Format.JSON_OBJECT, null, null, maxTokens, TEMPERATURE, timeout));
        }
    }

    private Explanation parseExplanation(String content, List<String> nodeIds) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode reply;
        try {
            reply = json.parse(text.substring(start, end + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String summary = reply.path("summary").asString("").replaceAll("\\s+", " ").trim();
        if (summary.isEmpty()) {
            return null;
        }
        if (summary.length() > 1500) {
            summary = summary.substring(0, 1499).trim() + "…";
        }
        List<Step> steps = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode step : reply.path("steps")) {
            String nodeId = step.path("nodeId").asString(null);
            String stepText = step.path("text").asString("").replaceAll("\\s+", " ").trim();
            if (nodeId == null || !nodeIds.contains(nodeId) || stepText.isEmpty() || !seen.add(nodeId)) {
                continue;
            }
            steps.add(new Step(nodeId, stepText.length() > 500 ? stepText.substring(0, 499).trim() + "…" : stepText));
        }
        return new Explanation(summary, List.copyOf(steps));
    }
}
