package com.orqix.stub;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Demo MCP server (Streamable HTTP transport, protocol 2025-06-18 and 2025-03-26) with two deterministic tools,
 * {@code vendor_risk_lookup} and {@code vendor_financials} (docs/build/S13-agents-mcp.md section 9). Replies are
 * {@code application/json}; the fault mode {@code sse} switches to a {@code text/event-stream} reply. Sessions are issued
 * by {@code initialize} and required afterwards. A development and test fixture, never a real integration.
 *
 * <p>Control surface (not part of MCP): {@code POST|GET /__mcp/faults}, {@code GET /__mcp/calls}; {@code POST /__reset}
 * clears all of it.
 */
@RestController
public class McpController {

    static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final List<String> VERSIONS = List.of("2025-06-18", "2025-03-26");

    private static final JsonNode VENDOR_INPUT = parse("""
            {"type":"object","properties":{"vendorName":{"type":"string","minLength":1,"maxLength":200,
             "description":"Legal name of the vendor, exactly as the requester wrote it."}},
             "required":["vendorName"],"additionalProperties":false}""");

    private static final JsonNode RISK_OUTPUT = parse("""
            {"type":"object","properties":{"vendorName":{"type":"string"},"registryId":{"type":"string"},
             "riskScore":{"type":"integer","minimum":0,"maximum":100},"riskBand":{"type":"string","enum":["low","medium","high"]},
             "sanctionsHit":{"type":"boolean"}},
             "required":["vendorName","registryId","riskScore","riskBand","sanctionsHit"]}""");

    private static final JsonNode FINANCIALS_OUTPUT = parse("""
            {"type":"object","properties":{"vendorName":{"type":"string"},"annualRevenueUsd":{"type":"integer"},
             "creditRating":{"type":"string"}},"required":["vendorName","annualRevenueUsd","creditRating"]}""");

    private final McpState state;
    private final StubProperties properties;
    private final Schema vendorInput = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(VENDOR_INPUT);

    public McpController(McpState state, StubProperties properties) {
        this.state = state;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ MCP endpoint

    @PostMapping("/mcp")
    public ResponseEntity<?> post(HttpServletRequest request) throws IOException {
        String token = properties.mcpToken();
        if (!token.isEmpty() && !("Bearer " + token).equals(request.getHeader("Authorization"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", "Bearer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(rpcError(null, -32001, "Unauthorized"));
        }
        byte[] raw = readBody(request);
        if (raw == null) {
            return json(HttpStatus.CONTENT_TOO_LARGE, rpcError(null, -32600, "Request body is too large"), false);
        }
        JsonNode message;
        try {
            message = OrderController.JSON.readTree(raw);
        } catch (JacksonException e) {
            return json(HttpStatus.BAD_REQUEST, rpcError(null, -32700, "Parse error"), false);
        }
        if (message == null || !message.isObject() || !message.path("method").isString()) {
            return json(HttpStatus.BAD_REQUEST, rpcError(null, -32600, "Invalid request"), false);
        }
        String method = message.path("method").stringValue();
        JsonNode id = message.get("id");
        boolean notification = id == null || id.isNull();

        Optional<McpState.AppliedFault> fault = state.consume(method);
        boolean sse = false;
        boolean toolError = false;
        if (fault.isPresent()) {
            McpState.AppliedFault applied = fault.get();
            switch (applied.mode()) {
                case delay -> sleep(applied.delayMs());
                case status500 -> {
                    return json(HttpStatus.INTERNAL_SERVER_ERROR, rpcError(id, -32603, "injected status500"), false);
                }
                case status429 -> {
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1")
                            .contentType(MediaType.APPLICATION_JSON).body(rpcError(id, -32603, "injected status429"));
                }
                case status401 -> {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", "Bearer")
                            .contentType(MediaType.APPLICATION_JSON).body(rpcError(id, -32001, "injected status401"));
                }
                case invalidJson -> {
                    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"jsonrpc\": \"2.0\", oops");
                }
                case toolError -> toolError = true;
                case sse -> sse = true;
                case none -> {
                }
            }
        }
        if (notification) {
            return ResponseEntity.accepted().build();
        }

        String sessionId = request.getHeader(SESSION_HEADER);
        if (!"initialize".equals(method)) {
            if (sessionId == null || sessionId.isBlank()) {
                return json(HttpStatus.BAD_REQUEST, rpcError(id, -32600, "Mcp-Session-Id header is required"), sse);
            }
            if (!state.hasSession(sessionId)) {
                return json(HttpStatus.NOT_FOUND, rpcError(id, -32001, "Unknown session"), sse);
            }
        }
        JsonNode params = message.path("params");
        return switch (method) {
            case "initialize" -> {
                String session = UUID.randomUUID().toString();
                state.addSession(session);
                String requested = params.path("protocolVersion").asString("");
                ObjectNode result = OrderController.JSON.createObjectNode();
                result.put("protocolVersion", VERSIONS.contains(requested) ? requested : VERSIONS.get(0));
                result.putObject("capabilities").putObject("tools").put("listChanged", false);
                result.putObject("serverInfo").put("name", "orqix-procurement-stub").put("version", "0.1.0");
                result.put("instructions", "Demo vendor registry. Deterministic fixtures, never real data.");
                yield rpcResult(id, result, sse, session);
            }
            case "ping" -> rpcResult(id, OrderController.JSON.createObjectNode(), sse, null);
            case "tools/list" -> {
                state.record(method, null, null);
                yield rpcResult(id, toolList(), sse, null);
            }
            case "tools/call" -> callTool(id, params, sse, toolError);
            default -> json(HttpStatus.OK, rpcError(id, -32601, "Method not found"), sse);
        };
    }

    @GetMapping("/mcp")
    public ResponseEntity<?> getStream() {
        // This server never opens a server-to-client stream (allowed by the specification).
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST, DELETE").build();
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<?> delete(HttpServletRequest request) {
        return state.removeSession(request.getHeader(SESSION_HEADER)) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private ResponseEntity<?> callTool(JsonNode id, JsonNode params, boolean sse, boolean toolError) {
        String tool = params.path("name").asString("");
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : OrderController.JSON.createObjectNode();
        state.record("tools/call", tool, arguments);
        if (!"vendor_risk_lookup".equals(tool) && !"vendor_financials".equals(tool)) {
            return json(HttpStatus.OK, rpcError(id, -32602, "Unknown tool: " + abbreviate(tool)), sse);
        }
        List<Error> errors = vendorInput.validate(arguments);
        if (!errors.isEmpty()) {
            return json(HttpStatus.OK, rpcError(id, -32602, "Invalid arguments: " + errors.get(0).getMessage()), sse);
        }
        String vendorName = arguments.path("vendorName").stringValue();
        if (vendorName.isBlank()) {
            return rpcResult(id, toolResult("vendorName must not be blank", null, true), sse, null);
        }
        if (toolError) {
            return rpcResult(id, toolResult("The vendor registry is temporarily unavailable", null, true), sse, null);
        }
        ObjectNode structured = OrderController.JSON.createObjectNode();
        if ("vendor_risk_lookup".equals(tool)) {
            VendorRegistry.Vendor vendor = VendorRegistry.risk(vendorName);
            structured.put("vendorName", vendor.vendorName());
            structured.put("registryId", vendor.registryId());
            structured.put("riskScore", vendor.riskScore());
            structured.put("riskBand", vendor.riskBand());
            structured.put("sanctionsHit", vendor.sanctionsHit());
        } else {
            VendorRegistry.Financials financials = VendorRegistry.financials(vendorName);
            structured.put("vendorName", financials.vendorName());
            structured.put("annualRevenueUsd", financials.annualRevenueUsd());
            structured.put("creditRating", financials.creditRating());
        }
        return rpcResult(id, toolResult(OrderController.JSON.writeValueAsString(structured), structured, false), sse, null);
    }

    private static ObjectNode toolResult(String text, JsonNode structured, boolean isError) {
        ObjectNode result = OrderController.JSON.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        if (structured != null) {
            result.set("structuredContent", structured);
        }
        result.put("isError", isError);
        return result;
    }

    private static ObjectNode toolList() {
        ObjectNode result = OrderController.JSON.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        ObjectNode risk = tools.addObject();
        risk.put("name", "vendor_risk_lookup");
        risk.put("title", "Vendor registry risk lookup");
        risk.put("description", "Looks a vendor up in the vendor registry and returns its risk score (0 to 100), risk "
                + "band (low, medium, high), whether it is on a sanctions list, and its registry id.");
        risk.set("inputSchema", VENDOR_INPUT);
        risk.set("outputSchema", RISK_OUTPUT);
        ObjectNode financials = tools.addObject();
        financials.put("name", "vendor_financials");
        financials.put("title", "Vendor financial summary");
        financials.put("description", "Returns the annual revenue (USD) and credit rating of a vendor.");
        financials.set("inputSchema", VENDOR_INPUT);
        financials.set("outputSchema", FINANCIALS_OUTPUT);
        return result;
    }

    // ------------------------------------------------------------------ control surface

    @PostMapping(value = "/__mcp/faults", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setFault(HttpServletRequest request) throws IOException {
        JsonNode body;
        try (InputStream in = request.getInputStream()) {
            body = OrderController.JSON.readTree(in.readNBytes(4096));
        } catch (JacksonException e) {
            return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body is not valid JSON");
        }
        if (body == null || !body.isObject()) {
            return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "Body must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!List.of("mode", "times", "delayMs", "on").contains(field)) {
                return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "Unknown property '" + field + "'");
            }
        }
        McpState.Mode mode;
        try {
            mode = McpState.Mode.valueOf(body.path("mode").asString(""));
        } catch (IllegalArgumentException e) {
            return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT",
                    "'mode' must be one of none, delay, status500, status429, status401, toolError, invalidJson, sse");
        }
        Integer times = null;
        JsonNode timesNode = body.get("times");
        if (timesNode != null && !timesNode.isNull()) {
            if (!timesNode.isIntegralNumber() || !timesNode.canConvertToInt() || timesNode.intValue() < 1) {
                return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "'times' must be an integer >= 1");
            }
            times = timesNode.intValue();
        }
        long delayMs = 1000;
        JsonNode delayNode = body.get("delayMs");
        if (delayNode != null && !delayNode.isNull()) {
            if (mode != McpState.Mode.delay) {
                return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "'delayMs' only applies to mode delay");
            }
            if (!delayNode.isIntegralNumber() || !delayNode.canConvertToLong() || delayNode.longValue() < 0
                    || delayNode.longValue() > properties.maxFaultDelayMs()) {
                return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT",
                        "'delayMs' must be an integer between 0 and " + properties.maxFaultDelayMs());
            }
            delayMs = delayNode.longValue();
        }
        String on = body.path("on").asString("toolsCall");
        if (!on.equals("toolsCall") && !on.equals("any")) {
            return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "'on' must be toolsCall or any");
        }
        state.arm(mode, times, delayMs, on.equals("any"));
        return ResponseEntity.ok(faultView());
    }

    @GetMapping(value = "/__mcp/faults", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getFault() {
        return faultView();
    }

    /** Requests received so far (method, tool and arguments of tools/call): what a test asserts "was not called" on. */
    @GetMapping(value = "/__mcp/calls", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> calls() {
        List<Map<String, Object>> calls = state.calls().stream().map(call -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("at", call.at().toString());
            view.put("method", call.method());
            view.put("tool", call.tool());
            view.put("arguments", call.arguments());
            return view;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", calls.size());
        result.put("toolCalls", calls.stream().filter(c -> "tools/call".equals(c.get("method"))).count());
        result.put("calls", calls);
        return result;
    }

    private Map<String, Object> faultView() {
        McpState.Snapshot snapshot = state.snapshot();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("mode", snapshot.mode().name());
        view.put("remaining", snapshot.remaining());
        view.put("delayMs", snapshot.delayMs());
        view.put("on", snapshot.onAnyRequest() ? "any" : "toolsCall");
        view.put("applied", snapshot.applied());
        return view;
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<?> rpcResult(JsonNode id, JsonNode result, boolean sse, String sessionId) {
        ObjectNode message = OrderController.JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        message.set("result", result);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        return reply(builder, message, sse);
    }

    private static ObjectNode rpcError(JsonNode id, int code, String text) {
        ObjectNode message = OrderController.JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        if (id == null) {
            message.putNull("id");
        } else {
            message.set("id", id);
        }
        message.putObject("error").put("code", code).put("message", text);
        return message;
    }

    private ResponseEntity<?> json(HttpStatus status, JsonNode message, boolean sse) {
        return reply(ResponseEntity.status(status), message, sse);
    }

    private static ResponseEntity<?> reply(ResponseEntity.BodyBuilder builder, JsonNode message, boolean sse) {
        String text = OrderController.JSON.writeValueAsString(message);
        if (sse) {
            return builder.contentType(MediaType.TEXT_EVENT_STREAM)
                    .body("event: message\ndata: " + text + "\n\n");
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(text);
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(properties.maxBodyBytes() + 1);
            return bytes.length > properties.maxBodyBytes() ? null : bytes;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String value) {
        String clean = value.replaceAll("[^A-Za-z0-9_.-]", "?");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private static JsonNode parse(String text) {
        try {
            return OrderController.JSON.readTree(text.getBytes(StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

}
