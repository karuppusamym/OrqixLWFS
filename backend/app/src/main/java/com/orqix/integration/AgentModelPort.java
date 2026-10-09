package com.orqix.integration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the agent executor needs from the AI module (S13 section 4). The integration module owns this port and the
 * AI module implements it, because the allowed dependency direction is ai to integration. The implementation applies
 * the AI settings: model profiles and price caps, the provider key, the monthly and per-request budgets, and metering.
 * No method logs or returns prompts, tool payloads or credentials.
 */
public interface AgentModelPort {

    /** Null when agents can run; otherwise a business-readable reason (for example "AI is not configured..."). */
    String unavailableReason();

    /**
     * Resolves a model profile ({@code null} = the deployment default).
     *
     * @throws ModelNotAllowedException when the profile is unknown, excluded or over the price caps
     */
    ResolvedModel resolve(String profileId);

    /** USD still spendable by one execution: min(monthly budget left, per-request maximum). Zero or less: exhausted. */
    BigDecimal budgetLeftUsd();

    /**
     * One chat completion with tool support.
     *
     * @throws ModelException on provider failures
     */
    Completion complete(CompletionRequest request);

    /** Meters one execution (kind {@code agent}). Commits independently of the caller's transaction. */
    void record(UsageRecord usage);

    record ResolvedModel(String profile, String model) {
    }

    /**
     * @param messages       OpenAI-format chat messages (system, user, assistant with tool_calls, tool)
     * @param tools          OpenAI function definitions; null or empty offers no tools
     * @param responseSchema JSON schema of a structured answer ({@code response_format}); null for free output
     * @param timeout        per call limit (already bounded by the execution deadline)
     */
    record CompletionRequest(String model, ArrayNode messages, ArrayNode tools, JsonNode responseSchema, int maxTokens,
            Duration timeout) {
    }

    /** @param message assistant message as returned by the provider (content and/or tool_calls) */
    record Completion(ObjectNode message, String finishReason, String model, Long promptTokens, Long completionTokens,
            BigDecimal costUsd) {
    }

    /** @param outcome one of success, invalidOutput, providerError, credentialsRejected, modelNotAllowed */
    record UsageRecord(UUID workspaceId, UUID subjectId, UUID workflowId, String profile, String model,
            String requestedModel, String promptVersion, int turns, Long inputTokens, Long outputTokens,
            BigDecimal costUsd, long durationMs, String outcome) {
    }

    /** Provider failure classes the executor distinguishes. */
    enum FailureKind {
        /** Timeout, connection failure, 429 or 5xx: retry later. */
        UNAVAILABLE,
        /** 401 or 403: the key is wrong; an administrator must fix it. */
        CREDENTIALS_REJECTED,
        /** 402: the provider account has no credit. */
        PAYMENT_REQUIRED,
        /** 400, 404 or 422: the request was refused (model or schema not supported). */
        REJECTED
    }

    class ModelException extends RuntimeException {

        private final transient FailureKind kind;
        private final transient Integer retryAfterSeconds;

        public ModelException(FailureKind kind, Integer retryAfterSeconds, String message) {
            super(message);
            this.kind = kind;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public FailureKind kind() {
            return kind;
        }

        public Integer retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    class ModelNotAllowedException extends RuntimeException {

        public ModelNotAllowedException(String message) {
            super(message);
        }
    }
}
