package com.orqix.ai.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.orqix.ai.AiProperties;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenRouter adapter over its OpenAI-compatible Chat Completions API (S10 section 1): JDK {@link HttpClient}, Jackson 3,
 * per-call timeout, typed {@link ProviderException}s. Every chat request carries {@code provider.max_price} and asks
 * for usage accounting. Request and response bodies are never logged.
 */
@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** One chat message (text content only). */
    public record Message(String role, String content) {
    }

    /** Requested output format. */
    public enum Format {
        JSON_SCHEMA, JSON_OBJECT, TEXT
    }

    /**
     * A chat completion request.
     *
     * @param schemaName JSON-schema name when {@code format} is JSON_SCHEMA
     * @param schema     JSON schema of the expected output when {@code format} is JSON_SCHEMA
     * @param timeout    per-call timeout; null uses {@code orqix.ai.timeout}
     */
    public record ChatRequest(String model, List<Message> messages, Format format, String schemaName, JsonNode schema,
            int maxTokens, Double temperature, Duration timeout) {
    }

    /**
     * Parsed completion.
     *
     * @param model    the model that answered (routers report the routed model)
     * @param cost     provider-reported cost in USD credits, null when absent
     */
    public record ChatResult(String id, String model, String provider, String content, String finishReason,
            Long promptTokens, Long completionTokens, BigDecimal cost) {
    }

    /** Model catalog entry from {@code GET /models}. */
    public record ModelInfo(String id, BigDecimal promptPrice, BigDecimal completionPrice, Set<String> supportedParameters,
            Set<String> inputModalities) {

        /** Routers report {@code -1}: the price depends on the routed model. */
        public boolean variablePrice() {
            return promptPrice == null || completionPrice == null || promptPrice.signum() < 0
                    || completionPrice.signum() < 0;
        }
    }

    private final AiProperties properties;
    private final Json json;
    private final HttpClient http;

    public OpenRouterClient(AiProperties properties, Json json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** {@code POST /chat/completions}. */
    public ChatResult chat(ChatRequest request) {
        ObjectNode body = json.object();
        body.put("model", request.model());
        ArrayNode messages = body.putArray("messages");
        for (Message message : request.messages()) {
            messages.addObject().put("role", message.role()).put("content", message.content());
        }
        body.put("max_tokens", request.maxTokens());
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        switch (request.format()) {
            case JSON_SCHEMA -> {
                ObjectNode format = body.putObject("response_format");
                format.put("type", "json_schema");
                ObjectNode schema = format.putObject("json_schema");
                schema.put("name", request.schemaName());
                schema.put("strict", false);
                schema.set("schema", request.schema());
            }
            case JSON_OBJECT -> body.putObject("response_format").put("type", "json_object");
            case TEXT -> {
                // plain text
            }
        }
        ObjectNode provider = body.putObject("provider");
        if (!properties.providerSort().isEmpty()) {
            provider.put("sort", properties.providerSort());
        }
        ObjectNode maxPrice = provider.putObject("max_price");
        maxPrice.put("prompt", properties.maxPrice().promptPerMillion());
        maxPrice.put("completion", properties.maxPrice().completionPerMillion());
        // Usage accounting (usage.cost); OpenRouter now always includes it, the flag is kept for older deployments.
        body.putObject("usage").put("include", true);

        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint("chat/completions"))
                .timeout(request.timeout() == null ? properties.timeout() : request.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Title", properties.appTitle())
                .POST(HttpRequest.BodyPublishers.ofString(json.write(body), StandardCharsets.UTF_8))
                .build();
        JsonNode response = send(httpRequest, "chat " + request.model());
        JsonNode error = response.path("error");
        if (error.isObject()) {
            int code = error.path("code").asInt(0);
            throw classify(code == 0 ? 502 : code, null, "provider error object (code " + code + ")");
        }
        JsonNode choice = response.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 502, null, "response without choices");
        }
        JsonNode choiceError = choice.path("error");
        if (choiceError.isObject()) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 502, null,
                    "choice error (code " + choiceError.path("code").asInt(0) + ")");
        }
        JsonNode usage = response.path("usage");
        return new ChatResult(response.path("id").asString(null), response.path("model").asString(request.model()),
                response.path("provider").asString(null), choice.path("message").path("content").asString(null),
                choice.path("finish_reason").asString(null), longOrNull(usage.path("prompt_tokens")),
                longOrNull(usage.path("completion_tokens")),
                usage.path("cost").isNumber() ? usage.path("cost").decimalValue() : null);
    }

    /** {@code GET /models}: pricing and capabilities (public endpoint; the key is still sent). */
    public List<ModelInfo> models() {
        HttpRequest request = HttpRequest.newBuilder(endpoint("models"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Accept", "application/json")
                .GET()
                .build();
        JsonNode response = send(request, "models");
        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode model : response.path("data")) {
            Set<String> parameters = new java.util.HashSet<>();
            model.path("supported_parameters").forEach(p -> parameters.add(p.asString("")));
            Set<String> modalities = new java.util.HashSet<>();
            model.path("architecture").path("input_modalities").forEach(p -> modalities.add(p.asString("")));
            models.add(new ModelInfo(model.path("id").asString(""), price(model.path("pricing").path("prompt")),
                    price(model.path("pricing").path("completion")), Set.copyOf(parameters), Set.copyOf(modalities)));
        }
        return models;
    }

    /** {@code GET /key}: verifies the credentials without spending anything. */
    public void verifyKey() {
        HttpRequest request = HttpRequest.newBuilder(endpoint("key"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Accept", "application/json")
                .GET()
                .build();
        send(request, "key");
    }

    private JsonNode send(HttpRequest request, String what) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 0, null, what + ": timeout");
        } catch (IOException e) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 0, null,
                    what + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 0, null, what + ": interrupted");
        }
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            Integer retryAfter = response.headers().firstValue("Retry-After").map(OpenRouterClient::seconds).orElse(null);
            ProviderException failure = classify(status, retryAfter, what + ": HTTP " + status);
            log.warn("AI provider call failed: {} (kind {})", failure.getMessage(), failure.kind());
            throw failure;
        }
        byte[] bytes = response.body();
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, status, null, what + ": response too large");
        }
        try {
            return json.parse(bytes);
        } catch (IllegalArgumentException e) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, status, null, what + ": unreadable response");
        }
    }

    static ProviderException classify(int status, Integer retryAfter, String message) {
        ProviderException.Kind kind;
        if (status == 401 || status == 403) {
            kind = ProviderException.Kind.CREDENTIALS_REJECTED;
        } else if (status == 402) {
            kind = ProviderException.Kind.PAYMENT_REQUIRED;
        } else if (status == 400 || status == 404 || status == 422) {
            kind = ProviderException.Kind.REJECTED;
        } else {
            kind = ProviderException.Kind.UNAVAILABLE;
        }
        return new ProviderException(kind, status, retryAfter, message);
    }

    private URI endpoint(String path) {
        String base = properties.baseUrl().toString();
        return URI.create(base.endsWith("/") ? base + path : base + "/" + path);
    }

    private static Integer seconds(String value) {
        try {
            int seconds = Integer.parseInt(value.trim());
            return seconds >= 0 ? Math.min(seconds, 3600) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOrNull(JsonNode node) {
        return node.isIntegralNumber() ? node.asLong() : null;
    }

    private static BigDecimal price(JsonNode node) {
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            String text = node.asString(null);
            return text == null || text.isBlank() ? null : new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
