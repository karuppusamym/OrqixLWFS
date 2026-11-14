package com.orqix.ai.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Model endpoints and the profiles that use them (CG-A04, docs/build/S134 section 1), prefix
 * {@code orqix.ai.model-providers}. Deployment-owned, like every AI setting. The profiles of {@code orqix.ai.profiles}
 * keep using the OpenRouter endpoint ({@code orqix.ai.api-key}, {@code base-url}) unchanged; the profiles declared here
 * name one of these endpoints and declare their prices, which the price caps check (no catalog exists for most
 * providers). Credentials are S132 secret references, never values.
 *
 * @param endpoints by id
 * @param profiles  extra profiles by id (an id also present in {@code orqix.ai.profiles} wins over these)
 * @param openRouterResidency residency tags of the built-in OpenRouter endpoint (default none: it then fails every
 *                  residency rule, because its routing is not bound to a region)
 */
@ConfigurationProperties(prefix = "orqix.ai.model-providers")
public record ProviderProperties(Map<String, Endpoint> endpoints, Map<String, Profile> profiles,
        List<String> openRouterResidency) {

    public ProviderProperties {
        openRouterResidency = openRouterResidency == null ? List.of()
                : openRouterResidency.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                        .toList();
        endpoints = endpoints == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
        profiles = profiles == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    /**
     * One provider endpoint.
     *
     * @param kind        {@code openaiCompatible}, {@code anthropic}, {@code bedrock}, {@code azureOpenai} or {@code vertex}
     * @param baseUrl     API address; the provider's public address when empty (not for openaiCompatible or azureOpenai)
     * @param secretRef   S132 secret reference of the key or token; empty for an endpoint without authentication. For
     *                    Bedrock either a Bedrock API key or a JSON {@code {"accessKeyId","secretAccessKey","sessionToken"}}
     * @param region      AWS region (Bedrock)
     * @param project     Google Cloud project (Vertex); empty uses the Gemini API with an API key
     * @param location    Vertex location (default {@code us-central1})
     * @param apiVersion  Azure OpenAI {@code api-version} (default {@code 2024-10-21})
     * @param deployments Azure model id to deployment name; a missing entry uses the model id
     * @param residency   tags describing where the endpoint processes data, e.g. {@code eu}, {@code us}, {@code private}
     * @param strictSchema ask for strict JSON-schema output where the protocol supports it (default true)
     * @param logprobs    ask an OpenAI-compatible endpoint for token log-probabilities (default false)
     * @param timeout     per-call ceiling (default 120 s); a task's own deadline may be shorter
     */
    public record Endpoint(String kind, URI baseUrl, String secretRef, String region, String project, String location,
            String apiVersion, Map<String, String> deployments, List<String> residency, Boolean strictSchema,
            Boolean logprobs, Duration timeout) {

        public Endpoint {
            secretRef = secretRef == null ? "" : secretRef.trim();
            region = region == null ? "" : region.trim();
            project = project == null ? "" : project.trim();
            location = location == null || location.isBlank() ? "us-central1" : location.trim();
            apiVersion = apiVersion == null || apiVersion.isBlank() ? "2024-10-21" : apiVersion.trim();
            deployments = deployments == null ? Map.of() : Map.copyOf(deployments);
            residency = residency == null ? List.of()
                    : residency.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
            strictSchema = strictSchema == null || strictSchema;
            logprobs = logprobs != null && logprobs;
            timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(120) : timeout;
        }

        public ModelProvider.Kind providerKind() {
            return ModelProvider.Kind.of(kind == null ? "" : kind);
        }

        @Override
        public String toString() {
            return "Endpoint[kind=" + kind + ", baseUrl=" + baseUrl + ", secretRef=" + secretRef + ", residency="
                    + residency + "]";
        }
    }

    /**
     * @param provider        endpoint id
     * @param model           the provider's model id (Bedrock model or inference profile id, Azure model, Gemini model)
     * @param inputPerMillion USD per million input tokens (checked against the price caps; used for metering)
     */
    public record Profile(String provider, String model, String label, BigDecimal inputPerMillion,
            BigDecimal outputPerMillion) {

        public Profile {
            inputPerMillion = inputPerMillion == null ? BigDecimal.ZERO : inputPerMillion;
            outputPerMillion = outputPerMillion == null ? BigDecimal.ZERO : outputPerMillion;
        }
    }
}
