package com.orqix.integration.secrets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * HashiCorp Vault, KV secrets engine version 2 (docs/build/S132). {@code vault:kv/apps/crm#token} reads
 * {@code GET /v1/kv/data/apps/crm} and returns the field {@code token}; without a key, a secret with one field gives
 * that field and a larger one its fields as JSON. Token auth reads the token file on every login so a renewed token
 * (Vault Agent) is picked up; Kubernetes auth exchanges the service-account token and keeps the Vault token until 80 %
 * of its lease. A 403 drops the Vault token and tries once more.
 */
@Component
public class VaultSecretProvider implements SecretProvider {

    private static final String STORE = "Vault";

    private final SecretProviderSettings.Vault settings;
    private final SecretHttp http;
    private final Clock clock;
    private volatile Token token;

    private record Token(String value, Instant renewAt) {
    }

    @Autowired
    public VaultSecretProvider(SecretProviderSettings settings, Clock clock) {
        this(settings.vault(), new SecretHttp(settings.timeout()), clock);
    }

    VaultSecretProvider(SecretProviderSettings.Vault settings, SecretHttp http, Clock clock) {
        this.settings = settings;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public String scheme() {
        return SecretRef.VAULT;
    }

    @Override
    public boolean enabled() {
        return settings.enabled() && settings.address() != null;
    }

    @Override
    public Optional<String> resolve(SecretRef ref) {
        int slash = ref.locator().indexOf('/');
        String mount = ref.locator().substring(0, slash);
        String path = ref.locator().substring(slash + 1);
        URI uri = URI.create(SecretHttp.base(settings.address(), "") + "/v1/" + SecretHttp.path(mount) + "/data/"
                + SecretHttp.path(path));
        SecretHttp.Response response = read(uri);
        if (response.status() == 403) {
            token = null;
            response = read(uri);
        }
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (!response.ok()) {
            throw new SecretProviderException(STORE + " answered " + response.status() + " for " + ref.raw() + ".");
        }
        JsonNode data = response.json().path("data").path("data");
        if (!data.isObject()) {
            return Optional.empty();
        }
        if (ref.key() != null) {
            return SecretHttp.field(data, ref.key());
        }
        if (data.size() == 1) {
            return SecretHttp.field(data, data.properties().iterator().next().getKey());
        }
        return data.isEmpty() ? Optional.empty() : Optional.of(SecretHttp.JSON.writeValueAsString(data));
    }

    private SecretHttp.Response read(URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET().header("X-Vault-Token", token());
        namespace(request);
        return http.send(STORE, request);
    }

    private void namespace(HttpRequest.Builder request) {
        if (settings.namespace() != null && !settings.namespace().isBlank()) {
            request.header("X-Vault-Namespace", settings.namespace());
        }
    }

    private String token() {
        Token current = token;
        if (current != null && clock.instant().isBefore(current.renewAt())) {
            return current.value();
        }
        Token fresh = switch (settings.auth()) {
            case "token" -> new Token(staticToken(), clock.instant().plusSeconds(60));
            case "kubernetes" -> kubernetesLogin();
            default -> throw new SecretProviderException(STORE + " auth " + settings.auth() + " is not supported.");
        };
        token = fresh;
        return fresh.value();
    }

    private String staticToken() {
        if (settings.tokenFile() != null) {
            String value = readFile(settings.tokenFile(), "token file");
            if (!value.isEmpty()) {
                return value;
            }
        }
        if (settings.token() != null && !settings.token().isBlank()) {
            return settings.token();
        }
        throw new SecretProviderException(STORE + " has no token configured.");
    }

    private Token kubernetesLogin() {
        if (settings.kubernetesRole() == null || settings.kubernetesRole().isBlank()) {
            throw new SecretProviderException(STORE + " Kubernetes auth needs a role.");
        }
        String jwt = readFile(settings.kubernetesJwtFile(), "service-account token");
        String body = SecretHttp.JSON.writeValueAsString(Map.of("role", settings.kubernetesRole(), "jwt", jwt));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(SecretHttp.base(settings.address(), "")
                        + "/v1/auth/" + SecretHttp.path(settings.kubernetesMount()) + "/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        namespace(request);
        SecretHttp.Response response = http.send(STORE, request);
        if (!response.ok()) {
            throw new SecretProviderException(STORE + " Kubernetes login answered " + response.status() + ".");
        }
        JsonNode auth = response.json().path("auth");
        String value = auth.path("client_token").asString("");
        if (value.isEmpty()) {
            throw new SecretProviderException(STORE + " Kubernetes login returned no token.");
        }
        long lease = Math.max(auth.path("lease_duration").asLong(60), 10);
        return new Token(value, clock.instant().plusSeconds(lease * 8 / 10));
    }

    private static String readFile(java.nio.file.Path file, String what) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new SecretProviderException(STORE + " " + what + " could not be read.");
        }
    }
}
