package com.orqix.integration.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.MutableClock;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;

/**
 * CG-C08 / CG-I04 (docs/build/S132) against a real HashiCorp Vault (Testcontainers, dev server, KV v2 at
 * {@code secret/}) and the real PostgreSQL: values resolve through the Vault provider, a rotation is picked up after
 * the cache time, bindings accept provider references, and every resolution shows in the secret-access audit with its
 * binding and provider but never a value.
 */
class SecretProvidersVaultTest extends RuntimeTestBase {

    private static final String ROOT_TOKEN = "orqix-root-" + UUID.randomUUID();
    private static final GenericContainer<?> VAULT = new GenericContainer<>("hashicorp/vault:latest")
            .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
            .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
            .withEnv("SKIP_SETCAP", "true")
            .withExposedPorts(8200)
            .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    @Autowired
    SecretAccessAudit audit;

    @Autowired
    OrqixProperties properties;

    @BeforeAll
    static void startVault() {
        VAULT.start();
    }

    @AfterAll
    static void stopVault() {
        VAULT.stop();
    }

    private static URI address() {
        return URI.create("http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200));
    }

    private static void write(String path, Map<String, String> data) throws Exception {
        String body = SecretHttp.JSON.writeValueAsString(Map.of("data", data));
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(address() + "/v1/secret/data/" + path)).header("X-Vault-Token", ROOT_TOKEN)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @Test
    void vaultKv2ValuesResolveRotateAfterTheCacheTimeAndAreCountedWithoutValues() throws Exception {
        String first = "vault-canary-" + UUID.randomUUID();
        String second = "vault-canary-" + UUID.randomUUID();
        String app = "apps/crm-" + UUID.randomUUID().toString().substring(0, 8);
        write(app, Map.of("token", first, "user", "svc-orqix"));
        Path tokenFile = Files.createTempFile("vault-token", "");
        Files.writeString(tokenFile, ROOT_TOKEN + "\n", StandardCharsets.UTF_8);
        MutableClock clock = new MutableClock(Instant.now());
        VaultSecretProvider vault = new VaultSecretProvider(new SecretProviderSettings.Vault(true, address(), null, "token",
                null, tokenFile, null, null, null), new SecretHttp(Duration.ofSeconds(5)), clock);
        SecretResolver resolver = new SecretResolver(List.of(new FileSecretProvider(properties), vault),
                SecretProviderSettings.defaults(), audit, clock);
        String ref = "vault:secret/" + app + "#token";
        try {
            assertThat(resolver.resolve(ref, SANDBOX)).contains(first);
            // Without a key, a secret with several fields resolves to its fields as JSON.
            assertThat(resolver.resolve("vault:secret/" + app, SANDBOX)).get().asString().contains("\"user\":\"svc-orqix\"");
            assertThat(resolver.resolve("vault:secret/" + app + "#missing", SANDBOX)).isEmpty();
            assertThat(resolver.resolve("vault:secret/apps/none-" + UUID.randomUUID() + "#token", SANDBOX)).isEmpty();

            write(app, Map.of("token", second, "user", "svc-orqix")); // rotation: KV v2 version 2
            assertThat(resolver.resolve(ref, SANDBOX)).as("cached for 30 s").contains(first);
            clock.set(clock.instant().plusSeconds(31));
            assertThat(resolver.resolve(ref, SANDBOX)).contains(second);

            Response report = api("wade.admin").get("/workspaces/{ws}/secret-access?days=1", SANDBOX);
            assertThat(report.status()).as(report.toString()).isEqualTo(200);
            JsonNode entry = entry(report.body(), ref);
            assertThat(entry.path("provider").asString()).isEqualTo("vault");
            assertThat(entry.path("resolutions").asLong()).isEqualTo(3);
            assertThat(entry.path("cacheHits").asLong()).isEqualTo(1);
            assertThat(entry.path("failures").asLong()).isZero();
            assertThat(entry.path("lastResolvedAt").isNull()).isFalse();
            assertThat(entry(report.body(), "vault:secret/" + app + "#missing").path("notFound").asLong()).isEqualTo(1);
            assertThat(report.toString()).doesNotContain(first, second, ROOT_TOKEN);
            String stored = jdbc.sql("SELECT string_agg(row_to_json(c)::text, ' ') FROM secret_access_count c")
                    .query(String.class).single();
            assertThat(stored).doesNotContain(first, second, ROOT_TOKEN);
        } finally {
            Files.deleteIfExists(tokenFile);
        }
    }

    @Test
    void bindingsAcceptProviderReferencesAndTheAuditNamesTheirBindings() throws Exception {
        String fileRef = "audit-" + UUID.randomUUID().toString().substring(0, 12);
        String vaultRef = "vault:secret/apps/" + UUID.randomUUID() + "#token";
        String credential = "file-canary-" + UUID.randomUUID();
        Path dir = properties.secrets().dir();
        Files.createDirectories(dir);
        Path file = dir.resolve(fileRef);
        Files.writeString(file, credential, StandardCharsets.UTF_8);
        try {
            // A provider reference is accepted by the API and the database; Vault is not enabled in this application,
            // so the connection is unavailable and the failure is counted.
            Response saved = api("wade.admin").put(binding("bearerToken", vaultRef), etag(),
                    "/workspaces/{ws}/connections/{key}/binding", SANDBOX, "procurement");
            assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
            assertThat(saved.body().path("auth").path("secretRef").asString()).isEqualTo(vaultRef);
            Response unavailable = connectionTest();
            assertThat(unavailable.body().path("errorCode").asString()).isEqualTo("CONNECTION_UNAVAILABLE");
            for (String bad : List.of("vault:secret/../sys/raw#x", "vault:secret", "ldap:x/y", "Vault:secret/a")) {
                Response refused = api("wade.admin").put(binding("bearerToken", bad), etag(),
                        "/workspaces/{ws}/connections/{key}/binding", SANDBOX, "procurement");
                assertThat(refused.status()).as(bad + " " + refused).isIn(400, 422);
            }

            Response file1 = api("wade.admin").put(binding("bearerToken", fileRef), etag(),
                    "/workspaces/{ws}/connections/{key}/binding", SANDBOX, "procurement");
            assertThat(file1.status()).as(file1.toString()).isEqualTo(200);
            Response ok = connectionTest();
            assertThat(ok.body().path("outcome").asString()).as(ok.toString()).isEqualTo("succeeded");

            Response report = api("wade.admin").get("/workspaces/{ws}/secret-access", SANDBOX);
            assertThat(report.status()).isEqualTo(200);
            JsonNode fileEntry = entry(report.body(), fileRef);
            assertThat(fileEntry.path("provider").asString()).isEqualTo("file");
            assertThat(fileEntry.path("resolutions").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(fileEntry.path("connectionKeys").toString()).isEqualTo("[\"procurement\"]");
            JsonNode vaultEntry = entry(report.body(), vaultRef);
            assertThat(vaultEntry.path("provider").asString()).isEqualTo("vault");
            assertThat(vaultEntry.path("failures").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(vaultEntry.path("resolutions").asLong()).isZero();
            assertThat(report.toString() + ok + unavailable).doesNotContain(credential);

            // Reading the audit needs connection.manage; a designer of the workspace is refused, bad ranges are invalid.
            assertThat(api("dana.designer").get("/workspaces/{ws}/secret-access", SANDBOX).status()).isEqualTo(403);
            assertThat(api("wade.admin").get("/workspaces/{ws}/secret-access?days=91", SANDBOX).status()).isEqualTo(422);
        } finally {
            api("wade.admin").put(binding("none", null), etag(), "/workspaces/{ws}/connections/{key}/binding", SANDBOX,
                    "procurement");
            Files.deleteIfExists(file);
        }
    }

    private static JsonNode entry(JsonNode report, String ref) {
        for (JsonNode item : report.path("items")) {
            if (ref.equals(item.path("secretRef").asString())) {
                return item;
            }
        }
        throw new AssertionError("no audit entry for " + ref + " in " + report);
    }

    private Map<String, Object> binding(String authType, String secretRef) {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("type", authType);
        if (secretRef != null) {
            auth.put("secretRef", secretRef);
        }
        return Map.of("baseUrl", Stub.baseUrl(), "auth", auth, "enabled", true);
    }

    private String etag() throws Exception {
        JsonNode detail = api("wade.admin").get("/workspaces/{ws}/connections/{key}", SANDBOX, "procurement").body();
        return "\"r" + detail.path("binding").path("revision").asInt() + "\"";
    }

    private Response connectionTest() throws Exception {
        return api("wade.admin").post(Map.of("version", 1, "operation", "health"),
                "/workspaces/{ws}/connections/{key}/test", SANDBOX, "procurement");
    }
}
