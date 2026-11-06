package com.orqix.integration.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.web.Sql;

/**
 * {@code maxRequestsPerMinute} of a binding (CG-W04, docs/build/S131), enforced on each pod as the limit divided by the
 * live pods (at least one request a minute per pod), with a sliding one-minute window. A call over the limit is deferred
 * until the oldest call in the window is a minute old, without using an attempt. Exact global limits need a shared
 * limiter (doc 29 section 12); the per-pod share can overshoot by the rounding while pods start or stop.
 * <p>
 * Live pods are the rows of {@code integration_pod} seen in the last 90 seconds; every pod refreshes its row every 30
 * seconds. Limits and the pod count are cached for 15 seconds.
 */
@Service
public class BindingRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration CACHE = Duration.ofSeconds(15);
    private static final Duration POD_LIVENESS = Duration.ofSeconds(90);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String podId;
    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final Map<String, Cached> limits = new ConcurrentHashMap<>();
    private volatile Instant podsReadAt = Instant.EPOCH;
    private volatile int livePods = 1;

    private record Cached(Integer limit, Instant readAt) {
    }

    public BindingRateLimiter(JdbcClient jdbc, Clock clock, OrqixProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.podId = properties.runtime().lockOwner();
    }

    /**
     * Takes one slot of the binding's per-pod share.
     *
     * @return null when the call may go out now, else when a slot is free again
     */
    public Instant acquire(UUID workspaceId, String connectionKey) {
        Integer limit = limit(workspaceId, connectionKey);
        if (limit == null) {
            return null;
        }
        int share = Math.max(1, limit / Math.max(1, pods()));
        Instant now = clock.instant();
        Deque<Instant> window = windows.computeIfAbsent(workspaceId + "|" + connectionKey, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= share) {
                return window.peekFirst().plus(WINDOW);
            }
            window.addLast(now);
            return null;
        }
    }

    /** @return the configured limit, or null when the binding has none */
    @Transactional(readOnly = true)
    public Integer limit(UUID workspaceId, String connectionKey) {
        String key = workspaceId + "|" + connectionKey;
        Instant now = clock.instant();
        Cached cached = limits.get(key);
        if (cached != null && cached.readAt().plus(CACHE).isAfter(now)) {
            return cached.limit();
        }
        Integer limit = jdbc.sql("""
                SELECT max_requests_per_minute FROM connection_rate_limit
                WHERE workspace_id = :ws AND connection_key = :key
                """)
                .param("ws", workspaceId).param("key", connectionKey)
                .query((rs, n) -> (Integer) rs.getObject("max_requests_per_minute")).optional().orElse(null);
        limits.put(key, new Cached(limit, now));
        return limit;
    }

    /** Forgets the cached limit after an administrator changed it on this pod. */
    public void invalidate(UUID workspaceId, String connectionKey) {
        limits.remove(workspaceId + "|" + connectionKey);
        windows.remove(workspaceId + "|" + connectionKey);
    }

    /** The number of live pods (at least one), cached. */
    public int pods() {
        Instant now = clock.instant();
        if (podsReadAt.plus(CACHE).isAfter(now)) {
            return livePods;
        }
        Integer count = jdbc.sql("SELECT count(*) FROM integration_pod WHERE seen_at > :since")
                .param("since", Sql.ts(now.minus(POD_LIVENESS))).query(Integer.class).single();
        livePods = Math.max(1, count == null ? 1 : count);
        podsReadAt = now;
        return livePods;
    }

    /** Refreshes this pod's liveness row and removes rows of pods gone for an hour. */
    @Scheduled(fixedDelayString = "${orqix.integration.pod-heartbeat:30s}", initialDelay = 0)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat() {
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO integration_pod (pod_id, seen_at) VALUES (:pod, :now)
                ON CONFLICT (pod_id) DO UPDATE SET seen_at = EXCLUDED.seen_at
                """)
                .param("pod", podId.length() > 200 ? podId.substring(0, 200) : podId).param("now", Sql.ts(now))
                .update();
        jdbc.sql("DELETE FROM integration_pod WHERE seen_at < :gone").param("gone", Sql.ts(now.minus(Duration.ofHours(1))))
                .update();
    }
}
