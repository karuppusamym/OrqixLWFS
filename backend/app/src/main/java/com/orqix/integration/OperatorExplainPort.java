package com.orqix.integration;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;

/**
 * One model call of "Explain this request" (S146 part C, CG-A07), implemented by the AI module so the runtime never
 * depends on it (the same pattern as {@link AiTaskPort}). The implementation resolves the profile through the provider
 * SPI under the workspace residency rule, checks the monthly budget, calls with temperature 0 and the strict schema,
 * and meters one {@code ai_generation} row of kind {@code aiExplain}. It never throws for policy or provider problems.
 */
public interface OperatorExplainPort {

    /**
     * @param profile  AI profile id; null or blank uses the deployment default
     * @param usable   tells the implementation whether the answer was usable, for the metering outcome
     */
    record Request(UUID workspaceId, UUID subjectId, UUID workflowId, String profile, String promptVersion,
            String system, String user, String schemaName, JsonNode schema, int maxTokens, Duration timeout,
            Predicate<String> usable) {
    }

    /**
     * @param content           the answer text, or null when no answer was obtained
     * @param unavailableReason notConfigured, residency, budget or unavailable when {@code content} is null
     */
    record Result(String content, String profile, String model, String unavailableReason) {

        public static Result unavailable(String reason) {
            return new Result(null, null, null, reason);
        }
    }

    Result complete(Request request);
}
