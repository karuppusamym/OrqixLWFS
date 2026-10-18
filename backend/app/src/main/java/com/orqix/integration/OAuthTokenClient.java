package com.orqix.integration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import com.orqix.shared.json.Json;

/** Server-to-server OAuth. Credentials stay in mounted secrets; tokens stay in bounded process memory. */
@Component
public class OAuthTokenClient {
    private record Key(UUID workspace, String reference) {}
    private record Token(String configurationHash, String value, Instant expiresAt) {}
    private static final Set<String> FIELDS = Set.of("tokenUrl", "clientId", "clientSecret", "scope");
    private final ConcurrentHashMap<Key, Token> tokens = new ConcurrentHashMap<>();
    private final EgressPolicy egress;
    private final Json json;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3)).build();

    public OAuthTokenClient(EgressPolicy egress, Json json, Clock clock) {
        this.egress = egress; this.json = json; this.clock = clock;
    }

    /** Re-read and validate the credential and egress policy even when a token is cached. */
    public Optional<String> resolve(UUID workspace, String reference, String configuration) {
        if (workspace == null || reference == null || configuration == null || configuration.length() > 16384)
            return Optional.empty();
        try {
            var config = json.parse(configuration);
            if (!config.isObject() || !FIELDS.containsAll(config.propertyNames())) return Optional.empty();
            for (String field : Set.of("tokenUrl", "clientId", "clientSecret")) {
                if (!config.path(field).isString() || config.path(field).asString().isBlank()) return Optional.empty();
            }
            if (config.has("scope") && (!config.path("scope").isString()
                    || !config.path("scope").asString().matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+(?: [\\x21\\x23-\\x5B\\x5D-\\x7E]+)*")))
                return Optional.empty();
            String url = config.path("tokenUrl").asString();
            egress.check(url);
            String hash = Json.sha256Hex(configuration);
            Key key = new Key(workspace, reference);
            // A bounded cache and per-key compute prevent parallel calls from flooding the token endpoint.
            if (tokens.size() >= 256 && !tokens.containsKey(key)) {
                tokens.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
                if (tokens.size() >= 256) return Optional.empty();
            }
            Token token = tokens.compute(key, (ignored, previous) -> {
                if (previous != null && previous.configurationHash().equals(hash)
                        && previous.expiresAt().isAfter(clock.instant())) return previous;
                try {
                    Instant requestedAt = clock.instant();
                    String credentials = encode(config.path("clientId").asString()) + ":"
                            + encode(config.path("clientSecret").asString());
                    String body = "grant_type=client_credentials";
                    if (config.has("scope")) body += "&scope=" + encode(config.path("scope").asString());
                    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    var future = client.sendAsync(request, info -> new HttpExecutor.LimitedSubscriber(16384));
                    HttpResponse<byte[]> response;
                    try { response = future.get(6, TimeUnit.SECONDS); }
                    finally { if (!future.isDone()) future.cancel(true); }
                    if (response.statusCode() != 200) return null;
                    var result = json.parse(new String(response.body(), StandardCharsets.UTF_8));
                    var value = result.path("access_token");
                    var lifetime = result.path("expires_in");
                    if (!"Bearer".equalsIgnoreCase(result.path("token_type").asString())
                            || !value.isString() || !value.asString().matches("[A-Za-z0-9._~+/-]+=*")
                            || value.asString().length() > 8192 || !lifetime.isIntegralNumber()
                            || !lifetime.canConvertToLong() || lifetime.asLong() < 1 || lifetime.asLong() > 86400)
                        return null;
                    // Never extend expiry by network time; short-lived tokens are renewed for every subsequent call.
                    Instant expires = requestedAt.plusSeconds(lifetime.asLong());
                    if (!expires.isAfter(clock.instant())) return null;
                    return new Token(hash, value.asString(), expires.minusSeconds(Math.min(30, lifetime.asLong() / 10)));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); return null;
                } catch (Exception ex) {
                    return null; // No provider response, URL, credential or token is included in errors or logs.
                }
            });
            return token == null ? Optional.empty() : Optional.of(token.value());
        } catch (Exception ex) {
            tokens.remove(new Key(workspace, reference));
            return Optional.empty();
        }
    }

    public void invalidate(UUID workspace, String reference) { tokens.remove(new Key(workspace, reference)); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
