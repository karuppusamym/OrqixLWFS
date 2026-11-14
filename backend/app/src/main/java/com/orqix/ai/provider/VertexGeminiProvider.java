package com.orqix.ai.provider;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Gemini {@code generateContent}. With a {@code project} it calls Vertex AI
 * ({@code {location}-aiplatform.googleapis.com/v1/projects/{p}/locations/{l}/publishers/google/models/{m}}) with the
 * secret as a bearer access token; without one it calls the Gemini API with the secret as {@code x-goog-api-key}.
 * Strict output uses {@code responseMimeType: application/json} with {@code responseJsonSchema}.
 */
@Component
public class VertexGeminiProvider implements ModelProvider {

    static final URI GEMINI_API = URI.create("https://generativelanguage.googleapis.com");

    private final ProviderHttp http;

    public VertexGeminiProvider(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public Kind kind() {
        return Kind.VERTEX;
    }

    @Override
    public ModelResponse complete(ProviderProperties.Endpoint endpoint, String credential, ModelRequest request) {
        ObjectNode body = http.json().object();
        if (request.system() != null && !request.system().isEmpty()) {
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.system());
        }
        ArrayNode contents = body.putArray("contents");
        request.messages().forEach(t -> contents.addObject().put("role", "assistant".equals(t.role()) ? "model" : "user")
                .putArray("parts").addObject().put("text", t.content()));
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("maxOutputTokens", request.maxTokens());
        if (request.temperature() != null) {
            generation.put("temperature", request.temperature());
        }
        boolean strict = request.schema() != null && endpoint.strictSchema();
        if (request.schema() != null) {
            generation.put("responseMimeType", "application/json");
            if (strict) {
                generation.set("responseJsonSchema", request.schema());
            }
        }
        String model = URLEncoder.encode(request.model(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        String secret = credential == null ? "" : credential.trim();
        URI uri;
        if (!endpoint.project().isEmpty()) {
            URI base = endpoint.baseUrl() != null ? endpoint.baseUrl()
                    : URI.create("https://" + endpoint.location() + "-aiplatform.googleapis.com");
            uri = ProviderHttp.join(base, "/v1/projects/" + URLEncoder.encode(endpoint.project(), StandardCharsets.UTF_8)
                    + "/locations/" + URLEncoder.encode(endpoint.location(), StandardCharsets.UTF_8)
                    + "/publishers/google/models/" + model + ":generateContent");
            if (!secret.isEmpty()) {
                headers.put("Authorization", "Bearer " + secret);
            }
        } else {
            uri = ProviderHttp.join(endpoint.baseUrl() != null ? endpoint.baseUrl() : GEMINI_API,
                    "/v1beta/models/" + model + ":generateContent");
            if (!secret.isEmpty()) {
                headers.put("x-goog-api-key", secret);
            }
        }
        JsonNode response = http.post(uri, headers, http.bytes(body), OpenAiCompatibleProvider.min(endpoint, request),
                "generateContent " + request.model());
        JsonNode candidate = response.path("candidates").path(0);
        StringBuilder text = new StringBuilder();
        candidate.path("content").path("parts").forEach(p -> text.append(p.path("text").asString("")));
        JsonNode usage = response.path("usageMetadata");
        return new ModelResponse(response.path("modelVersion").asString(request.model()), text.toString(),
                candidate.path("finishReason").asString(null), ProviderHttp.longOrNull(usage.path("promptTokenCount")),
                ProviderHttp.longOrNull(usage.path("candidatesTokenCount")),
                ProviderHttp.longOrNull(usage.path("cachedContentTokenCount")), null, strict, null);
    }
}
