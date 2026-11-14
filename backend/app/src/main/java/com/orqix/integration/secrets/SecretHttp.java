package com.orqix.integration.secrets;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Small HTTP and JSON helpers shared by the remote secret providers. Responses are capped at 1 MiB; error messages
 * carry the store name and status only, never a response body (it could echo a value).
 */
final class SecretHttp {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BODY = 1024 * 1024;

    private final HttpClient client;
    private final Duration timeout;

    SecretHttp(Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(), timeout);
    }

    SecretHttp(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    record Response(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        JsonNode json() {
            try {
                return JSON.readTree(body == null || body.isEmpty() ? "{}" : body);
            } catch (RuntimeException e) {
                throw new SecretProvider.SecretProviderException("The secret store returned an unreadable answer.");
            }
        }
    }

    Response send(String store, HttpRequest.Builder request) {
        HttpRequest built = request.timeout(timeout).build();
        try {
            HttpResponse<InputStream> response = client.send(built, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_BODY + 1);
                if (bytes.length > MAX_BODY) {
                    throw new SecretProvider.SecretProviderException(store + " returned more than 1 MiB.");
                }
                return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new SecretProvider.SecretProviderException(store + " could not be reached (" + e.getClass().getSimpleName() + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretProvider.SecretProviderException(store + " call was interrupted.");
        }
    }

    static String form(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((k, v) -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(enc(k)).append('=').append(enc(v));
        });
        return out.toString();
    }

    static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Percent-encodes each segment of a slash-separated path and keeps the slashes. */
    static String path(String value) {
        StringBuilder out = new StringBuilder();
        for (String segment : value.split("/")) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(enc(segment));
        }
        return out.toString();
    }

    static URI base(URI configured, String fallback) {
        String value = configured == null ? fallback : configured.toString();
        return URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    }

    /**
     * Picks one field of a JSON secret. Without a key the whole value is returned. A missing field, or a value that
     * is not a JSON object when a key is asked for, gives empty.
     */
    static Optional<String> select(String value, String key) {
        if (value == null) {
            return Optional.empty();
        }
        if (key == null) {
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        JsonNode tree;
        try {
            tree = JSON.readTree(value);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return field(tree, key);
    }

    static Optional<String> field(JsonNode object, String key) {
        if (object == null || !object.isObject() || !object.has(key) || object.get(key).isNull()) {
            return Optional.empty();
        }
        JsonNode field = object.get(key);
        String text = field.isString() ? field.asString() : JSON.writeValueAsString(field);
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
