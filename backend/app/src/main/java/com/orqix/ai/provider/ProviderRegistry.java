package com.orqix.ai.provider;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.orqix.ai.AiProperties;
import com.orqix.ai.internal.ModelPolicy;
import com.orqix.ai.internal.ProviderException;
import com.orqix.integration.ModelProviderSecrets;
import com.orqix.shared.problem.ApiException;

/**
 * Entry point of the provider SPI (CG-A04, docs/build/S134 section 1). Resolves a model profile to its endpoint and
 * applies the existing policy before any call: the S10 profiles go through {@link ModelPolicy} (OpenRouter catalog,
 * excluded models, allowlist, price caps, circuit breaker); profiles of {@code orqix.ai.model-providers} are checked
 * against the same excluded-model globs, allowlist and price caps using their declared prices. A workspace residency
 * rule then admits only endpoints carrying one of its tags. Credentials come from the S132 secret providers.
 *
 * <p>Other AI features (agents, assistants) call {@link #resolve} and {@link #complete} instead of a client of their
 * own when they move to the SPI; this class does not meter or check budgets, which stay with the caller's ledger.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** A usable profile and its endpoint. */
    public record Resolved(String profileId, String label, String endpointId, ModelProvider.Kind kind, String model,
            List<String> residency, BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    }

    /** A profile that cannot be used for this request; {@code reason} is business-readable. */
    public static class ProfileRefusedException extends RuntimeException {
        private final String code;

        public ProfileRefusedException(String code, String reason) {
            super(reason, null, false, false);
            this.code = code;
        }

        /** {@code unknown}, {@code policy} or {@code residency} */
        public String code() {
            return code;
        }
    }

    private final AiProperties ai;
    private final ProviderProperties providers;
    private final ModelPolicy policy;
    private final ModelProviderSecrets secrets;
    private final Map<ModelProvider.Kind, ModelProvider> byKind = new EnumMap<>(ModelProvider.Kind.class);
    private final ProviderProperties.Endpoint openRouter;

    public ProviderRegistry(AiProperties ai, ProviderProperties providers, ModelPolicy policy,
            ModelProviderSecrets secrets, List<ModelProvider> implementations) {
        this.ai = ai;
        this.providers = providers;
        this.policy = policy;
        this.secrets = secrets;
        implementations.forEach(p -> byKind.put(p.kind(), p));
        this.openRouter = new ProviderProperties.Endpoint("openaiCompatible", ai.baseUrl(), "", "", "", "", "",
                Map.of(), providers.openRouterResidency(), true, false, ai.timeout());
        providers.profiles().keySet().stream().filter(ai.profiles()::containsKey).forEach(id -> log.warn(
                "AI profile '{}' is declared in orqix.ai.profiles and orqix.ai.model-providers; the first wins.", id));
        providers.profiles().forEach((id, p) -> {
            if (!providers.endpoints().containsKey(p.provider())) {
                log.warn("AI profile '{}' names the unknown model endpoint '{}'; it cannot be used.", id, p.provider());
            }
        });
    }

    /** Every profile id known to the deployment, in configuration order (S10 profiles first). */
    public List<String> profileIds() {
        List<String> ids = new ArrayList<>(ai.profiles().keySet());
        providers.profiles().keySet().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        return ids;
    }

    /** Endpoint ids with their protocol and residency tags (Settings and status). */
    public Map<String, ProviderProperties.Endpoint> endpoints() {
        Map<String, ProviderProperties.Endpoint> all = new java.util.LinkedHashMap<>();
        if (ai.keyConfigured()) {
            all.put(OpenAiCompatibleProvider.OPENROUTER, openRouter);
        }
        all.putAll(providers.endpoints());
        return all;
    }

    /**
     * Resolves a profile ({@code null} = the deployment default) under a residency rule.
     *
     * @param allowedResidency tags of which an endpoint must carry one; empty admits every endpoint
     * @throws ProfileRefusedException when the profile is unknown, refused by policy or outside the residency rule
     */
    public Resolved resolve(String profileId, List<String> allowedResidency) {
        Resolved resolved;
        String id = profileId == null || profileId.isBlank() ? policy.defaultProfileId() : profileId;
        if (id != null && ai.profiles().containsKey(id)) {
            if (!ai.keyConfigured()) {
                throw new ProfileRefusedException("policy", ModelPolicy.REASON_NOT_CONFIGURED);
            }
            try {
                ModelPolicy.ResolvedProfile p = policy.resolve(id);
                resolved = new Resolved(p.id(), p.label(), OpenAiCompatibleProvider.OPENROUTER,
                        ModelProvider.Kind.OPENAI_COMPATIBLE, p.model(), openRouter.residency(), null, null);
            } catch (ApiException e) {
                throw new ProfileRefusedException("policy", e.getMessage());
            }
        } else if (id != null && providers.profiles().containsKey(id)) {
            ProviderProperties.Profile p = providers.profiles().get(id);
            ProviderProperties.Endpoint endpoint = providers.endpoints().get(p.provider());
            if (endpoint == null || p.model() == null || p.model().isBlank()) {
                throw new ProfileRefusedException("unknown", "The AI profile " + id + " has no usable endpoint.");
            }
            if (policy.excluded(p.model())) {
                throw new ProfileRefusedException("policy", "The model " + p.model() + " is excluded by policy.");
            }
            if (!policy.allowlisted(p.model())) {
                throw new ProfileRefusedException("policy", "The model " + p.model() + " is not in the allowlist.");
            }
            if (p.inputPerMillion().compareTo(ai.maxPrice().promptPerMillion()) > 0
                    || p.outputPerMillion().compareTo(ai.maxPrice().completionPerMillion()) > 0) {
                throw new ProfileRefusedException("policy",
                        "The model " + p.model() + " costs more than the configured price caps.");
            }
            resolved = new Resolved(id, p.label() == null || p.label().isBlank() ? id : p.label(), p.provider(),
                    endpoint.providerKind(), p.model(), endpoint.residency(), p.inputPerMillion(), p.outputPerMillion());
        } else {
            throw new ProfileRefusedException("unknown", "Unknown AI profile.");
        }
        if (allowedResidency != null && !allowedResidency.isEmpty()
                && resolved.residency().stream().noneMatch(allowedResidency::contains)) {
            throw new ProfileRefusedException("residency", "The AI profile " + resolved.profileId()
                    + " runs outside this workspace's residency rule (" + String.join(", ", allowedResidency) + ").");
        }
        return resolved;
    }

    /**
     * One completion through the profile's endpoint. Fills {@code costUsd} from the declared prices when the provider
     * reports none.
     *
     * @throws ProviderException on provider failures (and when the endpoint's secret has no value)
     */
    public ModelProvider.ModelResponse complete(Resolved profile, ModelProvider.ModelRequest request, UUID workspaceId) {
        ProviderProperties.Endpoint endpoint = OpenAiCompatibleProvider.OPENROUTER.equals(profile.endpointId())
                ? openRouter : providers.endpoints().get(profile.endpointId());
        ModelProvider provider = byKind.get(profile.kind());
        if (endpoint == null || provider == null) {
            throw new ProviderException(ProviderException.Kind.REJECTED, 0, null, "no provider for " + profile.kind());
        }
        String credential;
        if (endpoint == openRouter) {
            credential = ai.apiKey();
        } else if (endpoint.secretRef().isEmpty()) {
            credential = null;
        } else {
            credential = secrets.resolve(endpoint.secretRef(), workspaceId).orElseThrow(() -> new ProviderException(
                    ProviderException.Kind.CREDENTIALS_REJECTED, 0, null, "endpoint secret unavailable"));
        }
        ModelProvider.ModelRequest effective = new ModelProvider.ModelRequest(profile.model(), request.system(),
                request.messages(), request.schemaName(), request.schema(), request.maxTokens(), request.temperature(),
                request.timeout());
        ModelProvider.ModelResponse response = provider.complete(endpoint, credential, effective);
        if (endpoint == openRouter) {
            policy.routedViolation(profile.model(), response.model()).ifPresent(reason -> {
                throw new ProviderException(ProviderException.Kind.REJECTED, 0, null,
                        "routed to a refused model");
            });
        }
        if (response.costUsd() == null && profile.inputPerMillion() != null) {
            BigDecimal cost = BigDecimal.valueOf(response.inputTokens() == null ? 0 : response.inputTokens())
                    .multiply(profile.inputPerMillion())
                    .add(BigDecimal.valueOf(response.outputTokens() == null ? 0 : response.outputTokens())
                            .multiply(profile.outputPerMillion()))
                    .divide(MILLION, MathContext.DECIMAL64);
            response = new ModelProvider.ModelResponse(response.model(), response.content(), response.finishReason(),
                    response.inputTokens(), response.outputTokens(), response.cachedInputTokens(), cost,
                    response.schemaEnforced(), response.meanTokenProbability());
        }
        return response;
    }
}
