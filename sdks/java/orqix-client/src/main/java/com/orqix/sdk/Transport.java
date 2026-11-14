package com.orqix.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP exchange shared by {@link OrqixClient} and {@link CallbackTokenClient}. */
final class Transport {

    static final String VERSION = "0.1.0";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    /** Unknown response fields are ignored and nulls are not sent: additive server changes never break the SDK. */
    static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    record Answer<T>(int status, T body, HttpHeaders headers) {
    }

    private final String baseUrl;
    private final String workspaceId;
    private final HttpClient http;
    private final Duration timeout;
    private final Consumer<DeprecationNotice> deprecations;
    private final Set<OrqixOperation> reportedDeprecations = java.util.concurrent.ConcurrentHashMap.newKeySet();

    Transport(String baseUrl, String workspaceId, HttpClient http, Duration timeout,
            Consumer<DeprecationNotice> deprecations) {
        if (baseUrl == null || !(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
            throw new IllegalArgumentException("baseUrl must be the absolute URL of /api/v1, for example "
                    + "https://orqix.example/api/v1");
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId is required");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.workspaceId = workspaceId;
        this.http = http;
        this.timeout = timeout;
        this.deprecations = deprecations;
    }

    String workspaceId() {
        return workspaceId;
    }

    static String checkIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("An idempotency key is 8-128 characters of A-Z, a-z, 0-9, '.', '_', "
                    + "':' or '-'; use one key per logical command (a UUID is fine).");
        }
        return key;
    }

    /**
     * Sends one call. {@code pathValues} are the path parameters after {@code workspaceId}; {@code headers} are pairs
     * (name, value) with null values skipped; {@code allowNotModified} accepts 304 with a null body.
     */
    <T> Answer<T> send(OrqixOperation operation, List<String> pathValues, Map<String, ?> query, List<String> headers,
            Object body, Class<T> type, boolean allowNotModified) {
        List<String> values = new ArrayList<>();
        values.add(workspaceId);
        values.addAll(pathValues);
        StringBuilder url = new StringBuilder(baseUrl).append(operation.path(values.toArray(String[]::new)));
        char separator = '?';
        for (Map.Entry<String, ?> entry : query.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            url.append(separator).append(encode(entry.getKey())).append('=').append(encode(entry.getValue().toString()));
            separator = '&';
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(timeout)
                .header("Accept", "application/json, application/problem+json")
                .header("User-Agent", "orqix-sdk-java/" + VERSION);
        for (int i = 0; i + 1 < headers.size(); i += 2) {
            if (headers.get(i + 1) != null) {
                request.header(headers.get(i), headers.get(i + 1));
            }
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(operation.method(), HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        } else {
            request.method(operation.method(), HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw OrqixApiException.transport(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw OrqixApiException.transport(e);
        }
        reportDeprecation(operation, response.headers());
        int status = response.statusCode();
        if (status == 304 && allowNotModified) {
            return new Answer<>(status, null, response.headers());
        }
        if (status < 200 || status > 299) {
            throw problem(status, response);
        }
        byte[] payload = response.body();
        if (type == Void.class || payload == null || payload.length == 0) {
            return new Answer<>(status, null, response.headers());
        }
        try {
            return new Answer<>(status, JSON.readValue(payload, type), response.headers());
        } catch (JacksonException e) {
            throw new OrqixApiException(status, "RESPONSE_UNREADABLE", e.getOriginalMessage(), null, null);
        }
    }

    private void reportDeprecation(OrqixOperation operation, HttpHeaders headers) {
        headers.firstValue("Deprecation").ifPresent(value -> {
            if (reportedDeprecations.add(operation)) {
                deprecations.accept(new DeprecationNotice(operation, value,
                        headers.firstValue("Sunset").orElse(null), headers.firstValue("Link").orElse(null)));
            }
        });
    }

    private static OrqixApiException problem(int status, HttpResponse<byte[]> response) {
        String code = "HTTP_ERROR";
        String detail = null;
        String correlationId = null;
        try {
            JsonNode node = JSON.readTree(response.body());
            if (node != null && node.isObject()) {
                code = node.path("code").isString() ? node.path("code").asString() : code;
                detail = node.path("detail").isString() ? node.path("detail").asString() : null;
                correlationId = node.path("correlationId").isString() ? node.path("correlationId").asString() : null;
            }
        } catch (JacksonException | IllegalArgumentException ignored) {
            // Not a problem document (a proxy page, for example): keep HTTP_ERROR.
        }
        Long retryAfter = response.headers().firstValue("Retry-After").map(Transport::seconds).orElse(null);
        return new OrqixApiException(status, code, detail, correlationId, retryAfter);
    }

    private static Long seconds(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null; // an HTTP date: the caller falls back to its own delay
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
