package com.orqix.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import tools.jackson.databind.json.JsonMapper;

class OAuthTokenClientTest {
    private final Json json = new Json(JsonMapper.builder().build());
    private final UUID workspace = UUID.randomUUID();
    private WireMockServer server;
    private EgressPolicy egress;
    private OAuthTokenClient tokens;
    private final MutableClock clock = new MutableClock();
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-18T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @BeforeEach void setup() {
        server = new WireMockServer(0); server.start();
        String host = "localhost:" + server.port();
        egress = new EgressPolicy(new OrqixProperties(null, null, null,
                new OrqixProperties.Egress(List.of(host), List.of(host), List.of(host)), null, null, null, null, null));
        tokens = new OAuthTokenClient(egress, json, clock);
        token("first-token", 300);
    }
    @AfterEach void stop() { server.stop(); }
    private void token(String value, int seconds) {
        server.stubFor(post(urlEqualTo("/token")).willReturn(okJson(json.write(json.object()
                .put("token_type", "Bearer").put("access_token", value).put("expires_in", seconds)))));
    }
    private String configuration(String secret) {
        return json.write(json.object().put("tokenUrl", server.baseUrl() + "/token")
                .put("clientId", "client:id").put("clientSecret", secret).put("scope", "read write"));
    }
    @Test void authenticatesUsingEncodedBasicCredentialsAndReusesThenRenewsToken() {
        String config = configuration("private secret");
        assertThat(tokens.resolve(workspace, "service", config)).contains("first-token");
        assertThat(tokens.resolve(workspace, "service", config)).contains("first-token");
        server.verify(1, postRequestedFor(urlEqualTo("/token"))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("client%3Aid", "private+secret"))
                .withRequestBody(equalTo("grant_type=client_credentials&scope=read+write")));
        token("renewed-token", 300);
        clock.now = clock.now.plusSeconds(271);
        assertThat(tokens.resolve(workspace, "service", config)).contains("renewed-token");
        server.verify(2, postRequestedFor(urlEqualTo("/token")));
    }
    @Test void isolatesWorkspacesDetectsRotationAndInvalidatesRevokedTokens() {
        String config = configuration("old-secret");
        assertThat(tokens.resolve(workspace, "service", config)).isPresent();
        assertThat(tokens.resolve(UUID.randomUUID(), "service", config)).isPresent();
        token("rotated-token", 300);
        assertThat(tokens.resolve(workspace, "service", configuration("new-secret"))).contains("rotated-token");
        tokens.invalidate(workspace, "service");
        assertThat(tokens.resolve(workspace, "service", configuration("new-secret"))).isPresent();
        server.verify(4, postRequestedFor(urlEqualTo("/token")));
    }
    @Test void concurrentCallersShareOneTokenRequest() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) calls.add(() -> tokens.resolve(workspace, "service", configuration("secret")).orElseThrow());
            for (var result : pool.invokeAll(calls)) assertThat(result.get()).isEqualTo("first-token");
        }
        server.verify(1, postRequestedFor(urlEqualTo("/token")));
    }
    @Test void deniesUnapprovedEndpointAndRechecksDnsForCachedCredentials() {
        String config = configuration("secret");
        assertThat(tokens.resolve(workspace, "service", config)).isPresent();
        egress.setResolver(host -> { throw new java.net.UnknownHostException(); });
        assertThat(tokens.resolve(workspace, "service", config)).isEmpty();
        assertThat(tokens.resolve(workspace, "service", config.replace(server.baseUrl(), "https://unapproved.example"))).isEmpty();
        server.verify(1, postRequestedFor(urlEqualTo("/token")));
    }
    @Test void rejectsMalformedAndOversizedResponsesAndDoesNotFollowRedirects() {
        for (String response : List.of("{}", "{\"access_token\":\"secret\",\"token_type\":\"Basic\",\"expires_in\":300}",
                "{\"access_token\":\"secret\\r\\nInjected: yes\",\"token_type\":\"Bearer\",\"expires_in\":300}",
                "{\"access_token\":\"secret\",\"token_type\":\"Bearer\",\"expires_in\":0}", "x".repeat(17000))) {
            server.stubFor(post(urlEqualTo("/token")).willReturn(okJson(response)));
            assertThat(tokens.resolve(workspace, "service", configuration("secret"))).isEmpty();
        }
        server.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(302).withHeader("Location", server.baseUrl() + "/steal")));
        assertThat(tokens.resolve(workspace, "service", configuration("secret"))).isEmpty();
        server.verify(0, anyRequestedFor(urlEqualTo("/steal")));
    }
    @Test void refusesMissingAndUnknownCredentialFieldsWithoutNetworkTraffic() {
        for (String config : List.of("not-json", "{}", configuration("secret").replace("\"scope\":", "\"unexpected\":"),
                configuration("secret").replace("read write", "read\\nwrite"))) {
            assertThat(tokens.resolve(workspace, "service", config)).isEmpty();
        }
        assertThat(tokens.resolve(null, "service", configuration("secret"))).isEmpty();
        server.verify(0, postRequestedFor(urlEqualTo("/token")));
    }

    @Test void rejectedBearerInvalidatesCacheWithoutReplayingAWrite() {
        var secrets = org.mockito.Mockito.mock(com.orqix.integration.internal.SecretStore.class);
        var executor = new HttpExecutor(json, secrets, null);
        var operation = new ConnectionDefinition.Operation("write", "Write", "POST", "/write", json.object(), json.object(),
                java.util.Set.of(200), ConnectionDefinition.EffectClass.NON_IDEMPOTENT, null, 5, false, null);
        var definition = new ConnectionDefinition("service", 1, "Service", json.object(), "hash", java.util.Map.of("write",operation), "http");
        server.stubFor(post(urlEqualTo("/write")).willReturn(aResponse().withStatus(401)));
        var result = executor.call(new HttpExecutor.Target(server.baseUrl(), "oauth2ClientCredentials", null, "service", workspace),
                definition, operation, json.object(), java.util.Map.of(), "effect-key", 5, "token-value");
        assertThat(result.outcome()).isEqualTo(HttpExecutor.Outcome.PERMANENT);
        assertThat(result.detail()).doesNotContain("token-value");
        org.mockito.Mockito.verify(secrets).invalidateOAuth(workspace,"service");
        server.verify(1, postRequestedFor(urlEqualTo("/write")).withHeader("Authorization",equalTo("Bearer token-value")));
    }
}
