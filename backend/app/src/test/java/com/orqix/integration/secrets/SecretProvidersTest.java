package com.orqix.integration.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.orqix.support.MutableClock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Secret provider SPI (CG-C08, docs/build/S132) without cloud accounts: reference parsing, the resolver's provider
 * choice, cache and access audit, Signature V4 against the published AWS test vector, and the AWS, Azure, Google and
 * Vault Kubernetes-auth clients against local HTTP stand-ins that check what a real store would require.
 */
class SecretProvidersTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    /** A local server answering every request through {@code handler}; requests are recorded. */
    private record Recorded(String method, String path, String query, Map<String, String> headers, String body) {
    }

    private URI serve(List<Recorded> seen, Function<Recorded, Object[]> handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            Map<String, String> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.getFirst()));
            Recorded r = new Recorded(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                    headers, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            synchronized (seen) {
                seen.add(r);
            }
            Object[] answer = handler.apply(r);
            byte[] body = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders((Integer) answer[0], body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static SecretHttp http() {
        return new SecretHttp(Duration.ofSeconds(5));
    }

    // ---------------------------------------------------------------- references

    @Test
    void referencesNameTheirProviderAndNeverWalkOutOfTheirPath() {
        assertThat(SecretRef.parse("crm-token")).get().extracting(SecretRef::scheme, SecretRef::locator, SecretRef::key)
                .containsExactly("file", "crm-token", null);
        assertThat(SecretRef.parse("vault:kv/apps/crm#token")).get()
                .extracting(SecretRef::scheme, SecretRef::locator, SecretRef::key).containsExactly("vault", "kv/apps/crm", "token");
        assertThat(SecretRef.parse("aws-sm:arn:aws:secretsmanager:eu-west-1:123456789012:secret:crm-AbCdEf#password"))
                .get().extracting(SecretRef::scheme, SecretRef::key).containsExactly("aws-sm", "password");
        assertThat(SecretRef.parse("azure-kv:orqix-prod/crm-token")).get().extracting(SecretRef::scheme).isEqualTo("azure-kv");
        assertThat(SecretRef.parse("gcp-sm:my-project/crm-token/3#token")).get().extracting(SecretRef::locator)
                .isEqualTo("my-project/crm-token/3");
        for (String bad : List.of("Crm", "vault:kv", "vault:kv/../sys/x", "vault:kv//x", "vault:kv/x/", "azure-kv:a",
                "gcp-sm:p/s/v/extra", "ldap:x/y", "vault:kv/x#", "../etc/passwd", "")) {
            assertThat(SecretRef.valid(bad)).as(bad).isFalse();
        }
        assertThat(SecretRef.valid(null)).isFalse();
    }

    // ---------------------------------------------------------------- resolver: provider choice, cache, audit

    private static final class Counting implements SecretProvider {
        final String scheme;
        final AtomicInteger calls = new AtomicInteger();
        volatile String value = "v1";
        volatile boolean fail;

        Counting(String scheme) {
            this.scheme = scheme;
        }

        @Override
        public String scheme() {
            return scheme;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Optional<String> resolve(SecretRef ref) {
            calls.incrementAndGet();
            if (fail) {
                throw new SecretProviderException("Store answered 503 for " + ref.raw() + ".");
            }
            return Optional.ofNullable(value);
        }
    }

    @Test
    void theResolverCachesRemoteValuesBrieflyCountsEveryResolutionAndNeverCachesFiles(@TempDir Path dir) throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));
        Counting vault = new Counting("vault");
        SecretAccessAudit audit = mock(SecretAccessAudit.class);
        Files.writeString(dir.resolve("crm-token"), "file-v1\n");
        SecretResolver resolver = new SecretResolver(List.of(new FileSecretProvider(dir), vault),
                SecretProviderSettings.defaults(), audit, clock);
        UUID ws = UUID.randomUUID();

        assertThat(resolver.resolve("vault:kv/apps/crm#token", ws)).contains("v1");
        vault.value = "v2"; // rotated in the store
        assertThat(resolver.resolve("vault:kv/apps/crm#token", ws)).contains("v1");
        assertThat(vault.calls).hasValue(1);
        clock.set(clock.instant().plus(Duration.ofSeconds(31))); // the default cache time is 30 s
        assertThat(resolver.resolve("vault:kv/apps/crm#token", ws)).contains("v2");
        assertThat(vault.calls).hasValue(2);
        SecretRef ref = SecretRef.parse("vault:kv/apps/crm#token").orElseThrow();
        verify(audit, times(2)).record(ws, ref, SecretAccessAudit.Outcome.RESOLVED);
        verify(audit).record(ws, ref, SecretAccessAudit.Outcome.CACHED);

        // A failing store resolves to nothing and is counted, never cached.
        clock.set(clock.instant().plus(Duration.ofSeconds(31)));
        vault.fail = true;
        assertThat(resolver.resolve("vault:kv/apps/crm#token", ws)).isEmpty();
        verify(audit).record(ws, ref, SecretAccessAudit.Outcome.FAILED);

        // Files are read on every use: a rotated file is seen at once.
        assertThat(resolver.resolve("crm-token", ws)).contains("file-v1");
        Files.writeString(dir.resolve("crm-token"), "file-v2");
        assertThat(resolver.resolve("crm-token", ws)).contains("file-v2");
        assertThat(resolver.resolve("missing-token", ws)).isEmpty();
        verify(audit).record(ws, SecretRef.parse("missing-token").orElseThrow(), SecretAccessAudit.Outcome.NOT_FOUND);

        // A provider that is not configured, and a malformed reference, resolve to nothing.
        assertThat(resolver.resolve("aws-sm:prod/crm", ws)).isEmpty();
        verify(audit).record(ws, SecretRef.parse("aws-sm:prod/crm").orElseThrow(), SecretAccessAudit.Outcome.FAILED);
        assertThat(resolver.resolve("vault:kv/../sys", ws)).isEmpty();
    }

    @Test
    void aZeroCacheTimeReadsTheStoreEveryTime() {
        Counting vault = new Counting("vault");
        SecretResolver resolver = new SecretResolver(List.of(vault),
                new SecretProviderSettings(Duration.ZERO, null, null, null, null, null), mock(SecretAccessAudit.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        resolver.resolve("vault:kv/a", null);
        resolver.resolve("vault:kv/a", null);
        assertThat(vault.calls).hasValue(2);
    }

    // ---------------------------------------------------------------- AWS

    @Test
    void signatureV4MatchesTheAwsTestSuiteVanillaGet() {
        // aws-sig-v4-test-suite "get-vanilla"
        String auth = AwsSigV4.authorization(new AwsSigV4.Credentials("AKIDEXAMPLE",
                        "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null), "us-east-1", "service", "GET", "/", "",
                Map.of("host", "example.amazonaws.com", "x-amz-date", "20150830T123600Z"), new byte[0],
                Instant.parse("2015-08-30T12:36:00Z"));
        assertThat(auth).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                + "SignedHeaders=host;x-amz-date, "
                + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }

    @Test
    void awsSecretsManagerIsCalledWithASignedGetSecretValueAndPicksAJsonField() throws Exception {
        List<Recorded> seen = new ArrayList<>();
        URI endpoint = serve(seen, r -> r.body().contains("\"prod/crm\"")
                ? new Object[] {200, "{\"Name\":\"prod/crm\",\"SecretString\":\"{\\\"token\\\":\\\"aws-value\\\",\\\"user\\\":\\\"svc\\\"}\"}"}
                : new Object[] {400, "{\"__type\":\"ResourceNotFoundException\",\"message\":\"no\"}"});
        Map<String, String> env = Map.of("AWS_ACCESS_KEY_ID", "AKIDTEST", "AWS_SECRET_ACCESS_KEY", "secret-key",
                "AWS_SESSION_TOKEN", "session");
        var client = new AwsSecretsManagerProvider.HttpClient(new SecretProviderSettings.Aws(true, "eu-west-1", endpoint, null),
                http(), Clock.systemUTC(), env::get);
        var provider = new AwsSecretsManagerProvider(true, client);
        assertThat(provider.resolve(SecretRef.parse("aws-sm:prod/crm#token").orElseThrow())).contains("aws-value");
        assertThat(provider.resolve(SecretRef.parse("aws-sm:prod/crm").orElseThrow())).get().asString().contains("\"user\"");
        assertThat(provider.resolve(SecretRef.parse("aws-sm:prod/crm#missing").orElseThrow())).isEmpty();
        assertThat(provider.resolve(SecretRef.parse("aws-sm:prod/none").orElseThrow())).isEmpty();
        Recorded first = seen.getFirst();
        assertThat(first.method()).isEqualTo("POST");
        assertThat(first.headers()).containsEntry("x-amz-target", "secretsmanager.GetSecretValue")
                .containsEntry("content-type", "application/x-amz-json-1.1").containsEntry("x-amz-security-token", "session");
        assertThat(first.headers().get("authorization")).startsWith("AWS4-HMAC-SHA256 Credential=AKIDTEST/")
                .contains("/eu-west-1/secretsmanager/aws4_request",
                        "SignedHeaders=content-type;host;x-amz-date;x-amz-security-token;x-amz-target");
    }

    @Test
    void awsWebIdentityCredentialsComeFromStsAndAreReused(@TempDir Path dir) throws Exception {
        Path token = dir.resolve("token");
        Files.writeString(token, "projected-jwt");
        List<Recorded> seen = new ArrayList<>();
        URI sts = serve(seen, r -> new Object[] {200, "{\"AssumeRoleWithWebIdentityResponse\":{\"AssumeRoleWithWebIdentityResult\":"
                + "{\"Credentials\":{\"AccessKeyId\":\"ASIAWEB\",\"SecretAccessKey\":\"k\",\"SessionToken\":\"st\",\"Expiration\":"
                + (Instant.now().getEpochSecond() + 3600) + "}}}}"});
        URI sm = serve(seen, r -> new Object[] {200, "{\"SecretString\":\"irsa-value\"}"});
        Map<String, String> env = Map.of("AWS_WEB_IDENTITY_TOKEN_FILE", token.toString(), "AWS_ROLE_ARN",
                "arn:aws:iam::123456789012:role/orqix");
        var client = new AwsSecretsManagerProvider.HttpClient(new SecretProviderSettings.Aws(true, "eu-west-1", sm, sts),
                http(), Clock.systemUTC(), env::get);
        assertThat(client.secretValue("prod/crm")).contains("irsa-value");
        assertThat(client.secretValue("prod/crm")).contains("irsa-value");
        List<Recorded> stsCalls = seen.stream().filter(r -> r.body().contains("AssumeRoleWithWebIdentity")).toList();
        assertThat(stsCalls).hasSize(1);
        assertThat(stsCalls.getFirst().body()).contains("WebIdentityToken=projected-jwt", "RoleSessionName=orqix-secrets");
        assertThat(seen.getLast().headers().get("authorization")).contains("Credential=ASIAWEB/");
    }

    // ---------------------------------------------------------------- Azure

    @Test
    void azureKeyVaultUsesWorkloadIdentityAndReadsTheLatestOrAPinnedVersion(@TempDir Path dir) throws Exception {
        Path federated = dir.resolve("azure-token");
        Files.writeString(federated, "federated-jwt");
        List<Recorded> seen = new ArrayList<>();
        URI login = serve(seen, r -> new Object[] {200, "{\"access_token\":\"aad-token\",\"expires_in\":\"3599\"}"});
        URI vault = serve(seen, r -> r.path().equals("/secrets/crm-token") || r.path().equals("/secrets/crm-token/abc123")
                ? new Object[] {200, "{\"value\":\"{\\\"token\\\":\\\"kv-value\\\"}\",\"id\":\"x\"}"}
                : new Object[] {404, "{\"error\":{\"code\":\"SecretNotFound\"}}"});
        Map<String, String> env = Map.of("AZURE_FEDERATED_TOKEN_FILE", federated.toString(), "AZURE_CLIENT_ID", "client-1",
                "AZURE_TENANT_ID", "tenant-1");
        var client = new AzureKeyVaultProvider.HttpClient(new SecretProviderSettings.Azure(true, null,
                vault.toString(), URI.create(login + "/"), null), http(), Clock.systemUTC(), env::get);
        var provider = new AzureKeyVaultProvider(true, client);
        assertThat(provider.resolve(SecretRef.parse("azure-kv:orqix-prod/crm-token#token").orElseThrow())).contains("kv-value");
        assertThat(provider.resolve(SecretRef.parse("azure-kv:orqix-prod/crm-token/abc123#token").orElseThrow())).contains("kv-value");
        assertThat(provider.resolve(SecretRef.parse("azure-kv:orqix-prod/other").orElseThrow())).isEmpty();
        Recorded token = seen.stream().filter(r -> r.path().endsWith("/oauth2/v2.0/token")).findFirst().orElseThrow();
        assertThat(token.path()).isEqualTo("/tenant-1/oauth2/v2.0/token");
        assertThat(token.body()).contains("client_assertion=federated-jwt", "client_id=client-1",
                "scope=https%3A%2F%2Fvault.azure.net%2F.default");
        assertThat(seen.stream().filter(r -> r.path().endsWith("/oauth2/v2.0/token")).count()).isEqualTo(1);
        Recorded read = seen.stream().filter(r -> r.path().startsWith("/secrets/")).findFirst().orElseThrow();
        assertThat(read.query()).isEqualTo("api-version=7.4");
        assertThat(read.headers()).containsEntry("authorization", "Bearer aad-token");
    }

    @Test
    void azureFallsBackToTheManagedIdentityEndpoint() throws Exception {
        List<Recorded> seen = new ArrayList<>();
        URI imds = serve(seen, r -> new Object[] {200, "{\"access_token\":\"mi-token\",\"expires_in\":3600}"});
        URI vault = serve(seen, r -> new Object[] {200, "{\"value\":\"plain\"}"});
        var client = new AzureKeyVaultProvider.HttpClient(new SecretProviderSettings.Azure(true, "user-assigned",
                vault.toString(), null, URI.create(imds + "/metadata/identity/oauth2/token")), http(), Clock.systemUTC(),
                name -> null);
        assertThat(client.secret("orqix-prod", "crm-token", null)).contains("plain");
        Recorded token = seen.getFirst();
        assertThat(token.headers()).containsEntry("metadata", "true");
        assertThat(token.query()).contains("resource=https%3A%2F%2Fvault.azure.net", "client_id=user-assigned");
    }

    // ---------------------------------------------------------------- Google

    @Test
    void googleSecretManagerUsesTheMetadataTokenAndDecodesThePayload() throws Exception {
        List<Recorded> seen = new ArrayList<>();
        URI metadata = serve(seen, r -> new Object[] {200, "{\"access_token\":\"gce-token\",\"expires_in\":3599,\"token_type\":\"Bearer\"}"});
        String payload = Base64.getEncoder().encodeToString("{\"token\":\"gcp-value\"}".getBytes(StandardCharsets.UTF_8));
        URI api = serve(seen, r -> r.path().equals("/v1/projects/my-project/secrets/crm-token/versions/latest:access")
                || r.path().equals("/v1/projects/my-project/secrets/crm-token/versions/3:access")
                ? new Object[] {200, "{\"name\":\"x\",\"payload\":{\"data\":\"" + payload + "\"}}"}
                : new Object[] {404, "{\"error\":{\"status\":\"NOT_FOUND\"}}"});
        var client = new GoogleSecretManagerProvider.HttpClient(new SecretProviderSettings.Gcp(true, api, metadata), http(),
                Clock.systemUTC());
        var provider = new GoogleSecretManagerProvider(true, client);
        assertThat(provider.resolve(SecretRef.parse("gcp-sm:my-project/crm-token#token").orElseThrow())).contains("gcp-value");
        assertThat(provider.resolve(SecretRef.parse("gcp-sm:my-project/crm-token/3#token").orElseThrow())).contains("gcp-value");
        assertThat(provider.resolve(SecretRef.parse("gcp-sm:my-project/other").orElseThrow())).isEmpty();
        assertThat(provider.resolve(SecretRef.parse("gcp-sm:my-project/crm-token/abc").orElseThrow())).isEmpty();
        Recorded token = seen.getFirst();
        assertThat(token.path()).isEqualTo("/computeMetadata/v1/instance/service-accounts/default/token");
        assertThat(token.headers()).containsEntry("metadata-flavor", "Google");
        assertThat(seen.stream().filter(r -> r.path().startsWith("/computeMetadata")).count()).isEqualTo(1);
        assertThat(seen.get(1).headers()).containsEntry("authorization", "Bearer gce-token");
    }

    @Test
    void providerFailuresNameTheStoreAndStatusButNeverTheResponseBody() throws Exception {
        URI api = serve(new ArrayList<>(), r -> new Object[] {500, "{\"echo\":\"leaked-canary\"}"});
        var client = new GoogleSecretManagerProvider.HttpClient(new SecretProviderSettings.Gcp(true, api, api), http(),
                Clock.systemUTC());
        assertThatThrownBy(() -> client.access("my-project", "crm", "latest"))
                .isInstanceOf(SecretProvider.SecretProviderException.class)
                .hasMessageNotContaining("leaked-canary").hasMessageContaining("500");
    }

    // ---------------------------------------------------------------- Vault Kubernetes auth

    @Test
    void vaultKubernetesAuthLogsInWithTheServiceAccountTokenAndRenewsOnA403(@TempDir Path dir) throws Exception {
        Path jwt = dir.resolve("sa-token");
        Files.writeString(jwt, "service-account-jwt");
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        List<Recorded> seen = new ArrayList<>();
        URI vault = serve(seen, r -> {
            if (r.path().equals("/v1/auth/k8s-prod/login")) {
                int n = logins.incrementAndGet();
                return new Object[] {200, "{\"auth\":{\"client_token\":\"vault-token-" + n + "\",\"lease_duration\":3600}}"};
            }
            int read = reads.incrementAndGet();
            if (!("vault-token-" + logins.get()).equals(r.headers().get("x-vault-token")) || read == 3) {
                return new Object[] {403, "{\"errors\":[\"permission denied\"]}"};
            }
            return new Object[] {200, "{\"data\":{\"data\":{\"token\":\"k8s-value\"},\"metadata\":{\"version\":1}}}"};
        });
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));
        var provider = new VaultSecretProvider(new SecretProviderSettings.Vault(true, vault, "team-a", "kubernetes", null,
                null, "orqix", "k8s-prod", jwt), http(), clock);
        assertThat(provider.resolve(SecretRef.parse("vault:kv/apps/crm#token").orElseThrow())).contains("k8s-value");
        Recorded login = seen.getFirst();
        assertThat(login.body()).contains("\"role\":\"orqix\"", "\"jwt\":\"service-account-jwt\"");
        assertThat(login.headers()).containsEntry("x-vault-namespace", "team-a");
        assertThat(seen.get(1).path()).isEqualTo("/v1/kv/data/apps/crm");
        assertThat(provider.resolve(SecretRef.parse("vault:kv/apps/crm#token").orElseThrow())).contains("k8s-value");
        assertThat(logins).hasValue(1);
        // The token was revoked: the read gets 403, the provider logs in again once and succeeds.
        assertThat(provider.resolve(SecretRef.parse("vault:kv/apps/crm#token").orElseThrow())).contains("k8s-value");
        assertThat(logins).hasValue(2);
    }
}
