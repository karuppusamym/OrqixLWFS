package com.orqix.mcp.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.mcp.McpAuthFilter;
import com.orqix.mcp.McpProtocol;
import com.orqix.mcp.McpResource;
import com.orqix.mcp.McpSessions;
import com.orqix.mcp.McpStreams;
import com.orqix.mcp.McpTools;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * The MCP endpoint (S136 section 1): Streamable HTTP, one JSON-RPC message per POST, answered as JSON (or as an SSE
 * stream while asking the person to confirm a write). No server-initiated stream (GET answers 405) and sessions are
 * stateless signed IDs that cannot be ended (DELETE answers 405). Authentication happened in the MCP security chain.
 */
@RestController
public class McpEndpoint {

    static final int MAX_BODY_BYTES = 1_048_576;
    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String VERSION_HEADER = "MCP-Protocol-Version";

    private final McpProtocol protocol;
    private final McpSessions sessions;
    private final McpStreams streams;
    private final Json json;

    public McpEndpoint(McpProtocol protocol, McpSessions sessions, McpStreams streams, Json json) {
        this.protocol = protocol;
        this.sessions = sessions;
        this.streams = streams;
        this.json = json;
    }

    @PostMapping(McpResource.PATH)
    public void post(HttpServletRequest request, HttpServletResponse response) throws IOException {
        McpAuthFilter.Principal principal = (McpAuthFilter.Principal) request.getAttribute(McpAuthFilter.ATTRIBUTE);
        if (principal == null) {
            response.setStatus(401);
            return;
        }
        String accept = Optional.ofNullable(request.getHeader("Accept")).orElse("*/*").toLowerCase();
        boolean streamOk = accept.contains("text/event-stream");
        if (!streamOk && !accept.contains("application/json") && !accept.contains("*/*")) {
            write(response, 406, protocol.error(null, McpProtocol.INVALID_REQUEST,
                    "Accept application/json and text/event-stream."));
            return;
        }
        String contentType = request.getContentType();
        if (contentType != null && !contentType.toLowerCase().startsWith("application/json")) {
            write(response, 415, protocol.error(null, McpProtocol.INVALID_REQUEST, "Send application/json."));
            return;
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            write(response, 413, protocol.error(null, McpProtocol.INVALID_REQUEST, "The message is too large."));
            return;
        }
        JsonNode message;
        try {
            message = json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            write(response, 400, protocol.error(null, McpProtocol.PARSE_ERROR, "Parse error."));
            return;
        }
        McpTools.Context ctx = new McpTools.Context(principal.caller(), principal.client());
        boolean initialize = message != null && message.isObject() && "initialize".equals(message.path("method").asString(""));
        McpSessions.Session session = null;
        String sessionId = request.getHeader(SESSION_HEADER);
        if (!initialize && sessionId != null) {
            Optional<McpSessions.Session> found = sessions.read(sessionId, principal.caller().subjectId());
            if (found.isEmpty()) {
                write(response, 404, protocol.error(null, McpProtocol.INVALID_REQUEST,
                        "Unknown or expired session: initialize again."));
                return;
            }
            session = found.get();
        }
        String version = request.getHeader(VERSION_HEADER);
        if (!initialize && version != null && !McpProtocol.SUPPORTED.contains(version)) {
            write(response, 400, protocol.error(null, McpProtocol.INVALID_REQUEST,
                    "Unsupported MCP-Protocol-Version " + (version.length() > 20 ? version.substring(0, 20) : version)
                            + "; supported: " + String.join(", ", McpProtocol.SUPPORTED) + "."));
            return;
        }
        if (version == null) {
            version = session != null ? session.protocolVersion() : McpProtocol.DEFAULT_WITHOUT_HEADER;
        }
        McpProtocol.Reply reply = protocol.handle(ctx, session, version, message, streamOk);
        switch (reply) {
            case McpProtocol.Accepted accepted -> response.setStatus(202);
            case McpProtocol.Response r -> {
                if (r.sessionId() != null) {
                    response.setHeader(SESSION_HEADER, r.sessionId());
                }
                int code = r.body().path("error").path("code").asInt(0);
                boolean unaddressed = r.body().path("id").isNull()
                        && (code == McpProtocol.INVALID_REQUEST || code == McpProtocol.PARSE_ERROR);
                write(response, unaddressed ? 400 : 200, r);
            }
            case McpProtocol.Elicit e -> {
                if (!streams.elicit(request, response, ctx, e)) {
                    write(response, 200, new McpProtocol.Response(protocol.response(e.requestId(), e.fallback()), null));
                }
            }
        }
    }

    /** No server-initiated SSE stream: notifications are not offered (tools/list does not change during a session). */
    @GetMapping(McpResource.PATH)
    public void get(HttpServletResponse response) {
        response.setHeader("Allow", "POST");
        response.setStatus(405);
    }

    /** Sessions are signed IDs, not server state: there is nothing to end. */
    @DeleteMapping(McpResource.PATH)
    public void delete(HttpServletResponse response) {
        response.setHeader("Allow", "POST");
        response.setStatus(405);
    }

    private void write(HttpServletResponse response, int status, McpProtocol.Response reply) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.write(reply.body()));
    }
}
