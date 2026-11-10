package com.orqix.catalog.git;

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
 * HTTP and JSON for the Git providers. Redirects are never followed (a redirect could leave the vetted host); answers
 * are capped at 8 MiB; errors carry the provider, step and status only.
 */
public final class GitHttp {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_BODY = 8 * 1024 * 1024;

    private final HttpClient client;
    private final Duration timeout;

    public GitHttp(Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(), timeout);
    }

    GitHttp(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    /** A provider answer. */
    public record Response(int status, byte[] body, Map<String, java.util.List<String>> headers) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public Optional<String> header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return Optional.of(e.getValue().getFirst());
                }
            }
            return Optional.empty();
        }

        JsonNode json(String provider) {
            try {
                return JSON.readTree(body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body);
            } catch (RuntimeException e) {
                throw new GitException(provider + " returned an unreadable answer.", status);
            }
        }
    }

    public Response send(String provider, String step, String method, String url, Map<String, String> headers,
            byte[] body, String contentType) {
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        } catch (IllegalArgumentException e) {
            throw new GitException(provider + " " + step + ": the address is not valid.", 0);
        }
        headers.forEach(request::header);
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        if (body != null && contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, publisher);
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_BODY + 1);
                if (bytes.length > MAX_BODY) {
                    throw new GitException(provider + " " + step + ": the answer is larger than 8 MiB.", response.statusCode());
                }
                return new Response(response.statusCode(), bytes, response.headers().map());
            }
        } catch (IOException e) {
            throw new GitException(provider + " " + step + ": the service could not be reached.", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException(provider + " " + step + ": interrupted.", 0);
        }
    }

    /** Sends and requires 2xx; 404 and other statuses become {@link GitException} with that status. */
    public Response expect(String provider, String step, String method, String url, Map<String, String> headers,
            byte[] body, String contentType) {
        Response response = send(provider, step, method, url, headers, body, contentType);
        if (!response.ok()) {
            throw new GitException(provider + " " + step + " failed with HTTP " + response.status() + ".", response.status());
        }
        return response;
    }

    public JsonNode json(String provider, String step, String method, String url, Map<String, String> headers,
            Object body) {
        byte[] bytes = body == null ? null : JSON.writeValueAsBytes(body);
        return expect(provider, step, method, url, headers, bytes, bytes == null ? null : "application/json")
                .json(provider);
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Encodes each path segment, keeping the slashes. */
    public static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(encode(segment));
        }
        return out.toString();
    }

    static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
