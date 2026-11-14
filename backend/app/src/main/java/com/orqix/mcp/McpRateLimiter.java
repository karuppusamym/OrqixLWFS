package com.orqix.mcp;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * A sliding one-minute window of MCP tool calls per person, on each pod (S136 section 6). With several pods a person
 * may make up to {@code pods x limit} calls a minute: this protects the service from a looping assistant, it is not a
 * quota.
 */
@Component
public class McpRateLimiter {

    static final long WINDOW_MILLIS = 60_000;
    static final int MAX_TRACKED = 20_000;

    private final Clock clock;
    private final int limit;
    private final Map<UUID, Deque<Long>> recent = new HashMap<>();
    private long lastSweep = Long.MIN_VALUE;

    public McpRateLimiter(Clock clock, McpSettings settings) {
        this.clock = clock;
        this.limit = settings.callsPerMinute();
    }

    /** Counts a call; returns the seconds to wait when the person has used up the window, else empty. */
    public synchronized OptionalLong acquire(UUID subjectId) {
        long now = clock.millis();
        if (lastSweep == Long.MIN_VALUE || now - lastSweep >= WINDOW_MILLIS || recent.size() >= MAX_TRACKED) {
            recent.entrySet().removeIf(e -> {
                drop(e.getValue(), now);
                return e.getValue().isEmpty();
            });
            lastSweep = now;
        }
        if (!recent.containsKey(subjectId) && recent.size() >= MAX_TRACKED) {
            return OptionalLong.of(60);
        }
        Deque<Long> times = recent.computeIfAbsent(subjectId, k -> new ArrayDeque<>());
        drop(times, now);
        if (times.size() >= limit) {
            return OptionalLong.of(Math.max(1, (WINDOW_MILLIS - (now - times.peekFirst()) + 999) / 1000));
        }
        times.addLast(now);
        return OptionalLong.empty();
    }

    /** Tests start from an empty window. */
    public synchronized void reset() {
        recent.clear();
    }

    private static void drop(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) {
            times.pollFirst();
        }
    }
}
