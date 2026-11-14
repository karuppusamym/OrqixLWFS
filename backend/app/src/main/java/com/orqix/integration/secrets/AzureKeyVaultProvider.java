package com.orqix.integration.secrets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Azure Key Vault (docs/build/S132). {@code azure-kv:orqix-prod/crm-token} reads the latest version of the secret
 * {@code crm-token} in the vault {@code orqix-prod}; a third segment pins a version and {@code #key} picks one field
 * of a JSON value. The default client gets its token from workload identity or the managed identity endpoint.
 */
@Component
public class AzureKeyVaultProvider implements SecretProvider {

    /** The store, so tests and other transports can stand in for Azure. */
    public interface Client {
        /** @param version a version id, or null for the latest; @return the value, or empty when there is none */
        Optional<String> secret(String vault, String name, String version);
    }

    private final boolean enabled;
    private final Client client;

    @Autowired
    public AzureKeyVaultProvider(SecretProviderSettings settings, Clock clock) {
        this(settings.azure().enabled(), settings.azure().enabled()
                ? new HttpClient(settings.azure(), new SecretHttp(settings.timeout()), clock, System::getenv) : null);
    }

    AzureKeyVaultProvider(boolean enabled, Client client) {
        this.enabled = enabled;
        this.client = client;
    }

    @Override
    public String scheme() {
        return SecretRef.AZURE;
    }

    @Override
    public boolean enabled() {
        return enabled && client != null;
    }

    @Override
    public Optional<String> resolve(SecretRef ref) {
        String[] parts = ref.locator().split("/");
        if (!parts[0].matches("^[A-Za-z0-9-]{3,24}$") || !parts[1].matches("^[A-Za-z0-9-]{1,127}$")) {
            return Optional.empty();
        }
        return client.secret(parts[0], parts[1], parts.length == 3 ? parts[2] : null)
                .flatMap(value -> SecretHttp.select(value, ref.key()));
    }

    /** Key Vault REST API 7.4 with a bearer token for {@code https://vault.azure.net}. */
    static final class HttpClient implements Client {
        private static final String STORE = "Azure Key Vault";
        private static final String RESOURCE = "https://vault.azure.net";

        private final SecretProviderSettings.Azure settings;
        private final SecretHttp http;
        private final UnaryOperator<String> env;
        private final BearerTokens tokens;

        HttpClient(SecretProviderSettings.Azure settings, SecretHttp http, Clock clock, UnaryOperator<String> env) {
            this.settings = settings;
            this.http = http;
            this.env = env;
            this.tokens = new BearerTokens(clock, this::fetchToken);
        }

        @Override
        public Optional<String> secret(String vault, String name, String version) {
            String template = settings.vaultEndpoint() == null ? "https://{vault}.vault.azure.net" : settings.vaultEndpoint();
            String base = template.replace("{vault}", vault);
            URI uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/secrets/"
                    + SecretHttp.enc(name) + (version == null ? "" : "/" + SecretHttp.enc(version)) + "?api-version=7.4");
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
            JsonNode json = response.json();
            return json.hasNonNull("value") ? Optional.of(json.get("value").asString()) : Optional.empty();
        }

        private SecretHttp.Response get(URI uri) {
            return http.send(STORE, HttpRequest.newBuilder(uri).GET().header("Authorization", "Bearer " + tokens.get()));
        }

        private BearerTokens.Token fetchToken() {
            String federated = env.apply("AZURE_FEDERATED_TOKEN_FILE");
            String clientId = settings.clientId() != null ? settings.clientId() : env.apply("AZURE_CLIENT_ID");
            String tenant = env.apply("AZURE_TENANT_ID");
            SecretHttp.Response response;
            if (federated != null && clientId != null && tenant != null) {
                String assertion;
                try {
                    assertion = Files.readString(Path.of(federated), StandardCharsets.UTF_8).strip();
                } catch (IOException e) {
                    throw new SecretProviderException(STORE + " federated token could not be read.");
                }
                String authority = settings.authorityHost() != null ? settings.authorityHost().toString()
                        : env.apply("AZURE_AUTHORITY_HOST") != null ? env.apply("AZURE_AUTHORITY_HOST")
                        : "https://login.microsoftonline.com/";
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "client_credentials");
                form.put("client_id", clientId);
                form.put("scope", RESOURCE + "/.default");
                form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
                form.put("client_assertion", assertion);
                URI uri = URI.create((authority.endsWith("/") ? authority : authority + "/") + SecretHttp.enc(tenant)
                        + "/oauth2/v2.0/token");
                response = http.send(STORE, HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(SecretHttp.form(form))));
            } else {
                String base = settings.identityEndpoint() != null ? settings.identityEndpoint().toString()
                        : "http://169.254.169.254/metadata/identity/oauth2/token";
                String query = "api-version=2018-02-01&resource=" + SecretHttp.enc(RESOURCE)
                        + (clientId == null ? "" : "&client_id=" + SecretHttp.enc(clientId));
                response = http.send(STORE, HttpRequest.newBuilder(URI.create(base + "?" + query)).GET()
                        .header("Metadata", "true"));
            }
            if (!response.ok()) {
                throw new SecretProviderException(STORE + " token request answered " + response.status() + ".");
            }
            JsonNode json = response.json();
            String value = json.path("access_token").asString("");
            if (value.isEmpty()) {
                throw new SecretProviderException(STORE + " token request returned no token.");
            }
            return new BearerTokens.Token(value, BearerTokens.seconds(json.get("expires_in")));
        }
    }
}
