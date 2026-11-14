package com.orqix.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;
import com.orqix.shared.web.CorrelationId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimal MCP client for the Streamable HTTP transport (S13 section 5, S135): JSON-RPC 2.0 over POST, replies as
 * {@code application/json} or as a single {@code text/event-stream}. It implements {@code initialize},
 * {@code notifications/initialized}, {@code tools/list}, {@code tools/call} and {@code resources/read}. Server-initiated
 * requests that arrive on a response stream are answered while the stream is still open: {@code sampling/createMessage}
 * and every other request are refused, {@code ping} is answered, and {@code elicitation/create} is declined unless the
 * caller allowed it, in which case it is cancelled and handed to the caller (it becomes a task for a person, CG-M03).
 * {@code notifications/tools/list_changed} sets a flag the caller checks. Hard bounds that matter for safety live here:
 * redirects are never followed, every response is capped at 1 MiB, every call has a timeout derived from one execution
 * deadline, and nothing is ever logged. The caller has already applied the egress policy to the endpoint.
 */
@Component
public class McpClient {

    /** Protocol versions this client speaks, newest first. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    private static final int MAX_TOOL_PAGES = 5;
    private static final Duration CALL_CAP = Duration.ofSeconds(15);
    static final int MAX_ELICITATION_CHARS = 2000;

    /** @param bearerToken null for auth type none */
    public record Endpoint(String url, String bearerToken) {
    }

    /** @param elicitation true: the client declares the elicitation capability and hands requests to the caller */
    public record Options(boolean elicitation) {
        public static final Options NONE = new Options(false);
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

    /** An elicitation the server asked for during a call: its message and the requested (flat) schema. */
    public record Elicitation(String message, JsonNode requestedSchema) {
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
        return connect(endpoint, deadline, Options.NONE);
    }

    public Session connect(Endpoint endpoint, Deadline deadline, Options options) {
        Session session = new Session(endpoint, deadline, options == null ? Options.NONE : options);
        try {
            session.initialize();
        } catch (RuntimeException e) {
            session.close();
            throw e;
        }
        return session;
    }

    /** One MCP session (one execution). Not thread safe for callers; server requests are answered internally. */
    public final class Session implements AutoCloseable {

        private final Endpoint endpoint;
        private final Deadline deadline;
        private final Options options;
        private long nextId = 1;
        private volatile String sessionId;
        private volatile String protocolVersion = PROTOCOL_VERSIONS.get(0);
        private JsonNode serverCapabilities;
        private volatile boolean toolsChanged;
        private volatile int samplingRefused;
        private volatile Elicitation elicitation;
        private final List<CompletableFuture<?>> replies = new CopyOnWriteArrayList<>();

        private Session(Endpoint endpoint, Deadline deadline, Options options) {
            this.endpoint = endpoint;
            this.deadline = deadline;
            this.options = options;
        }

        private void initialize() {
            ObjectNode params = json.object();
            params.put("protocolVersion", PROTOCOL_VERSIONS.get(0));
            ObjectNode capabilities = params.putObject("capabilities");
            if (options.elicitation()) {
                capabilities.putObject("elicitation");
            }
            params.putObject("clientInfo").put("name", "orqix").put("version", "1");
            JsonNode result = call("initialize", params);
            String negotiated = result.path("protocolVersion").asString("");
            if (!PROTOCOL_VERSIONS.contains(negotiated)) {
                throw new McpException(FailureKind.INVALID, null, "Unsupported MCP protocol version");
            }
            protocolVersion = negotiated;
            serverCapabilities = result.path("capabilities");
            send(notification("notifications/initialized"));
        }

        /** True when the server declared the given capability ({@code tools}, {@code resources}, ...). */
        public boolean serverSupports(String capability) {
            return serverCapabilities != null && serverCapabilities.path(capability).isObject();
        }

        public String protocolVersion() {
            return protocolVersion;
        }

        /** True once the server sent {@code notifications/tools/list_changed}; cleared by {@link #clearToolsChanged}. */
        public boolean toolsChanged() {
            return toolsChanged;
        }

        public void clearToolsChanged() {
            toolsChanged = false;
        }

        /** Sampling requests of the server that were refused in this session. */
        public int samplingRefused() {
            return samplingRefused;
        }

        /** The elicitation of the last call (cancelled towards the server), or null; cleared when read. */
        public Elicitation takeElicitation() {
            Elicitation value = elicitation;
            elicitation = null;
            return value;
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

        /**
         * {@code resources/read} of one fixed URI: the text contents joined, capped at {@code maxChars}.
         *
         * @throws McpException INVALID when the server has no resources or sends no text
         */
        public String readResource(String uri, int maxChars) {
            if (!serverSupports("resources")) {
                throw new McpException(FailureKind.INVALID, null, "The MCP server offers no resources");
            }
            JsonNode result = call("resources/read", json.object().put("uri", uri));
            StringBuilder text = new StringBuilder();
            for (JsonNode content : result.path("contents")) {
                if (content.path("text").isString()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(content.path("text").asString());
                }
            }
            if (text.isEmpty()) {
                throw new McpException(FailureKind.INVALID, null, "The resource has no text content");
            }
            return text.length() > maxChars ? text.substring(0, maxChars) + "..." : text.toString();
        }

        /** Ends the session (best effort, never throws). */
        @Override
        public void close() {
            awaitReplies();
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

        /** Request whose JSON-RPC error is a protocol failure (initialize, tools/list, resources/read). */
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
            HttpResponse<byte[]> response = await(builder.build(), timeout, this::onStreamMessage);
            awaitReplies();
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
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            try {
                List<JsonNode> messages;
                if (contentType.startsWith("text/event-stream")) {
                    messages = parseEvents(new String(body, StandardCharsets.UTF_8));
                } else if (body.length == 0) {
                    messages = List.of();
                } else {
                    JsonNode parsed = json.parse(body);
                    messages = parsed.isArray() ? iterable(parsed) : List.of(parsed);
                    // A JSON reply cannot carry server requests that wait for an answer; notifications still count.
                    messages.forEach(m -> {
                        if ("notifications/tools/list_changed".equals(m.path("method").asString(""))) {
                            toolsChanged = true;
                        }
                    });
                }
                return new Reply(messages);
            } catch (IllegalArgumentException e) {
                throw new McpException(FailureKind.INVALID, status, "The MCP response is not valid JSON");
            }
        }

        /** Called for every complete event of a response stream, while the stream is still open. */
        private void onStreamMessage(JsonNode message) {
            if (!message.isObject() || !message.has("method")) {
                return;
            }
            String method = message.path("method").asString("");
            if ("notifications/tools/list_changed".equals(method)) {
                toolsChanged = true;
                return;
            }
            JsonNode id = message.get("id");
            if (id == null || id.isNull()) {
                return; // other notifications (progress, logging) are ignored
            }
            ObjectNode reply = json.object();
            reply.put("jsonrpc", "2.0");
            reply.set("id", id);
            switch (method) {
                case "ping" -> reply.putObject("result");
                case "elicitation/create" -> {
                    if (options.elicitation()) {
                        JsonNode params = message.path("params");
                        String text = params.path("message").asString("");
                        elicitation = new Elicitation(text.length() > MAX_ELICITATION_CHARS
                                ? text.substring(0, MAX_ELICITATION_CHARS) : text, params.path("requestedSchema"));
                        // The person answers later through a task; the server is told now, so it never waits on us.
                        reply.putObject("result").put("action", "cancel");
                    } else {
                        reply.putObject("result").put("action", "decline");
                    }
                }
                case "sampling/createMessage" -> {
                    samplingRefused++;
                    reply.putObject("error").put("code", -32601).put("message", "Sampling is not supported");
                }
                default -> reply.putObject("error").put("code", -32601).put("message", "Method not supported");
            }
            postReply(reply);
        }

        private void postReply(ObjectNode reply) {
            Duration timeout = min(deadline.remaining(), Duration.ofSeconds(5));
            if (timeout.isZero()) {
                return;
            }
            try {
                HttpRequest request = base(timeout).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.write(reply))).build();
                replies.add(http.sendAsync(request, HttpResponse.BodyHandlers.discarding()));
            } catch (RuntimeException e) {
                // best effort: the server times its request out
            }
        }

        private void awaitReplies() {
            for (CompletableFuture<?> reply : replies) {
                try {
                    reply.get(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException e) {
                    reply.cancel(true);
                }
            }
            replies.clear();
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

    private HttpResponse<byte[]> await(HttpRequest request, Duration timeout, Consumer<JsonNode> onStreamMessage) {
        WatchingHandler body = new WatchingHandler(onStreamMessage);
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, body);
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
            // The body limit wins over whatever error the client reported after the subscriber cancelled.
            if (body.exceeded() || cause instanceof HttpExecutor.ResponseTooLargeException) {
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

    /**
     * Body handler that caps the body like every HTTP call and, for an event stream, hands each complete event to the
     * session as soon as it arrives, so server requests are answered before the server finishes the stream.
     */
    private final class WatchingHandler implements HttpResponse.BodyHandler<byte[]> {

        private final Consumer<JsonNode> onStreamMessage;
        private final HttpExecutor.LimitedSubscriber.Holder limited = new HttpExecutor.LimitedSubscriber.Holder();

        WatchingHandler(Consumer<JsonNode> onStreamMessage) {
            this.onStreamMessage = onStreamMessage;
        }

        boolean exceeded() {
            return limited.exceeded();
        }

        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
            HttpResponse.BodySubscriber<byte[]> delegate = limited.handler().apply(info);
            boolean stream = info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT)
                    .startsWith("text/event-stream");
            return stream ? new EventWatcher(delegate, onStreamMessage) : delegate;
        }
    }

    /** Passes the bytes to the capped subscriber and scans them for complete events. */
    private final class EventWatcher implements HttpResponse.BodySubscriber<byte[]> {

        private final HttpResponse.BodySubscriber<byte[]> delegate;
        private final Consumer<JsonNode> onMessage;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        EventWatcher(HttpResponse.BodySubscriber<byte[]> delegate, Consumer<JsonNode> onMessage) {
            this.delegate = delegate;
            this.onMessage = onMessage;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                ByteBuffer copy = item.duplicate();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                if (pending.size() + bytes.length <= HttpExecutor.MAX_RESPONSE_BYTES) {
                    pending.writeBytes(bytes);
                }
            }
            drain();
            delegate.onNext(items);
        }

        private void drain() {
            String text = pending.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            int end = text.lastIndexOf("\n\n");
            if (end < 0) {
                return;
            }
            String complete = text.substring(0, end + 2);
            String rest = text.substring(end + 2);
            pending.reset();
            pending.writeBytes(rest.getBytes(StandardCharsets.UTF_8));
            for (JsonNode message : parseEvents(complete)) {
                try {
                    onMessage.accept(message);
                } catch (RuntimeException e) {
                    // a reply that cannot be sent never breaks the exchange
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            pending.writeBytes("\n\n".getBytes(StandardCharsets.UTF_8));
            drain();
            delegate.onComplete();
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
                // Ids are compared as text: 1 (int) and 1 (long) are the same JSON number. Server requests carry a
                // method and are never a response.
                if (message.isObject() && message.has("id") && !message.has("method")
                        && message.get("id").asString().equals(id.asString())
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
