package com.orqix.ai.provider;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.orqix.ai.internal.ProviderException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AWS Bedrock Converse API ({@code POST /model/{modelId}/converse} on {@code bedrock-runtime.{region}.amazonaws.com}).
 * Credentials: a Bedrock API key (bearer) or IAM keys as a JSON secret, signed with Signature V4 (service
 * {@code bedrock}). Strict output is a forced tool whose input schema is the answer schema; the tool input is the answer.
 */
@Component
public class BedrockProvider implements ModelProvider {

    static final String TOOL = "answer";

    private final ProviderHttp http;
    private final Clock clock;

    public BedrockProvider(ProviderHttp http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    @Override
    public Kind kind() {
        return Kind.BEDROCK;
    }

    @Override
    public ModelResponse complete(ProviderProperties.Endpoint endpoint, String credential, ModelRequest request) {
        if (endpoint.region().isEmpty() && endpoint.baseUrl() == null) {
            throw new ProviderException(ProviderException.Kind.REJECTED, 0, null, "bedrock: no region");
        }
        ObjectNode body = http.json().object();
        if (request.system() != null && !request.system().isEmpty()) {
            body.putArray("system").addObject().put("text", request.system());
        }
        ArrayNode messages = body.putArray("messages");
        request.messages().forEach(t -> messages.addObject().put("role", t.role()).putArray("content").addObject()
                .put("text", t.content()));
        ObjectNode inference = body.putObject("inferenceConfig");
        inference.put("maxTokens", request.maxTokens());
        if (request.temperature() != null) {
            inference.put("temperature", request.temperature());
        }
        boolean strict = request.schema() != null && endpoint.strictSchema();
        if (strict) {
            ObjectNode config = body.putObject("toolConfig");
            ObjectNode spec = config.putArray("tools").addObject().putObject("toolSpec");
            spec.put("name", TOOL).put("description", "Return the answer.");
            spec.putObject("inputSchema").set("json", request.schema());
            config.putObject("toolChoice").putObject("tool").put("name", TOOL);
        }
        URI base = endpoint.baseUrl() != null ? endpoint.baseUrl()
                : URI.create("https://bedrock-runtime." + endpoint.region() + ".amazonaws.com");
        String encodedModel = SigV4.encode(request.model());
        String path = "/model/" + encodedModel + "/converse";
        byte[] bytes = http.bytes(body);
        Map<String, String> headers = new LinkedHashMap<>();
        String secret = credential == null ? "" : credential.trim();
        if (secret.startsWith("{")) {
            JsonNode keys = http.json().parse(secret);
            SigV4.Credentials creds = new SigV4.Credentials(keys.path("accessKeyId").asString(""),
                    keys.path("secretAccessKey").asString(""), keys.path("sessionToken").asString(null));
            Instant now = clock.instant();
            Map<String, String> signed = new LinkedHashMap<>();
            String host = base.getHost() + (base.getPort() > 0 ? ":" + base.getPort() : "");
            signed.put("host", host);
            signed.put("x-amz-date", SigV4.AMZ_DATE.format(now));
            signed.put("content-type", "application/json");
            if (creds.sessionToken() != null && !creds.sessionToken().isEmpty()) {
                signed.put("x-amz-security-token", creds.sessionToken());
            }
            // Non-S3 services sign each path segment encoded twice.
            String canonical = "/model/" + SigV4.encode(encodedModel) + "/converse";
            String region = endpoint.region().isEmpty() ? "us-east-1" : endpoint.region();
            headers.put("Authorization", SigV4.authorization(creds, region, "bedrock", "POST", canonical, "", signed,
                    bytes, now));
            headers.put("x-amz-date", signed.get("x-amz-date"));
            if (signed.containsKey("x-amz-security-token")) {
                headers.put("x-amz-security-token", signed.get("x-amz-security-token"));
            }
        } else if (!secret.isEmpty()) {
            headers.put("Authorization", "Bearer " + secret);
        }
        JsonNode response = http.post(ProviderHttp.join(base, path), headers, bytes,
                OpenAiCompatibleProvider.min(endpoint, request), "converse " + request.model());
        String content = null;
        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("output").path("message").path("content")) {
            if (block.has("toolUse") && TOOL.equals(block.path("toolUse").path("name").asString(""))) {
                content = http.json().write(block.path("toolUse").path("input"));
            } else if (block.has("text")) {
                text.append(block.path("text").asString(""));
            }
        }
        JsonNode usage = response.path("usage");
        return new ModelResponse(request.model(), content != null ? content : text.toString(),
                response.path("stopReason").asString(null), ProviderHttp.longOrNull(usage.path("inputTokens")),
                ProviderHttp.longOrNull(usage.path("outputTokens")),
                ProviderHttp.longOrNull(usage.path("cacheReadInputTokens")), null, strict, null);
    }
}
