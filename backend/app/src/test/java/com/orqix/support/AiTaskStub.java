package com.orqix.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

import java.util.ArrayList;
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
 * WireMock stand-in for every model provider protocol of the S134 provider SPI: OpenAI-compatible
 * ({@code /openai/chat/completions}), Anthropic ({@code /anthropic/v1/messages}), Bedrock Converse
 * ({@code /bedrock/model/{id}/converse}), Azure OpenAI ({@code /azure/openai/deployments/{d}/chat/completions}) and
 * Gemini ({@code /gemini/v1beta/models/{m}:generateContent}). Answers are deterministic and follow keywords in the
 * request data, so AI jobs running concurrently need no scripted queue:
 * <ul>
 * <li>a classify answer is {@code unusual} when the data says "urgent", otherwise {@code routine};</li>
 * <li>"unsure" answers with confidence 0.4, otherwise 0.95;</li>
 * <li>"outage" answers 503; "garbled" answers invalid JSON on the first call of a conversation (the repair works);</li>
 * <li>"slow" answers after 1.5 seconds; "ignore previous instructions" is answered like any other data.</li>
 * </ul>
 * Never a real provider and never a real key. Every request body and its headers are recorded.
 */
public final class AiTaskStub {

    public static final String KEY = "test-ai-task-key-not-real";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Call(String path, Map<String, String> headers, JsonNode body) {
    }

    private static WireMockServer server;
    private static final List<Call> CALLS = new ArrayList<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private AiTaskStub() {
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

    public static synchronized List<Call> calls() {
        return List.copyOf(CALLS);
    }

    public static synchronized void reset() {
        CALLS.clear();
    }

    private static ResponseDefinition handle(Request request) {
        String path = request.getUrl();
        JsonNode body;
        try {
            body = JSON.readTree(request.getBodyAsString());
        } catch (RuntimeException e) {
            return json(400, "{\"error\":{\"message\":\"bad json\"}}");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaders().all().forEach(h -> headers.put(h.key().toLowerCase(), h.firstValue()));
        synchronized (AiTaskStub.class) {
            CALLS.add(new Call(path, headers, body));
        }
        String all = body.toString();
        String data = requestData(all);
        boolean repairTurn = all.contains("Your answer was not valid");
        if (data.contains("outage")) {
            return json(503, "{\"error\":{\"code\":503,\"message\":\"outage\"}}");
        }
        String answer;
        if (data.contains("garbled") && !repairTurn) {
            answer = "Sure! The label is probably routine.";
        } else {
            answer = answer(all, data);
        }
        ResponseDefinitionBuilder builder;
        if (path.startsWith("/anthropic/")) {
            ObjectNode r = JSON.createObjectNode();
            r.put("id", "msg_" + SEQUENCE.incrementAndGet()).put("type", "message").put("role", "assistant")
                    .put("model", body.path("model").asString()).put("stop_reason", "end_turn");
            r.putArray("content").addObject().put("type", "text").put("text", answer);
            r.putObject("usage").put("input_tokens", 300).put("output_tokens", 40).put("cache_read_input_tokens", 250);
            builder = response(r);
        } else if (path.startsWith("/bedrock/")) {
            ObjectNode r = JSON.createObjectNode();
            ArrayNode content = r.putObject("output").putObject("message").put("role", "assistant").putArray("content");
            if (body.has("toolConfig") && !answer.startsWith("Sure")) {
                content.addObject().putObject("toolUse").put("toolUseId", "t1").put("name", "answer")
                        .set("input", JSON.readTree(answer));
            } else {
                content.addObject().put("text", answer);
            }
            r.put("stopReason", "tool_use");
            r.putObject("usage").put("inputTokens", 310).put("outputTokens", 41);
            builder = response(r);
        } else if (path.startsWith("/gemini/")) {
            ObjectNode r = JSON.createObjectNode();
            ObjectNode candidate = r.putArray("candidates").addObject();
            candidate.putObject("content").put("role", "model").putArray("parts").addObject().put("text", answer);
            candidate.put("finishReason", "STOP");
            r.putObject("usageMetadata").put("promptTokenCount", 320).put("candidatesTokenCount", 42);
            r.put("modelVersion", "gemini-test-001");
            builder = response(r);
        } else if (path.contains("/chat/completions")) {
            ObjectNode r = JSON.createObjectNode();
            r.put("id", "chatcmpl-" + SEQUENCE.incrementAndGet()).put("model", body.path("model").asString("deployment"));
            ObjectNode choice = r.putArray("choices").addObject();
            choice.put("index", 0).put("finish_reason", "stop");
            choice.putObject("message").put("role", "assistant").put("content", answer);
            r.putObject("usage").put("prompt_tokens", 330).put("completion_tokens", 43);
            builder = response(r);
        } else {
            return json(404, "{\"error\":{\"message\":\"not found\"}}");
        }
        if (data.contains("slow")) {
            builder.withFixedDelay(1500);
        }
        return builder.build();
    }

    /** The request data between the tags (all user text when absent). */
    private static String requestData(String all) {
        int start = all.indexOf("<request_data>");
        int end = all.indexOf("</request_data>");
        return start >= 0 && end > start ? all.substring(start, end) : all;
    }

    private static String answer(String all, String data) {
        double confidence = data.contains("unsure") ? 0.4 : 0.95;
        ObjectNode a = JSON.createObjectNode();
        if (all.contains("one classify task")) {
            String label = data.contains("urgent") ? "unusual" : "routine";
            a.put("label", label).put("confidence", confidence);
            String quote = data.contains("urgent") ? "urgent" : "Replacement";
            a.putArray("reasons").addObject().put("text", "The request says so.").put("quote", quote);
        } else if (all.contains("one extract task")) {
            ObjectNode fields = a.putObject("fields");
            fields.put("invoiceNumber", "INV-42");
            fields.put("amount", 120.5);
            a.putObject("fieldConfidence").put("invoiceNumber", confidence).put("amount", confidence);
            a.put("confidence", confidence);
        } else if (all.contains("one score task")) {
            a.put("score", data.contains("urgent") ? 80 : 20).put("confidence", confidence);
            a.putArray("reasons").addObject().put("text", "Risk factors.").put("quote", "");
        } else if (all.contains("one recommend task")) {
            a.put("recommendation", "approve").put("rationale", "Within budget.").put("confidence", confidence);
        } else {
            a.put("summary", "A short summary.").put("confidence", confidence);
        }
        return a.toString();
    }

    private static ResponseDefinitionBuilder response(JsonNode body) {
        return new ResponseDefinitionBuilder().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(body.toString());
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
            return "ai-task-stub";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }
    }
}
