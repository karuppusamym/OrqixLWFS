package com.orqix.integration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.orqix.integration.secrets.SecretResolver;

/**
 * Credentials of AI model provider endpoints (CG-A04, docs/build/S134), resolved through the S132 secret providers so
 * they come from Vault or a cloud key vault like every connection credential and every resolution is counted in the
 * secret-access audit. References are deployment configuration ({@code orqix.ai.model-providers.endpoints.*.secret-ref}),
 * never workspace-authored; webhook signing secrets are refused.
 */
@Component
public class ModelProviderSecrets {

    private final SecretResolver resolver;

    public ModelProviderSecrets(SecretResolver resolver) {
        this.resolver = resolver;
    }

    /** @return the secret value, or empty when the reference is reserved, malformed or has no value */
    public Optional<String> resolve(String secretRef, UUID workspaceId) {
        if (secretRef == null || secretRef.isBlank() || SecretNamespaces.reserved(secretRef)) {
            return Optional.empty();
        }
        return resolver.resolve(secretRef, workspaceId == null ? new UUID(0, 0) : workspaceId);
    }
}
