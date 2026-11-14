package com.orqix.ai.provider;

import java.net.URI;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.orqix.ai.internal.ProviderException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Native Anthropic Messages API ({@code POST {baseUrl}/v1/messages}, API version {@code 2026-10-21}). The fixed
 * instructions go in a system block with {@code cache_control} (prompt caching of long skill instructions); strict
 * output uses {@code output_config.format} with a JSON schema. Sampling settings are not sent: this API version refuses
 * {@code temperature}. A {@code refusal} stop reason is reported as a refused request.
 */
@Component
public class AnthropicProvider implements ModelProvider {

    static final URI DEFAULT_BASE = URI.create("https://api.anthropic.com");
    static final String VERSION = "2026-10-21";

    private final ProviderHttp http;

    public AnthropicProvider(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public Kind kind() {
        return Kind.ANTHROPIC;
    }

    @Override
    public ModelResponse complete(ProviderProperties.Endpoint endpoint, String credential, ModelRequest request) {
        ObjectNode body = http.json().object();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        if (request.system() != null && !request.system().isEmpty()) {
            ArrayNode system = body.putArray("system");
            system.addObject().put("type", "text").put("text", request.system()).putObject("cache_control")
                    .put("type", "ephemeral");
        }
        ArrayNode messages = body.putArray("messages");
        request.messages().forEach(t -> messages.addObject().put("role", t.role()).put("content", t.content()));
        boolean strict = request.schema() != null && endpoint.strictSchema();
        if (strict) {
            body.putObject("output_config").putObject("format").put("type", "json_schema").set("schema",
                    request.schema());
        }
        URI base = endpoint.baseUrl() == null ? DEFAULT_BASE : endpoint.baseUrl();
        JsonNode response = http.post(ProviderHttp.join(base, "/v1/messages"),
                Map.of("x-api-key", credential == null ? "" : credential, "anthropic-version", VERSION),
                http.bytes(body), OpenAiCompatibleProvider.min(endpoint, request), "messages " + request.model());
        String stop = response.path("stop_reason").asString(null);
        if ("refusal".equals(stop)) {
            throw new ProviderException(ProviderException.Kind.REJECTED, 200, null, "messages: refusal");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asString(""))) {
                text.append(block.path("text").asString(""));
            }
        }
        JsonNode usage = response.path("usage");
        return new ModelResponse(response.path("model").asString(request.model()), text.toString(), stop,
                ProviderHttp.longOrNull(usage.path("input_tokens")), ProviderHttp.longOrNull(usage.path("output_tokens")),
                ProviderHttp.longOrNull(usage.path("cache_read_input_tokens")), null, strict, null);
    }
}
