package com.orqix.integration.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.orqix.integration.secrets.SecretResolver;

/**
 * Reads connection secrets by {@code secretRef}. A plain name is a mounted file in {@code orqix.secrets.dir}, read on
 * every use so a rotated Kubernetes Secret takes effect without restart (S6 section 6); a reference with a scheme is
 * resolved by the secret provider it names (Vault, AWS, Azure, Google; docs/build/S132). The value is never logged,
 * returned or stored, and every resolution is counted in the secret-access audit.
 */
@Component
public class SecretStore {

    private static final String KEYRING = "oauth-token-keyring";

    private final SecretResolver resolver;
    private final com.orqix.integration.OAuthTokenClient oauth;
    private final com.orqix.integration.DelegatedOAuth delegated;

    public SecretStore(SecretResolver resolver, com.orqix.integration.OAuthTokenClient oauth,
            com.orqix.integration.DelegatedOAuth delegated) {
        this.resolver = resolver;
        this.oauth = oauth;
        this.delegated = delegated;
    }

    public Optional<String> credential(String authType, String reference, UUID workspace) {
        if ("oauth2AuthorizationCode".equals(authType)) return delegated.resolve(workspace, reference);
        var value = read(reference, workspace);
        return "oauth2ClientCredentials".equals(authType)
                ? value.flatMap(config -> oauth.resolve(workspace, reference, config)) : value;
    }

    public void invalidateOAuth(UUID workspace, String reference) {
        oauth.invalidate(workspace, reference);
        delegated.invalidate(workspace, reference);
        resolver.invalidate(reference);
    }

    /** @return the secret value, or empty when it is missing or unreadable (CONNECTION_UNAVAILABLE) */
    public Optional<String> read(String secretRef) {
        return read(secretRef, null);
    }

    /** As {@link #read(String)}, counted for the workspace that uses the reference. */
    public Optional<String> read(String secretRef, UUID workspace) {
        if (secretRef == null || KEYRING.equals(secretRef)) {
            return Optional.empty();
        }
        return resolver.resolve(secretRef, workspace);
    }
}
