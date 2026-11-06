package com.orqix.support;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * A scripted MCP client (S136): Streamable HTTP over MockMvc with a bearer token, the session and protocol version
 * headers after initialize, JSON-RPC ids, and the elicitation round trip. Mirrors what an assistant host sends.
 */
public final class McpTestClient {

    /** One HTTP answer of the MCP endpoint. */
    public record Reply(int status, String contentType, String body, String sessionId, String wwwAuthenticate,
            JsonNode json, MvcResult mvcResult) {

        public JsonNode result() {
            return json.path("result");
        }

        public JsonNode error() {
            return json.path("error");
        }
    }

    private final MockMvc mvc;
    private final Json json;
    private final AtomicInteger ids = new AtomicInteger();
    private String token;
    private String sessionId;
    private String protocolVersion;
    private String accept = "application/json, text/event-stream";

    public McpTestClient(MockMvc mvc, Json json, String token) {
        this.mvc = mvc;
        this.json = json;
        this.token = token;
    }

    public McpTestClient token(String value) {
        this.token = value;
        return this;
    }

    public McpTestClient accept(String value) {
        this.accept = value;
        return this;
    }

    public String sessionId() {
        return sessionId;
    }

    public McpTestClient sessionId(String value) {
        this.sessionId = value;
        return this;
    }

    /** initialize + notifications/initialized; {@code elicitation} declares the form capability. */
    public Reply initialize(boolean elicitation) throws Exception {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        if (elicitation) {
            capabilities.put("elicitation", Map.of("form", Map.of()));
        }
        Reply reply = request("initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", capabilities,
                "clientInfo", Map.of("name", "orqix-test-client", "version", "1.0")));
        if (reply.status() == 200 && reply.result().has("protocolVersion")) {
            sessionId = reply.sessionId();
            protocolVersion = reply.result().path("protocolVersion").asString();
            Reply initialized = notify("notifications/initialized");
            if (initialized.status() != 202) {
                throw new AssertionError("initialized notification: " + initialized.status());
            }
        }
        return reply;
    }

    public Reply listTools() throws Exception {
        return request("tools/list", Map.of());
    }

    public Reply call(String tool, Map<String, ?> arguments) throws Exception {
        return request("tools/call", Map.of("name", tool, "arguments", arguments));
    }

    public Reply request(String method, Object params) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", ids.incrementAndGet());
        message.put("method", method);
        message.put("params", params);
        return post(json.write(message));
    }

    public Reply notify(String method) throws Exception {
        return post(json.write(Map.of("jsonrpc", "2.0", "method", method)));
    }

    /** The person's answer to an elicitation request with the given id. */
    public Reply answer(String requestId, String action, Boolean confirm) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        if (confirm != null) {
            result.put("content", Map.of("confirm", confirm));
        }
        return post(json.write(Map.of("jsonrpc", "2.0", "id", requestId, "result", result)));
    }

    public Reply post(String body) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON).header("Accept", accept)
                .content(body.getBytes(StandardCharsets.UTF_8));
        return send(builder);
    }

    public Reply send(MockHttpServletRequestBuilder builder) throws Exception {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        MvcResult result = mvc.perform(builder).andReturn();
        return reply(result);
    }

    public Reply reply(MvcResult result) throws Exception {
        MockHttpServletResponse response = result.getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        JsonNode parsed = json.object();
        if (text != null && !text.isBlank() && response.getContentType() != null
                && response.getContentType().startsWith("application/json")) {
            parsed = json.parse(text);
        }
        return new Reply(response.getStatus(), response.getContentType(), text, response.getHeader("Mcp-Session-Id"),
                response.getHeader("WWW-Authenticate"), parsed, result);
    }

    /** The JSON-RPC messages of an SSE body (one per {@code data:} line). */
    public java.util.List<JsonNode> events(String body) {
        java.util.List<JsonNode> events = new java.util.ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("data: ")) {
                events.add(json.parse(line.substring(6)));
            }
        }
        return events;
    }
}
