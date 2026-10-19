package com.orqix.integration.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.orqix.shared.config.OrqixProperties;

/**
 * Reads connection secrets: one file per {@code secretRef} in {@code orqix.secrets.dir}, read on every use so a rotated
 * Kubernetes Secret takes effect without restart (S6 section 6). The value is never logged, returned or stored.
 */
@Component
public class SecretStore {

    private static final Pattern REF = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    private final OrqixProperties properties;
    private final com.orqix.integration.OAuthTokenClient oauth;
    private final com.orqix.integration.DelegatedOAuth delegated;

    public SecretStore(OrqixProperties properties, com.orqix.integration.OAuthTokenClient oauth,
            com.orqix.integration.DelegatedOAuth delegated) {
        this.properties = properties;
        this.oauth = oauth;
        this.delegated = delegated;
    }

    public Optional<String> credential(String authType, String reference, java.util.UUID workspace) {
        if ("oauth2AuthorizationCode".equals(authType)) return delegated.resolve(workspace, reference);
        var value = read(reference);
        return "oauth2ClientCredentials".equals(authType)
                ? value.flatMap(config -> oauth.resolve(workspace, reference, config)) : value;
    }

    public void invalidateOAuth(java.util.UUID workspace, String reference) { oauth.invalidate(workspace, reference); delegated.invalidate(workspace, reference); }

    /** @return the secret value, or empty when the file is missing or unreadable (CONNECTION_UNAVAILABLE) */
    public Optional<String> read(String secretRef) {
        if (secretRef == null || "oauth-token-keyring".equals(secretRef) || !REF.matcher(secretRef).matches()) {
            return Optional.empty();
        }
        Path dir = properties.secrets().dir().toAbsolutePath().normalize();
        Path file = dir.resolve(secretRef).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String value = Files.readString(file, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
