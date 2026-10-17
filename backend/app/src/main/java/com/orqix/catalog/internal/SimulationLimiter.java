package com.orqix.catalog.internal;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * A sliding-window limit on dry runs per person (30 per minute on each pod), so a loop in a script cannot use the
 * evaluator as a load generator. The runs are cheap and side-effect free; this is protection, not a quota.
 */
@Component
public class SimulationLimiter {

    static final int LIMIT = 30;
    static final long WINDOW_MILLIS = 60_000;

    private final Clock clock;
    private final ConcurrentHashMap<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();
    private long lastCleanupMillis = Long.MIN_VALUE;

    public SimulationLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @throws ApiException RATE_LIMITED with Retry-After when the person has used up the window */
    public synchronized void check(UUID subjectId) {
        long now = clock.millis();
        if (lastCleanupMillis == Long.MIN_VALUE || now - lastCleanupMillis >= WINDOW_MILLIS || recent.size() >= 10_000) {
        recent.entrySet().removeIf(e -> {
            Deque<Long> entries=e.getValue();
            while(!entries.isEmpty() && now-entries.peekFirst()>=WINDOW_MILLIS) entries.pollFirst();
            return entries.isEmpty();
        });
        lastCleanupMillis = now;
        }
        if (!recent.containsKey(subjectId) && recent.size() >= 10_000)
            throw new ApiException(ProblemCode.RATE_LIMITED,"Too many active workflow testers. Try again shortly.").withHeader("Retry-After","60");
        Deque<Long> times=recent.computeIfAbsent(subjectId,id -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) times.pollFirst();
        if(times.size()>=LIMIT) {
            long wait=Math.max(1,(WINDOW_MILLIS-(now-times.peekFirst())+999)/1000);
            throw new ApiException(ProblemCode.RATE_LIMITED,"You can test a workflow " + LIMIT + " times a minute.").withHeader("Retry-After",String.valueOf(wait));
        }
        times.addLast(now);
    }
}
