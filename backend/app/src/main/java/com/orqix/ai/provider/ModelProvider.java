package com.orqix.ai.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Model provider SPI (CG-A04, docs/build/S134 section 1). One implementation per wire protocol: OpenAI-compatible
 * (OpenRouter, vLLM, Ollama, LM Studio), native Anthropic Messages, AWS Bedrock Converse, Azure OpenAI and Google
 * Vertex/Gemini. Callers never pick a provider or a raw model id: they resolve a profile through
 * {@link ProviderRegistry}, which applies the profile and price policy, the workspace residency rule and the budget,
 * and then calls {@link #complete}. Implementations never log or put request or response text into exceptions; failures
 * are {@link com.orqix.ai.internal.ProviderException}s classified by HTTP status.
 */
public interface ModelProvider {

    /** Wire protocol this implementation speaks (the {@code kind} of an endpoint). */
    Kind kind();

    /**
     * One completion.
     *
     * @param endpoint   the configured endpoint (address, region, API version, deployments)
     * @param credential the resolved secret of the endpoint, or null when it needs none (a local Ollama)
     */
    ModelResponse complete(ProviderProperties.Endpoint endpoint, String credential, ModelRequest request);

    /** Wire protocols. */
    enum Kind {
        OPENAI_COMPATIBLE("openaiCompatible"), ANTHROPIC("anthropic"), BEDROCK("bedrock"), AZURE_OPENAI("azureOpenai"),
        VERTEX("vertex");

        private final String json;

        Kind(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }

        public static Kind of(String value) {
            for (Kind k : values()) {
                if (k.json.equalsIgnoreCase(value) || k.name().equalsIgnoreCase(value)) {
                    return k;
                }
            }
            throw new IllegalArgumentException("Unknown model provider kind " + value);
        }
    }

    /** One turn of text ({@code user} or {@code assistant}); the system text travels separately. */
    record Turn(String role, String content) {
    }

    /**
     * @param system      fixed instructions (cached where the provider supports prompt caching)
     * @param schema      JSON schema of the answer; null asks for free text
     * @param temperature null for the provider default; ignored where the API refuses sampling settings (Anthropic)
     */
    record ModelRequest(String model, String system, List<Turn> messages, String schemaName, JsonNode schema,
            int maxTokens, Double temperature, Duration timeout) {

        public ModelRequest {
            messages = List.copyOf(messages);
        }
    }

    /**
     * @param model                the model that answered (routers report the routed model)
     * @param costUsd              provider-reported cost; null when the provider does not report one
     * @param schemaEnforced       true when the provider was asked to enforce {@code schema} strictly
     * @param meanTokenProbability mean probability of the answer tokens when the provider returned log-probabilities
     */
    record ModelResponse(String model, String content, String finishReason, Long inputTokens, Long outputTokens,
            Long cachedInputTokens, BigDecimal costUsd, boolean schemaEnforced, Double meanTokenProbability) {
    }
}
