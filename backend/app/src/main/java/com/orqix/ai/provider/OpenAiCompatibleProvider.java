package com.orqix.ai.provider;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.orqix.ai.AiProperties;
import com.orqix.ai.internal.ProviderException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI Chat Completions protocol ({@code POST {baseUrl}/chat/completions}): OpenRouter (the deployment default),
 * vLLM, Ollama ({@code /v1}) and LM Studio. Strict JSON-schema output ({@code response_format.json_schema.strict})
 * when the endpoint allows it. For the built-in OpenRouter endpoint every request also carries
 * {@code provider.max_price} and usage accounting, exactly like the S10 client.
 */
@Component
public class OpenAiCompatibleProvider implements ModelProvider {

    /** Id of the endpoint synthesized from {@code orqix.ai.api-key} / {@code base-url}. */
    public static final String OPENROUTER = "openrouter";

    private final ProviderHttp http;
    private final AiProperties ai;

    public OpenAiCompatibleProvider(ProviderHttp http, AiProperties ai) {
        this.http = http;
        this.ai = ai;
    }

    @Override
    public Kind kind() {
        return Kind.OPENAI_COMPATIBLE;
    }

    @Override
    public ModelResponse complete(ProviderProperties.Endpoint endpoint, String credential, ModelRequest request) {
        if (endpoint.baseUrl() == null) {
            throw new ProviderException(ProviderException.Kind.REJECTED, 0, null, "openaiCompatible: no base URL");
        }
        ObjectNode body = chatBody(http, endpoint, request);
        body.put("model", request.model());
        boolean openRouter = isOpenRouter(endpoint);
        if (openRouter) {
            ObjectNode provider = body.putObject("provider");
            if (!ai.providerSort().isEmpty()) {
                provider.put("sort", ai.providerSort());
            }
            provider.putObject("max_price").put("prompt", ai.maxPrice().promptPerMillion())
                    .put("completion", ai.maxPrice().completionPerMillion());
            body.putObject("usage").put("include", true);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (credential != null && !credential.isEmpty()) {
            headers.put("Authorization", "Bearer " + credential);
        }
        if (openRouter) {
            headers.put("X-Title", ai.appTitle());
        }
        JsonNode response = http.post(ProviderHttp.join(endpoint.baseUrl(), "/chat/completions"), headers,
                http.bytes(body), min(endpoint, request), "chat " + request.model());
        return parse(response, request, endpoint.strictSchema() && request.schema() != null);
    }

    private boolean isOpenRouter(ProviderProperties.Endpoint endpoint) {
        return endpoint.baseUrl() != null && endpoint.baseUrl().equals(ai.baseUrl());
    }

    /** Messages, limits and the response format shared by OpenAI-compatible and Azure OpenAI. */
    static ObjectNode chatBody(ProviderHttp http, ProviderProperties.Endpoint endpoint, ModelRequest request) {
        ObjectNode body = http.json().object();
        ArrayNode messages = body.putArray("messages");
        if (request.system() != null && !request.system().isEmpty()) {
            messages.addObject().put("role", "system").put("content", request.system());
        }
        request.messages().forEach(t -> messages.addObject().put("role", t.role()).put("content", t.content()));
        body.put("max_tokens", request.maxTokens());
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.schema() != null) {
            ObjectNode format = body.putObject("response_format");
            format.put("type", "json_schema");
            ObjectNode schema = format.putObject("json_schema");
            schema.put("name", request.schemaName() == null ? "result" : request.schemaName());
            schema.put("strict", endpoint.strictSchema());
            schema.set("schema", request.schema());
        }
        if (endpoint.logprobs()) {
            body.put("logprobs", true);
        }
        return body;
    }

    static java.time.Duration min(ProviderProperties.Endpoint endpoint, ModelRequest request) {
        return request.timeout() == null || request.timeout().compareTo(endpoint.timeout()) > 0 ? endpoint.timeout()
                : request.timeout();
    }

    static ModelResponse parse(JsonNode response, ModelRequest request, boolean enforced) {
        JsonNode error = response.path("error");
        if (error.isObject()) {
            int code = error.path("code").asInt(0);
            throw ProviderHttp.classify(code == 0 ? 502 : code, null, "provider error object (code " + code + ")");
        }
        JsonNode choice = response.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 502, null, "response without choices");
        }
        Double probability = null;
        JsonNode tokens = choice.path("logprobs").path("content");
        if (tokens.isArray() && !tokens.isEmpty()) {
            double sum = 0;
            for (JsonNode token : tokens) {
                sum += Math.exp(token.path("logprob").asDouble(0));
            }
            probability = sum / tokens.size();
        }
        JsonNode usage = response.path("usage");
        return new ModelResponse(response.path("model").asString(request.model()),
                choice.path("message").path("content").asString(null), choice.path("finish_reason").asString(null),
                ProviderHttp.longOrNull(usage.path("prompt_tokens")),
                ProviderHttp.longOrNull(usage.path("completion_tokens")),
                ProviderHttp.longOrNull(usage.path("prompt_tokens_details").path("cached_tokens")),
                usage.path("cost").isNumber() ? usage.path("cost").decimalValue() : null, enforced, probability);
    }
}
