package com.orqix.ai.provider;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.orqix.ai.internal.ProviderException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Azure OpenAI / Foundry deployments: {@code POST {baseUrl}/openai/deployments/{deployment}/chat/completions
 * ?api-version=...} with the {@code api-key} header (or a bearer Entra token when the secret starts with
 * {@code Bearer }). Same body and strict JSON-schema output as the OpenAI protocol.
 */
@Component
public class AzureOpenAiProvider implements ModelProvider {

    private final ProviderHttp http;

    public AzureOpenAiProvider(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public Kind kind() {
        return Kind.AZURE_OPENAI;
    }

    @Override
    public ModelResponse complete(ProviderProperties.Endpoint endpoint, String credential, ModelRequest request) {
        if (endpoint.baseUrl() == null) {
            throw new ProviderException(ProviderException.Kind.REJECTED, 0, null, "azureOpenai: no base URL");
        }
        ObjectNode body = OpenAiCompatibleProvider.chatBody(http, endpoint, request);
        String deployment = endpoint.deployments().getOrDefault(request.model(), request.model());
        URI uri = ProviderHttp.join(endpoint.baseUrl(), "/openai/deployments/"
                + URLEncoder.encode(deployment, StandardCharsets.UTF_8) + "/chat/completions?api-version="
                + URLEncoder.encode(endpoint.apiVersion(), StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        String secret = credential == null ? "" : credential.trim();
        if (secret.startsWith("Bearer ")) {
            headers.put("Authorization", secret);
        } else if (!secret.isEmpty()) {
            headers.put("api-key", secret);
        }
        JsonNode response = http.post(uri, headers, http.bytes(body), OpenAiCompatibleProvider.min(endpoint, request),
                "azure " + deployment);
        return OpenAiCompatibleProvider.parse(response, request, endpoint.strictSchema() && request.schema() != null);
    }
}
