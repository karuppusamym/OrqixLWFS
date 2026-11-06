package com.orqix.integration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.orqix.integration.secrets.SecretResolver;

/**
 * CG-T01 (docs/build/S141): secrets that verify inbound chat interactions (the Teams outgoing-webhook HMAC key, the
 * Slack signing secret), resolved through the S132 secret providers like every other secret, so each resolution is
 * counted in the secret-access audit. Reserved namespaces are refused.
 */
@Component
public class ChannelSecrets {

    private final SecretResolver resolver;

    public ChannelSecrets(SecretResolver resolver) {
        this.resolver = resolver;
    }

    /** @return the secret value, or empty when the reference is reserved, malformed or has no value */
    public Optional<String> resolve(String secretRef, UUID workspaceId) {
        if (secretRef == null || secretRef.isBlank() || SecretNamespaces.reserved(secretRef)) {
            return Optional.empty();
        }
        return resolver.resolve(secretRef, workspaceId);
    }

    /** Throws a 422-style refusal for a reference in a reserved namespace (same rule as trigger secrets). */
    public static void refuseReserved(String secretRef, String pointer) {
        SecretNamespaces.refuseReserved(secretRef, pointer);
    }
}
