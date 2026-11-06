package com.orqix.mcp;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;

import tools.jackson.databind.node.ObjectNode;

/**
 * The elicitation flow on Streamable HTTP (S136 section 5): the answer to a tools/call that prepared a write is an SSE
 * stream. Its first event is the server's {@code elicitation/create} request; the client POSTs the person's answer to
 * {@code /mcp} (any pod), which confirms or declines the action in the database. This stream watches the confirmation
 * and sends the tools/call result once it is settled, or the confirmation-required result after the wait, so the
 * assistant can still use {@code confirm}. At most {@link #MAX_STREAMS} streams wait per pod; beyond that the
 * confirmation-required result is answered directly.
 */
@Component
public class McpStreams {

    static final int MAX_STREAMS = 32;
    private static final Logger LOG = LoggerFactory.getLogger(McpStreams.class);

    private final McpTools tools;
    private final McpProtocol protocol;
    private final McpSettings settings;
    private final Json json;
    private final Clock clock;
    private final ExecutorService waiters = new ThreadPoolExecutor(0, MAX_STREAMS, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread t = new Thread(runnable, "orqix-mcp-elicitation");
                t.setDaemon(true);
                return t;
            });

    public McpStreams(McpTools tools, McpProtocol protocol, McpSettings settings, Json json, Clock clock) {
        this.tools = tools;
        this.protocol = protocol;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
    }

    /** False when the stream could not start; the caller then answers {@code reply.fallback()} as plain JSON. */
    public boolean elicit(HttpServletRequest request, HttpServletResponse response, McpTools.Context ctx,
            McpProtocol.Elicit reply) throws IOException {
        if (settings.elicitationWait().isZero()) {
            return false;
        }
        AsyncContext async = request.startAsync(request, response);
        async.setTimeout(settings.elicitationWait().toMillis() + 15_000);
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = response.getWriter();
        event(writer, protocol.elicitation(reply.pending(), reply.protocolVersion()));
        response.flushBuffer();
        Instant deadline = clock.instant().plus(settings.elicitationWait());
        try {
            waiters.execute(() -> {
                try {
                    ObjectNode result = waitForAnswer(ctx, reply, deadline);
                    event(writer, protocol.response(reply.requestId(), result));
                } catch (RuntimeException e) {
                    LOG.warn("MCP elicitation stream ended early ({})", e.getClass().getSimpleName());
                    event(writer, protocol.response(reply.requestId(), reply.fallback()));
                } finally {
                    async.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            event(writer, protocol.response(reply.requestId(), reply.fallback()));
            async.complete();
        }
        return true;
    }

    private ObjectNode waitForAnswer(McpTools.Context ctx, McpProtocol.Elicit reply, Instant deadline) {
        while (clock.instant().isBefore(deadline)) {
            Optional<ObjectNode> settled = tools.settled(ctx.caller(), reply.pending().id());
            if (settled.isPresent()) {
                return settled.get();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Optional<ObjectNode> settled = tools.settled(ctx.caller(), reply.pending().id());
        if (settled.isPresent()) {
            return settled.get();
        }
        ObjectNode fallback = reply.fallback().deepCopy();
        ((tools.jackson.databind.node.ArrayNode) fallback.get("content")).addObject().put("type", "text").put("text", "The person did not answer the "
                + "confirmation request in time. Ask them, then call confirm with the confirmationId if they agree.");
        return fallback;
    }

    private void event(PrintWriter writer, ObjectNode message) {
        synchronized (writer) {
            writer.write("event: message\ndata: ");
            writer.write(json.write(message));
            writer.write("\n\n");
            writer.flush();
        }
    }

    @PreDestroy
    void stop() {
        waiters.shutdownNow();
    }

    /** Tests: whether a stream thread is still waiting. */
    int active() {
        return ((ThreadPoolExecutor) waiters).getActiveCount();
    }
}
