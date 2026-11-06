package com.orqix.ai.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.orqix.ai.internal.ProviderException;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * The one HTTP path of the provider adapters: JDK client, redirects never followed, 4 MiB response limit, status
 * classification shared with the OpenRouter client (401/403 credentials, 402 payment, 400/404/422 refused, the rest
 * unavailable). Bodies are never logged or carried in exceptions.
 */
@Component
public class ProviderHttp {

    private static final Logger log = LoggerFactory.getLogger(ProviderHttp.class);
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final Json json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ProviderHttp(Json json) {
        this.json = json;
    }

    public Json json() {
        return json;
    }

    /** POSTs {@code body} and returns the parsed JSON answer. */
    public JsonNode post(URI uri, Map<String, String> headers, byte[] body, Duration timeout, String what) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json");
        headers.forEach(builder::header);
        HttpResponse<byte[]> response;
        try {
            response = http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 0, null, what + ": timeout");
        } catch (IOException e) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 0, null,
                    what + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, 0, null, what + ": interrupted");
        }
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            Integer retryAfter = response.headers().firstValue("Retry-After").map(ProviderHttp::seconds).orElse(null);
            ProviderException failure = classify(status, retryAfter, what + ": HTTP " + status);
            log.warn("Model provider call failed: {} (kind {})", failure.getMessage(), failure.kind());
            throw failure;
        }
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, status, null, what + ": response too large");
        }
        try {
            return json.parse(response.body());
        } catch (IllegalArgumentException e) {
            throw new ProviderException(ProviderException.Kind.UNAVAILABLE, status, null, what + ": unreadable response");
        }
    }

    public byte[] bytes(JsonNode body) {
        return json.write(body).getBytes(StandardCharsets.UTF_8);
    }

    static ProviderException classify(int status, Integer retryAfter, String message) {
        ProviderException.Kind kind;
        if (status == 401 || status == 403) {
            kind = ProviderException.Kind.CREDENTIALS_REJECTED;
        } else if (status == 402) {
            kind = ProviderException.Kind.PAYMENT_REQUIRED;
        } else if (status == 400 || status == 404 || status == 422) {
            kind = ProviderException.Kind.REJECTED;
        } else {
            kind = ProviderException.Kind.UNAVAILABLE;
        }
        return new ProviderException(kind, status, retryAfter, message);
    }

    static URI join(URI base, String path) {
        String text = base.toString();
        return URI.create((text.endsWith("/") ? text.substring(0, text.length() - 1) : text) + path);
    }

    static Long longOrNull(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asLong() : null;
    }

    private static Integer seconds(String value) {
        try {
            int seconds = Integer.parseInt(value.trim());
            return seconds >= 0 ? Math.min(seconds, 3600) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
