package com.orqix.support;

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
import tools.jackson.databind.node.ObjectNode;

/**
 * WireMock stand-in for OpenRouter used by the agent tests (S13 section 10): {@code GET /api/v1/models},
 * {@code GET /api/v1/key} and {@code POST /api/v1/chat/completions} answering from a queue of scripted replies that can
 * carry tool calls. Every chat request body and its headers are recorded, so tests can check what the model was shown
 * (tools offered, messages, response format, price caps) and what was never sent.
 */
public final class AgentLlmStub {

    public static final String KEY = "test-agent-openrouter-key-not-real";
    public static final String MODEL = "deepseek/deepseek-v3.2";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Reply(int status, String body, int delayMs, Map<String, String> headers) {
    }

    private static WireMockServer server;
    private static final Deque<Reply> REPLIES = new ArrayDeque<>();
    private static final List<JsonNode> REQUESTS = new ArrayList<>();
    private static final List<Map<String, String>> HEADERS = new ArrayList<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private AgentLlmStub() {
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
    }

    /** Queues a completion that asks for one tool call. */
    public static synchronized void toolCall(String callId, String tool, String argumentsJson, double cost) {
        ObjectNode message = JSON.createObjectNode().put("role", "assistant");
        message.putNull("content");
        ObjectNode call = message.putArray("tool_calls").addObject();
        call.put("id", callId).put("type", "function");
        call.putObject("function").put("name", tool).put("arguments", argumentsJson);
        queue(message, "tool_calls", cost, 0);
    }

    /** Queues a final answer. */
    public static synchronized void answer(String content, double cost) {
        queue(JSON.createObjectNode().put("role", "assistant").put("content", content), "stop", cost, 0);
    }

    /** Queues a final answer that arrives after {@code delayMs}. */
    public static synchronized void answerAfter(String content, double cost, int delayMs) {
        queue(JSON.createObjectNode().put("role", "assistant").put("content", content), "stop", cost, delayMs);
    }

    /** Queues an HTTP error with an OpenRouter error object. */
    public static synchronized void fail(int status) {
        REPLIES.addLast(new Reply(status, "{\"error\":{\"code\":" + status + ",\"message\":\"injected\"}}", 0, Map.of()));
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

    private static void queue(ObjectNode message, String finishReason, double cost, int delayMs) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", "gen-agent-" + SEQUENCE.incrementAndGet());
        body.put("model", MODEL);
        body.put("provider", "StubProvider");
        body.put("object", "chat.completion");
        ObjectNode choice = body.putArray("choices").addObject();
        choice.put("index", 0).put("finish_reason", finishReason);
        choice.set("message", message);
        ObjectNode usage = body.putObject("usage");
        usage.put("prompt_tokens", 900).put("completion_tokens", 60).put("total_tokens", 960).put("cost", cost);
        REPLIES.addLast(new Reply(200, JSON.writeValueAsString(body), delayMs, Map.of()));
    }

    private static synchronized ResponseDefinition handle(Request request) {
        String path = request.getUrl();
        String method = request.getMethod().getName();
        if ("GET".equals(method) && path.startsWith("/api/v1/models")) {
            return json(200, models());
        }
        if ("GET".equals(method) && path.startsWith("/api/v1/key")) {
            return json(200, "{\"data\":{\"label\":\"test\",\"usage\":0}}");
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

    private static String models() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode data = root.putArray("data");
        model(data, MODEL, "0.000000259", "0.00000042");
        model(data, "typesafe/jev-router", "-1", "-1");
        return JSON.writeValueAsString(root);
    }

    private static void model(ArrayNode data, String id, String prompt, String completion) {
        ObjectNode model = data.addObject();
        model.put("id", id);
        model.putObject("pricing").put("prompt", prompt).put("completion", completion);
        model.putArray("supported_parameters").add("max_tokens").add("response_format").add("temperature")
                .add("tools").add("structured_outputs");
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
            return "agent-llm-stub";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }
    }
}
