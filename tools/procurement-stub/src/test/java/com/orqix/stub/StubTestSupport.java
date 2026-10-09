package com.orqix.stub;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared plumbing for the stub tests. Every test talks to the real embedded server over HTTP on a random port
 * (no MockMvc), because several fault modes only exist at the socket level.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "stub.commit-then-timeout-ms=1500")
abstract class StubTestSupport {

    protected static final String VALID_BODY = "{\"title\":\"Laptop\",\"amount\":1800.07,\"costCenter\":\"IT\"}";
    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    protected int port;

    protected final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    protected record Response(int status, String body, java.net.http.HttpHeaders headers) {
        JsonNode json() {
            return JSON.readTree(body);
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    @BeforeEach
    void resetStub() throws Exception {
        Response reset = post("/__reset", null, null);
        if (reset.status() != 200) {
            throw new IllegalStateException("reset failed: " + reset);
        }
    }

    protected String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    protected Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body(), response.headers());
    }

    protected Response get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url(path))).GET().timeout(Duration.ofSeconds(10)).build());
    }

    /** POSTs {@code body} (null = empty) with the Idempotency-Key header when {@code key} is not null. */
    protected Response post(String path, String key, String body) throws IOException, InterruptedException {
        return send(postRequest(path, key, body, Duration.ofSeconds(10)));
    }

    protected HttpRequest postRequest(String path, String key, String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return builder.build();
    }

    protected Response createOrder(String key, String body) throws IOException, InterruptedException {
        return post("/orders", key, body);
    }

    protected Response arm(String faultJson) throws IOException, InterruptedException {
        return post("/__faults", null, faultJson);
    }

    protected Response lookup(String key) throws IOException, InterruptedException {
        return get("/orders?idempotencyKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
    }

    protected int storedOrderCount() throws IOException, InterruptedException {
        return get("/__orders").json().get("count").intValue();
    }
}
