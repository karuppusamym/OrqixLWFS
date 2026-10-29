package com.orqix.runtime.internal;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * A sliding one-minute window of poll and resume calls per callback token, on each pod (docs/build/S104). Only tokens
 * that resolved to a wait are counted, so random guesses cannot fill the table. Keyed by the token's SHA-256, never
 * the token. With several pods a token may make up to {@code pods x limit} calls; this is protection, not a quota.
 */
@Component
public class CallbackRateLimiter {

    static final long WINDOW_MILLIS = 60_000;
    static final int MAX_TRACKED_TOKENS = 50_000;
    static final long FULL_SWEEP_MILLIS = 1_000;

    private final Clock clock;
    private final int limit;
    private final Map<String, Deque<Long>> recent = new HashMap<>();
    private long lastCleanupMillis = Long.MIN_VALUE;

    @Autowired
    public CallbackRateLimiter(Clock clock, CallbackSettings settings) {
        this(clock, settings.requestsPerMinute());
    }

    CallbackRateLimiter(Clock clock, int limit) {
        this.clock = clock;
        this.limit = limit;
    }

    /** @throws ApiException RATE_LIMITED with Retry-After when this token has used up the window */
    public synchronized void check(String tokenSha256) {
        long now = clock.millis();
        // A full table is swept at most once a second: with every tracked token still active, sweeping on each call
        // would scan all of them under this lock for every request.
        if (lastCleanupMillis == Long.MIN_VALUE || now - lastCleanupMillis >= WINDOW_MILLIS
                || (recent.size() >= MAX_TRACKED_TOKENS && now - lastCleanupMillis >= FULL_SWEEP_MILLIS)) {
            recent.entrySet().removeIf(e -> {
                drop(e.getValue(), now);
                return e.getValue().isEmpty();
            });
            lastCleanupMillis = now;
        }
        if (!recent.containsKey(tokenSha256) && recent.size() >= MAX_TRACKED_TOKENS) {
            throw new ApiException(ProblemCode.RATE_LIMITED, "Too many callback tokens are in use at once. Try again shortly.")
                    .withHeader("Retry-After", "60");
        }
        Deque<Long> times = recent.computeIfAbsent(tokenSha256, k -> new ArrayDeque<>());
        drop(times, now);
        if (times.size() >= limit) {
            long wait = Math.max(1, (WINDOW_MILLIS - (now - times.peekFirst()) + 999) / 1000);
            throw new ApiException(ProblemCode.RATE_LIMITED,
                    "A callback token can be used " + limit + " times a minute. Poll less often.")
                    .withHeader("Retry-After", String.valueOf(wait));
        }
        times.addLast(now);
    }

    private static void drop(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) {
            times.pollFirst();
        }
    }
}
