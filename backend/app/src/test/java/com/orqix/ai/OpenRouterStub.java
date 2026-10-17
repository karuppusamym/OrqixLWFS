package com.orqix.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * WireMock stand-in for OpenRouter's OpenAI-compatible API (S10 section 8): {@code GET /api/v1/models} (catalog with
 * prices and capabilities), {@code GET /api/v1/key}, and {@code POST /api/v1/chat/completions} answering from a queue
 * of scripted replies. Every chat request body and its headers are recorded so tests can check what was sent.
 */
public final class OpenRouterStub {

    public static final String KEY = "test-openrouter-key-not-real";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private record Reply(int status, String body, int delayMs, Map<String, String> headers) {
    }

    private static WireMockServer server;
    private static final Deque<Reply> REPLIES = new ArrayDeque<>();
    private static final List<JsonNode> REQUESTS = new ArrayList<>();
    private static final List<Map<String, String>> HEADERS = new ArrayList<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static volatile int keyStatus = 200;

    private OpenRouterStub() {
    }

    public static synchronized int port() {
        if (server == null) {
            WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort()
                    .extensions(new Transformer()).containerThreads(20));
            wm.start();
            wm.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(404)));
            server = wm;
        }
        return server.port();
    }

    public static String baseUrl() {
        return "http://localhost:" + port() + "/api/v1";
    }

    public static synchronized void reset() {
        REPLIES.clear();
        REQUESTS.clear();
        HEADERS.clear();
        keyStatus = 200;
    }

    /** Queues a successful completion whose message content is {@code content}. */
    public static synchronized void reply(String content, String model, double cost) {
        ObjectNode body = NODES.objectNode();
        body.put("id", "gen-test-" + SEQUENCE.incrementAndGet());
        body.put("model", model);
        body.put("provider", "StubProvider");
        body.put("object", "chat.completion");
        ArrayNode choices = body.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", content);
        ObjectNode usage = body.putObject("usage");
        usage.put("prompt_tokens", 1200);
        usage.put("completion_tokens", 800);
        usage.put("total_tokens", 2000);
        usage.put("cost", cost);
        REPLIES.addLast(new Reply(200, JSON.writeValueAsString(body), 0, Map.of()));
    }

    /** Queues an HTTP error (OpenRouter error object). */
    public static synchronized void fail(int status, Map<String, String> headers) {
        String body = "{\"error\":{\"code\":" + status + ",\"message\":\"injected\"}}";
        REPLIES.addLast(new Reply(status, body, 0, headers));
    }

    /** Queues a reply that arrives after {@code delayMs}. */
    public static synchronized void slow(String content, int delayMs) {
        delayed(content, "deepseek/deepseek-v3.2", 0.0001, delayMs);
    }

    /** Queues a completion that arrives after {@code delayMs}. */
    public static synchronized void delayed(String content, String model, double cost, int delayMs) {
        reply(content, model, cost);
        Reply last = REPLIES.removeLast();
        REPLIES.addLast(new Reply(last.status(), last.body(), delayMs, Map.of()));
    }

    /** Answer of {@code GET /key} (401 simulates rejected credentials). */
    public static void keyStatus(int status) {
        keyStatus = status;
    }

    public static synchronized List<JsonNode> requests() {
        return List.copyOf(REQUESTS);
    }

    public static synchronized List<Map<String, String>> headers() {
        return List.copyOf(HEADERS);
    }

    public static synchronized int pending() {
        return REPLIES.size();
    }

    private static synchronized ResponseDefinition handle(Request request) {
        String path = request.getUrl();
        String method = request.getMethod().getName();
        if ("GET".equals(method) && path.startsWith("/api/v1/models")) {
            return json(200, models());
        }
        if ("GET".equals(method) && path.startsWith("/api/v1/key")) {
            return json(keyStatus, keyStatus == 200 ? "{\"data\":{\"label\":\"test\",\"usage\":0}}"
                    : "{\"error\":{\"code\":" + keyStatus + ",\"message\":\"rejected\"}}");
        }
        if (!"POST".equals(method) || !path.startsWith("/api/v1/chat/completions")) {
            return json(404, "{\"error\":{\"code\":404,\"message\":\"not found\"}}");
        }
        REQUESTS.add(JSON.readTree(request.getBodyAsString()));
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaders().all().forEach(h -> headers.put(h.key().toLowerCase(), h.firstValue()));
        HEADERS.add(headers);
        Reply reply = REPLIES.pollFirst();
        if (reply == null) {
            return json(500, "{\"error\":{\"code\":500,\"message\":\"no scripted reply\"}}");
        }
        ResponseDefinitionBuilder builder = new ResponseDefinitionBuilder().withStatus(reply.status())
                .withHeader("Content-Type", "application/json").withBody(reply.body());
        reply.headers().forEach(builder::withHeader);
        if (reply.delayMs() > 0) {
            builder.withFixedDelay(reply.delayMs());
        }
        return builder.build();
    }

    /** Catalog: the default profiles, an over-cap model and an excluded model. */
    private static String models() {
        ObjectNode root = NODES.objectNode();
        ArrayNode data = root.putArray("data");
        model(data, "deepseek/deepseek-v3.2", "0.000000259", "0.00000042", true);
        model(data, "typesafe/jev-router", "-1", "-1", true);
        model(data, "openai/gpt-pricey", "0.00001", "0.00003", true);
        model(data, "anthropic/claude-opus-9", "0.000015", "0.000075", true);
        model(data, "plain/json-only", "0.0000001", "0.0000002", false);
        return JSON.writeValueAsString(root);
    }

    private static void model(ArrayNode data, String id, String prompt, String completion, boolean structured) {
        ObjectNode model = data.addObject();
        model.put("id", id);
        model.putObject("pricing").put("prompt", prompt).put("completion", completion);
        ArrayNode parameters = model.putArray("supported_parameters");
        parameters.add("max_tokens").add("response_format").add("temperature");
        if (structured) {
            parameters.add("structured_outputs");
        }
        model.putObject("architecture").putArray("input_modalities").add("text");
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
            return "openrouter-stub";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }
    }
}
