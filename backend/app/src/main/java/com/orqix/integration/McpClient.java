package com.orqix.integration;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;
import com.orqix.shared.web.CorrelationId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimal MCP client for the Streamable HTTP transport (S13 section 5): JSON-RPC 2.0 over POST, replies as
 * {@code application/json} or as a single {@code text/event-stream}. It implements exactly {@code initialize},
 * {@code notifications/initialized}, {@code tools/list} and {@code tools/call}; server-initiated requests and the
 * GET stream are not used. Hard bounds that matter for safety live here: redirects are never followed, every response
 * is capped at 1 MiB, every call has a timeout derived from one execution deadline, and nothing is ever logged.
 * The caller has already applied the egress policy to the endpoint.
 */
@Component
public class McpClient {

    /** Protocol versions this client speaks, newest first. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26");
    private static final int MAX_TOOL_PAGES = 5;
    private static final Duration CALL_CAP = Duration.ofSeconds(15);

    /** @param bearerToken null for auth type none */
    public record Endpoint(String url, String bearerToken) {
    }

    public record Tool(String name, String description, JsonNode inputSchema) {
    }

    /**
     * @param isError    the server flagged the call as a tool failure (the model may still react to it)
     * @param text       concatenated text content, never null
     * @param structured {@code structuredContent} when the server sent it, else null
     */
    public record ToolResult(boolean isError, String text, JsonNode structured) {
    }

    /** One execution deadline shared by every call of that execution. */
    public static final class Deadline {

        private final long endNanos;

        public Deadline(Duration total) {
            this.endNanos = System.nanoTime() + total.toNanos();
        }

        public Duration remaining() {
            return Duration.ofNanos(Math.max(0, endNanos - System.nanoTime()));
        }

        public boolean expired() {
            return endNanos - System.nanoTime() <= 0;
        }
    }

    public enum FailureKind {
        /** Not reachable, timed out, 429 or 5xx: retry later. */
        UNAVAILABLE,
        /** 4xx (credentials, wrong endpoint): an administrator must fix the binding. */
        REJECTED,
        /** The server answered something that is not valid MCP. */
        INVALID
    }

    public static class McpException extends RuntimeException {

        private final transient FailureKind kind;
        private final transient Integer httpStatus;

        public McpException(FailureKind kind, Integer httpStatus, String message) {
            super(message);
            this.kind = kind;
            this.httpStatus = httpStatus;
        }

        public FailureKind kind() {
            return kind;
        }

        public Integer httpStatus() {
            return httpStatus;
        }
    }

    private final Json json;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public McpClient(Json json) {
        this.json = json;
    }

    /** Opens a session: {@code initialize} then {@code notifications/initialized}. */
    public Session connect(Endpoint endpoint, Deadline deadline) {
        Session session = new Session(endpoint, deadline);
        try {
            session.initialize();
        } catch (RuntimeException e) {
            session.close();
            throw e;
        }
        return session;
    }

    /** One MCP session (one execution). Not thread safe. */
    public final class Session implements AutoCloseable {

        private final Endpoint endpoint;
        private final Deadline deadline;
        private long nextId = 1;
        private String sessionId;
        private String protocolVersion = PROTOCOL_VERSIONS.get(0);

        private Session(Endpoint endpoint, Deadline deadline) {
            this.endpoint = endpoint;
            this.deadline = deadline;
        }

        private void initialize() {
            ObjectNode params = json.object();
            params.put("protocolVersion", PROTOCOL_VERSIONS.get(0));
            params.putObject("capabilities");
            params.putObject("clientInfo").put("name", "orqix").put("version", "1");
            JsonNode result = call("initialize", params);
            String negotiated = result.path("protocolVersion").asString("");
            if (!PROTOCOL_VERSIONS.contains(negotiated)) {
                throw new McpException(FailureKind.INVALID, null, "Unsupported MCP protocol version");
            }
            protocolVersion = negotiated;
            send(notification("notifications/initialized"));
        }

        /** {@code tools/list} over all pages (at most five pages). */
        public List<Tool> listTools() {
            List<Tool> tools = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < MAX_TOOL_PAGES; page++) {
                ObjectNode params = json.object();
                if (cursor != null) {
                    params.put("cursor", cursor);
                }
                JsonNode result = call("tools/list", params);
                for (JsonNode tool : result.path("tools")) {
                    String name = tool.path("name").asString("");
                    if (!name.isEmpty()) {
                        tools.add(new Tool(name, tool.path("description").asString(""), tool.path("inputSchema")));
                    }
                }
                cursor = result.path("nextCursor").asString(null);
                if (cursor == null || cursor.isEmpty()) {
                    break;
                }
            }
            return tools;
        }

        /** {@code tools/call}. A JSON-RPC error of the server is reported as a tool error, not as an exception. */
        public ToolResult callTool(String name, ObjectNode arguments) {
            ObjectNode params = json.object();
            params.put("name", name);
            params.set("arguments", arguments == null ? json.object() : arguments);
            JsonNode message = exchange(rpc("tools/call", params));
            if (message.has("error")) {
                return new ToolResult(true, "The tool could not be executed.", null);
            }
            JsonNode result = message.path("result");
            StringBuilder text = new StringBuilder();
            for (JsonNode part : result.path("content")) {
                if ("text".equals(part.path("type").asString()) && part.path("text").isString()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(part.path("text").asString());
                }
            }
            JsonNode structured = result.has("structuredContent") && !result.get("structuredContent").isNull()
                    ? result.get("structuredContent") : null;
            return new ToolResult(result.path("isError").asBoolean(false), text.toString(), structured);
        }

        /** Ends the session (best effort, never throws). */
        @Override
        public void close() {
            if (sessionId == null) {
                return;
            }
            try {
                HttpRequest.Builder builder = base(Duration.ofSeconds(2)).DELETE();
                http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException | RuntimeException e) {
                // ignored: the server expires idle sessions itself
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                sessionId = null;
            }
        }

        private ObjectNode rpc(String method, ObjectNode params) {
            ObjectNode message = json.object();
            message.put("jsonrpc", "2.0");
            message.put("id", nextId++);
            message.put("method", method);
            message.set("params", params);
            return message;
        }

        private ObjectNode notification(String method) {
            ObjectNode message = json.object();
            message.put("jsonrpc", "2.0");
            message.put("method", method);
            return message;
        }

        /** Request whose JSON-RPC error is a protocol failure (initialize, tools/list). */
        private JsonNode call(String method, ObjectNode params) {
            JsonNode message = exchange(rpc(method, params));
            if (message.has("error")) {
                throw new McpException(FailureKind.INVALID, null, method + " was refused by the MCP server");
            }
            JsonNode result = message.path("result");
            if (!result.isObject()) {
                throw new McpException(FailureKind.INVALID, null, method + " returned no result");
            }
            return result;
        }

        /** Sends a request and returns the JSON-RPC response message with the same id. */
        private JsonNode exchange(ObjectNode request) {
            JsonNode id = request.get("id");
            Reply reply = send(request);
            JsonNode message = reply.find(id);
            if (message == null) {
                throw new McpException(FailureKind.INVALID, null, "The MCP server sent no response to the request");
            }
            return message;
        }

        private Reply send(ObjectNode message) {
            Duration timeout = min(deadline.remaining(), CALL_CAP);
            if (timeout.isZero()) {
                throw new McpException(FailureKind.UNAVAILABLE, null, "The time limit was reached");
            }
            byte[] payload = json.write(message).getBytes(StandardCharsets.UTF_8);
            HttpRequest.Builder builder = base(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            HttpResponse<byte[]> response = await(builder.build(), timeout);
            int status = response.statusCode();
            if (status == 202 && !message.has("id")) {
                return new Reply(List.of());
            }
            if (status == 404 && sessionId != null) {
                throw new McpException(FailureKind.UNAVAILABLE, status, "The MCP session expired");
            }
            if (status == 429 || status == 408 || status >= 500) {
                throw new McpException(FailureKind.UNAVAILABLE, status, "The MCP server answered HTTP " + status);
            }
            if (status >= 300) {
                throw new McpException(FailureKind.REJECTED, status, "The MCP server answered HTTP " + status);
            }
            response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> {
                if (id.length() <= 256 && id.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
                    sessionId = id;
                }
            });
            byte[] body = response.body();
            if (body == null) {
                throw new McpException(FailureKind.INVALID, status, "The MCP response is too large");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
            try {
                if (contentType.startsWith("text/event-stream")) {
                    return new Reply(parseEvents(new String(body, StandardCharsets.UTF_8)));
                }
                if (body.length == 0) {
                    return new Reply(List.of());
                }
                JsonNode parsed = json.parse(body);
                return new Reply(parsed.isArray() ? iterable(parsed) : List.of(parsed));
            } catch (IllegalArgumentException e) {
                throw new McpException(FailureKind.INVALID, status, "The MCP response is not valid JSON");
            }
        }

        private HttpRequest.Builder base(Duration timeout) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.url()))
                    .timeout(timeout)
                    .header("Accept", "application/json, text/event-stream")
                    .header(CorrelationId.HEADER, CorrelationId.current());
            if (endpoint.bearerToken() != null) {
                builder.header("Authorization", "Bearer " + endpoint.bearerToken());
            }
            if (sessionId != null) {
                builder.header("Mcp-Session-Id", sessionId);
                builder.header("MCP-Protocol-Version", protocolVersion);
            }
            return builder;
        }
    }

    private HttpResponse<byte[]> await(HttpRequest request, Duration timeout) {
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request,
                info -> new HttpExecutor.LimitedSubscriber());
        try {
            return future.get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpException(FailureKind.UNAVAILABLE, null, "The call was interrupted");
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new McpException(FailureKind.UNAVAILABLE, null, "The MCP server did not answer in time");
        } catch (ExecutionException e) {
            future.cancel(true);
            Throwable cause = e.getCause();
            if (cause instanceof HttpExecutor.ResponseTooLargeException) {
                throw new McpException(FailureKind.INVALID, null, "The MCP response is too large");
            }
            if (cause instanceof HttpTimeoutException) {
                throw new McpException(FailureKind.UNAVAILABLE, null, "The MCP server did not answer in time");
            }
            if (isConnectFailure(cause)) {
                throw new McpException(FailureKind.UNAVAILABLE, null, "The MCP server could not be reached");
            }
            throw new McpException(FailureKind.UNAVAILABLE, null,
                    "The MCP call failed (" + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + ")");
        }
    }

    private static boolean isConnectFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof UnresolvedAddressException || t instanceof java.net.http.HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** JSON-RPC messages of one HTTP response. */
    private record Reply(List<JsonNode> messages) {

        JsonNode find(JsonNode id) {
            for (JsonNode message : messages) {
                // Ids are compared as text: 1 (int) and 1 (long) are the same JSON number.
                if (message.isObject() && message.has("id") && message.get("id").asString().equals(id.asString())
                        && (message.has("result") || message.has("error"))) {
                    return message;
                }
            }
            return null;
        }
    }

    /** Server-sent events: the {@code data} of each event (joined lines) is one JSON-RPC message. */
    List<JsonNode> parseEvents(String stream) {
        List<JsonNode> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : (stream + "\n\n").split("\r?\n", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    try {
                        messages.add(json.parse(data.toString()));
                    } catch (IllegalArgumentException e) {
                        // not a JSON-RPC message (for example a priming event): ignored
                    }
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.length() > 5 && line.charAt(5) == ' ' ? line.substring(6) : line.substring(5));
            }
        }
        return messages;
    }

    private static List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
