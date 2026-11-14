package com.orqix.integration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.orqix.integration.secrets.SecretRef;
import com.orqix.integration.secrets.SecretResolver;

/**
 * CG-S01 (docs/build/S147): the access token of a workspace's Git connection, resolved through the S132 secret
 * providers at call time, so every resolution is counted in the secret-access audit. The catalog keeps only the
 * reference. Reserved namespaces (platform keys, OAuth keyrings) are refused like every other user-supplied reference.
 */
@Component
public class GitCredentials {

    /** The OAuth token keyring is a platform key and never a Git credential (same rule as SecretStore). */
    private static final String PLATFORM_KEYRING = "oauth-token-keyring";

    private final SecretResolver resolver;

    public GitCredentials(SecretResolver resolver) {
        this.resolver = resolver;
    }

    /** @return the token, or empty when the reference is reserved, malformed or the store has no value */
    public Optional<String> resolve(String credentialRef, UUID workspaceId) {
        if (credentialRef == null || credentialRef.isBlank() || SecretNamespaces.reserved(credentialRef)
                || PLATFORM_KEYRING.equals(credentialRef)) {
            return Optional.empty();
        }
        return resolver.resolve(credentialRef, workspaceId).map(String::strip).filter(v -> !v.isEmpty());
    }

    /** True when the reference has an accepted shape (the same expression the bindings use). */
    public static boolean wellFormed(String credentialRef) {
        return SecretRef.parse(credentialRef).isPresent();
    }

    /** Throws a 422 refusal for a reference in a reserved namespace. */
    public static void refuseReserved(String credentialRef, String pointer) {
        SecretNamespaces.refuseReserved(credentialRef, pointer);
        if (PLATFORM_KEYRING.equals(credentialRef)) {
            throw new com.orqix.shared.problem.ApiException(com.orqix.shared.problem.ProblemCode.VALIDATION_FAILED,
                    "This reference is reserved for a platform key.").withFieldErrors(java.util.List.of(
                            new com.orqix.shared.problem.ApiException.FieldError(pointer, "reserved",
                                    "This reference is reserved for a platform key.")));
        }
    }
}
