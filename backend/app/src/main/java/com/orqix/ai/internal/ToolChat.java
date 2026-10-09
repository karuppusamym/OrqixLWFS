package com.orqix.ai.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.orqix.ai.AiProperties;
import com.orqix.integration.AgentModelPort;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One OpenRouter {@code /chat/completions} call with tool support, for agent steps (S13 section 4). The existing
 * {@link OpenRouterClient} sends text-only messages, so this class builds the body itself (OpenAI-format messages with
 * {@code tool_calls} and {@code tool} turns) but keeps every policy of that client: {@code provider.max_price}, usage
 * accounting, per-call timeout, redirects never followed, status classification through
 * {@link OpenRouterClient#classify}, and no request or response text in logs or exceptions.
 */
@Component
public class ToolChat {

    private static final Logger log = LoggerFactory.getLogger(ToolChat.class);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final AiProperties properties;
    private final Json json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ToolChat(AiProperties properties, Json json) {
        this.properties = properties;
        this.json = json;
    }

    /** @throws ProviderException typed provider failure (never carries bodies) */
    public AgentModelPort.Completion complete(AgentModelPort.CompletionRequest request) {
        ObjectNode body = json.object();
        body.put("model", request.model());
        body.set("messages", request.messages());
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", 0);
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.set("tools", request.tools());
            body.put("tool_choice", "auto");
        }
        if (request.responseSchema() != null) {
            ObjectNode format = body.putObject("response_format");
            format.put("type", "json_schema");
            ObjectNode schema = format.putObject("json_schema");
            schema.put("name", "result");
            schema.put("strict", false);
            schema.set("schema", request.responseSchema());
        }
        ObjectNode provider = body.putObject("provider");
        if (!properties.providerSort().isEmpty()) {
            provider.put("sort", properties.providerSort());
        }
        ObjectNode maxPrice = provider.putObject("max_price");
        maxPrice.put("prompt", properties.maxPrice().promptPerMillion());
        maxPrice.put("completion", properties.maxPrice().completionPerMillion());
        body.putObject("usage").put("include", true);

        String base = properties.baseUrl().toString();
        HttpRequest httpRequest = HttpRequest
                .newBuilder(URI.create(base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions"))
                .timeout(request.timeout() == null ? properties.timeout() : request.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Title", properties.appTitle())
                .POST(HttpRequest.BodyPublishers.ofString(json.write(body), StandardCharsets.UTF_8))
                .build();
        JsonNode response = send(httpRequest);
        JsonNode error = response.path("error");
        if (error.isObject()) {
            int code = error.path("code").asInt(0);
            throw OpenRouterClient.classify(code == 0 ? 502 : code, null, "provider error object (code " + code + ")");
        }
        JsonNode choice = response.path("choices").path(0);
        if (choice.isMissingNode() || choice.path("error").isObject()) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 502, null, "response without a usable choice");
        }
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 502, null, "response without a message");
        }
        JsonNode usage = response.path("usage");
        return new AgentModelPort.Completion((ObjectNode) message, choice.path("finish_reason").asString(null),
                response.path("model").asString(request.model()), longOrNull(usage.path("prompt_tokens")),
                longOrNull(usage.path("completion_tokens")),
                usage.path("cost").isNumber() ? usage.path("cost").decimalValue() : null);
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 0, null, "agent chat: timeout");
        } catch (IOException e) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 0, null,
                    "agent chat: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 0, null, "agent chat: interrupted");
        }
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            Integer retryAfter = response.headers().firstValue("Retry-After").map(ToolChat::seconds).orElse(null);
            ProviderException failure = OpenRouterClient.classify(status, retryAfter, "agent chat: HTTP " + status);
            log.warn("AI provider call failed: {} (kind {})", failure.getMessage(), failure.kind());
            throw failure;
        }
        byte[] bytes = response.body();
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, status, null, "agent chat: response too large");
        }
        try {
            return json.parse(bytes);
        } catch (IllegalArgumentException e) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, status, null, "agent chat: unreadable response");
        }
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
}
