package com.orqix.stub;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Sessions, the injected fault and the call log of the demo MCP endpoint. Thread-safe. */
@Component
public class McpState {

    private static final Logger log = LoggerFactory.getLogger(McpState.class);
    private static final int MAX_CALLS = 500;

    /** Fault modes of {@code POST /__mcp/faults}. */
    public enum Mode {
        none, delay, status500, status429, status401, toolError, invalidJson,
        /** Answers as a {@code text/event-stream} instead of {@code application/json} (valid, but a different shape). */
        sse
    }

    /** @param onAnyRequest true: every request is affected; false (default): only {@code tools/call} */
    public record AppliedFault(Mode mode, long delayMs) {
    }

    public record Snapshot(Mode mode, Integer remaining, long delayMs, boolean onAnyRequest, long applied) {
    }

    /** One recorded request. {@code arguments} is null except for {@code tools/call}. */
    public record Call(Instant at, String method, String tool, JsonNode arguments) {
    }

    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final List<Call> calls = new ArrayList<>();
    private Mode mode = Mode.none;
    private Integer remaining;
    private long delayMs;
    private boolean onAnyRequest;
    private long applied;

    public boolean addSession(String id) {
        return sessions.add(id);
    }

    public boolean hasSession(String id) {
        return id != null && sessions.contains(id);
    }

    public boolean removeSession(String id) {
        return id != null && sessions.remove(id);
    }

    public synchronized void arm(Mode newMode, Integer times, long newDelayMs, boolean any) {
        if (newMode == Mode.none) {
            clearFault();
            return;
        }
        this.mode = newMode;
        this.remaining = times;
        this.delayMs = newDelayMs;
        this.onAnyRequest = any;
        log.info("mcp fault armed: mode={} times={} delayMs={} any={}", newMode, times, newDelayMs, any);
    }

    private void clearFault() {
        this.mode = Mode.none;
        this.remaining = null;
        this.delayMs = 0;
        this.onAnyRequest = false;
    }

    /** Takes one use of the fault when it applies to this method. */
    public synchronized Optional<AppliedFault> consume(String method) {
        if (mode == Mode.none || (!onAnyRequest && !"tools/call".equals(method))) {
            return Optional.empty();
        }
        AppliedFault fault = new AppliedFault(mode, delayMs);
        applied++;
        if (remaining != null && --remaining <= 0) {
            clearFault();
        }
        return Optional.of(fault);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mode, remaining, delayMs, onAnyRequest, applied);
    }

    public synchronized void record(String method, String tool, JsonNode arguments) {
        if (calls.size() >= MAX_CALLS) {
            calls.remove(0);
        }
        calls.add(new Call(Instant.now(), method, tool, arguments));
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized void reset() {
        clearFault();
        applied = 0;
        calls.clear();
        sessions.clear();
    }
}
