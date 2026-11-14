package com.orqix.integration.secrets;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Google Secret Manager (docs/build/S132). {@code gcp-sm:my-project/crm-token} reads the latest version of the
 * secret; a third segment pins a version and {@code #key} picks one field of a JSON value. The default client uses
 * the workload identity of the pod or VM through the metadata server.
 */
@Component
public class GoogleSecretManagerProvider implements SecretProvider {

    /** The store, so tests and other transports can stand in for Google Cloud. */
    public interface Client {
        /** @param version a version number or {@code latest}; @return the payload, or empty when there is none */
        Optional<String> access(String project, String secret, String version);
    }

    private final boolean enabled;
    private final Client client;

    @Autowired
    public GoogleSecretManagerProvider(SecretProviderSettings settings, Clock clock) {
        this(settings.gcp().enabled(), settings.gcp().enabled()
                ? new HttpClient(settings.gcp(), new SecretHttp(settings.timeout()), clock) : null);
    }

    GoogleSecretManagerProvider(boolean enabled, Client client) {
        this.enabled = enabled;
        this.client = client;
    }

    @Override
    public String scheme() {
        return SecretRef.GCP;
    }

    @Override
    public boolean enabled() {
        return enabled && client != null;
    }

    @Override
    public Optional<String> resolve(SecretRef ref) {
        String[] parts = ref.locator().split("/");
        if (!parts[0].matches("^[a-z][a-z0-9-]{4,29}$") || !parts[1].matches("^[A-Za-z0-9_-]{1,255}$")) {
            return Optional.empty();
        }
        String version = parts.length == 3 ? parts[2] : "latest";
        if (!version.matches("^(latest|[0-9]{1,10})$")) {
            return Optional.empty();
        }
        return client.access(parts[0], parts[1], version).flatMap(value -> SecretHttp.select(value, ref.key()));
    }

    /** Secret Manager v1 {@code versions/*:access} with a metadata-server access token. */
    static final class HttpClient implements Client {
        private static final String STORE = "Google Secret Manager";

        private final URI endpoint;
        private final URI metadata;
        private final SecretHttp http;
        private final BearerTokens tokens;

        HttpClient(SecretProviderSettings.Gcp settings, SecretHttp http, Clock clock) {
            this.endpoint = SecretHttp.base(settings.endpoint(), "https://secretmanager.googleapis.com");
            this.metadata = SecretHttp.base(settings.metadataEndpoint(), "http://metadata.google.internal");
            this.http = http;
            this.tokens = new BearerTokens(clock, this::fetchToken);
        }

        @Override
        public Optional<String> access(String project, String secret, String version) {
            URI uri = URI.create(endpoint + "/v1/projects/" + SecretHttp.enc(project) + "/secrets/" + SecretHttp.enc(secret)
                    + "/versions/" + SecretHttp.enc(version) + ":access");
            SecretHttp.Response response = get(uri);
            if (response.status() == 401) {
                tokens.clear();
                response = get(uri);
            }
            if (response.status() == 404) {
                return Optional.empty();
            }
            if (!response.ok()) {
                throw new SecretProviderException(STORE + " answered " + response.status() + ".");
            }
            JsonNode payload = response.json().path("payload");
            if (!payload.hasNonNull("data")) {
                return Optional.empty();
            }
            return Optional.of(new String(Base64.getDecoder().decode(payload.get("data").asString()), StandardCharsets.UTF_8));
        }

        private SecretHttp.Response get(URI uri) {
            return http.send(STORE, HttpRequest.newBuilder(uri).GET().header("Authorization", "Bearer " + tokens.get()));
        }

        private BearerTokens.Token fetchToken() {
            SecretHttp.Response response = http.send(STORE, HttpRequest.newBuilder(URI.create(metadata
                    + "/computeMetadata/v1/instance/service-accounts/default/token")).GET()
                    .header("Metadata-Flavor", "Google"));
            if (!response.ok()) {
                throw new SecretProviderException(STORE + " metadata token request answered " + response.status() + ".");
            }
            JsonNode json = response.json();
            String value = json.path("access_token").asString("");
            if (value.isEmpty()) {
                throw new SecretProviderException(STORE + " metadata server returned no token.");
            }
            return new BearerTokens.Token(value, BearerTokens.seconds(json.get("expires_in")));
        }
    }
}
