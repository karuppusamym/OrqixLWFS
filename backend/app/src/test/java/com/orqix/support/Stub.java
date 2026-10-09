package com.orqix.support;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

/**
 * WireMock reproduction of the procurement stub contract (S1 section 6.2) with its fault modes, used by T-EFF and
 * T-RUN tests: {@code POST /orders} deduplicates by {@code Idempotency-Key} (same key + body returns the original
 * order with 200; different body 422; missing key 400), {@code GET /health}. Faults are queued per test.
 */
public final class Stub {

    public enum Mode {
        NONE, DELAY, STATUS500, STATUS400, STATUS429, COMMIT_THEN_TIMEOUT, DROP_CONNECTION, INVALID_BODY
    }

    private record Fault(Mode mode, int delayMs) {
    }

    private record Order(String id, String body) {
    }

    private static WireMockServer server;
    private static final Map<String, Order> ORDERS = new LinkedHashMap<>();
    private static final Deque<Fault> FAULTS = new ArrayDeque<>();
    private static final AtomicInteger POSTS = new AtomicInteger();
    private static final List<Map<String, String>> HEADERS = new ArrayList<>();
    private static int sequence;
    private static volatile boolean requireKey = true;

    private Stub() {
    }

    public static synchronized int port() {
        if (server == null) {
            WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort()
                    .extensions(new Transformer()).containerThreads(30));
            wm.start();
            wm.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(404)));
            server = wm;
        }
        return server.port();
    }

    public static String baseUrl() {
        return "http://localhost:" + port();
    }

    /** The nonIdempotent copy of createOrder sends no Idempotency-Key; the stub then creates an order per call. */
    public static void requireIdempotencyKey(boolean required) {
        requireKey = required;
    }

    public static synchronized void reset() {
        requireKey = true;
        ORDERS.clear();
        FAULTS.clear();
        POSTS.set(0);
        HEADERS.clear();
    }

    /** Queues {@code times} faults of {@code mode} for the next POST /orders calls. */
    public static synchronized void fault(Mode mode, int times, int delayMs) {
        for (int i = 0; i < times; i++) {
            FAULTS.addLast(new Fault(mode, delayMs));
        }
    }

    public static synchronized int orderCount() {
        return ORDERS.size();
    }

    public static int postCount() {
        return POSTS.get();
    }

    public static synchronized List<Map<String, String>> postHeaders() {
        return List.copyOf(HEADERS);
    }

    private static synchronized ResponseDefinition handle(Request request) {
        String path = request.getUrl();
        String method = request.getMethod().getName();
        if ("GET".equals(method) && path.startsWith("/health")) {
            return json(200, "{\"status\":\"UP\"}");
        }
        if (!"POST".equals(method) || !path.startsWith("/orders")) {
            return json(404, "{\"error\":\"not found\"}");
        }
        POSTS.incrementAndGet();
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaders().all().forEach(h -> headers.put(h.key().toLowerCase(), h.firstValue()));
        HEADERS.add(headers);
        Fault fault = FAULTS.pollFirst();
        Mode mode = fault == null ? Mode.NONE : fault.mode();
        switch (mode) {
            case STATUS500 -> {
                return json(500, "{\"error\":\"injected\"}");
            }
            case STATUS400 -> {
                return json(400, "{\"error\":\"injected\"}");
            }
            case STATUS429 -> {
                return json(429, "{\"error\":\"injected\"}");
            }
            case DROP_CONNECTION -> {
                return new ResponseDefinitionBuilder().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER).build();
            }
            default -> {
                // continue
            }
        }
        String key = request.getHeader("Idempotency-Key");
        String body = request.getBodyAsString();
        if (requireKey && mode != Mode.INVALID_BODY && key == null) {
            return json(400, "{\"error\":\"Idempotency-Key required\"}");
        }
        String storeKey = key == null ? "no-key-" + POSTS.get() : key;
        Order existing = ORDERS.get(storeKey);
        ResponseDefinitionBuilder response;
        if (existing != null) {
            if (!existing.body().equals(body)) {
                return json(422, "{\"error\":\"different body for key\"}");
            }
            response = new ResponseDefinitionBuilder().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"" + existing.id() + "\",\"status\":\"CREATED\"}");
        } else {
            Order order = new Order("PO-" + (++sequence), body);
            ORDERS.put(storeKey, order);
            if (mode == Mode.INVALID_BODY) {
                return json(201, "{\"status\":\"CREATED\"}");
            }
            response = new ResponseDefinitionBuilder().withStatus(201).withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"" + order.id() + "\",\"status\":\"CREATED\"}");
        }
        if (mode == Mode.COMMIT_THEN_TIMEOUT || mode == Mode.DELAY) {
            response.withFixedDelay(fault.delayMs());
        }
        return response.build();
    }

    private static ResponseDefinition json(int status, String body) {
        return new ResponseDefinitionBuilder().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(body).build();
    }

    private static final class Transformer implements ResponseDefinitionTransformerV2 {

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            return handle(serveEvent.getRequest());
        }

        @Override
        public String getName() {
            return "procurement-stub";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }
    }
}
