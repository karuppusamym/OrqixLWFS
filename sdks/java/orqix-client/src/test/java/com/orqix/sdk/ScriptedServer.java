package com.orqix.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A loopback HTTP server that answers from a script: each rule matches a method and path suffix and answers with a
 * status, JSON body and headers; a rule may be used once or always. Every request is recorded.
 */
public final class ScriptedServer implements AutoCloseable {

    public record Received(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private record Rule(String method, String pathSuffix, int status, String body, Map<String, String> headers,
            boolean always) {
    }

    private final HttpServer server;
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public ScriptedServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    /** The base URL of the API on this server. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    public ScriptedServer once(String method, String pathSuffix, int status, String body) {
        return once(method, pathSuffix, status, body, Map.of());
    }

    public ScriptedServer once(String method, String pathSuffix, int status, String body, Map<String, String> headers) {
        rules.add(new Rule(method, pathSuffix, status, body, headers, false));
        return this;
    }

    public ScriptedServer always(String method, String pathSuffix, int status, String body) {
        rules.add(new Rule(method, pathSuffix, status, body, Map.of(), true));
        return this;
    }

    public List<Received> received() {
        return new ArrayList<>(received);
    }

    public List<Received> received(String method, String pathSuffix) {
        return received.stream().filter(r -> r.method().equals(method) && r.path().endsWith(pathSuffix)).toList();
    }

    /** Waits up to {@code seconds} for a request matching {@code test}. */
    public Received await(Predicate<Received> test, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            for (Received r : received) {
                if (test.test(r)) {
                    return r;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No matching request within " + seconds + " s; received " + received);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getRawPath();
        received.add(new Received(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                Map.copyOf(exchange.getRequestHeaders()), body));
        Rule match = null;
        for (Rule rule : rules) {
            if (rule.method().equals(exchange.getRequestMethod()) && path.endsWith(rule.pathSuffix())) {
                match = rule;
                break;
            }
        }
        if (match == null) {
            reply(exchange, 500, "{\"code\":\"INTERNAL_ERROR\",\"detail\":\"no scripted answer for " + path + "\"}",
                    Map.of());
            return;
        }
        if (!match.always()) {
            rules.remove(match);
        }
        reply(exchange, match.status(), match.body(), match.headers());
    }

    private static void reply(HttpExchange exchange, int status, String body, Map<String, String> headers)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) {
            exchange.getResponseHeaders().add("Content-Type",
                    status >= 400 ? "application/problem+json" : "application/json");
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
