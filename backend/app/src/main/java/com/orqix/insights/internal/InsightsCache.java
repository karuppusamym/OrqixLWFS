package com.orqix.insights.internal;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A very short-lived cache of finished Insights figures (docs/build/S92 F2: the aggregates were the largest consumer
 * of database time and were recomputed for every viewer on every replica). It sits <b>after</b> the access check and
 * the parameter validation, so a caller who may not see the figures never reaches it. The key names the figures
 * (kind, workspace, workflow, window); the figures themselves do not depend on who asks.
 *
 * <p>Concurrent callers for the same key share one calculation instead of each running the aggregates. A time to live
 * of zero switches the cache off (tests, and anyone who wants every figure computed on demand): `orqix.insights.cache-ttl-seconds`. The cache is per
 * replica and bounded; a replica restart empties it.
 */
@Component
public class InsightsCache {

    private static final int MAX_ENTRIES = 500;

    private record Entry(Object value, long expiresAtNanos) {
    }

    private final long ttlNanos;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public InsightsCache(@Value("${orqix.insights.cache-ttl-seconds:30}") long ttlSeconds) {
        this(Duration.ofSeconds(Math.max(0, ttlSeconds)));
    }

    public InsightsCache(Duration ttl) {
        this.ttlNanos = Math.max(0, ttl.toNanos());
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> compute) {
        if (ttlNanos == 0) {
            return compute.get();
        }
        Entry hit = entries.get(key);
        if (hit != null && System.nanoTime() < hit.expiresAtNanos()) {
            return (T) hit.value();
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                hit = entries.get(key);
                if (hit != null && System.nanoTime() < hit.expiresAtNanos()) {
                    return (T) hit.value();
                }
                T value = compute.get();
                evictIfFull();
                entries.put(key, new Entry(value, System.nanoTime() + ttlNanos));
                return value;
            } finally {
                locks.remove(key, lock);
            }
        }
    }

    private void evictIfFull() {
        if (entries.size() < MAX_ENTRIES) {
            return;
        }
        long now = System.nanoTime();
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            if (now >= it.next().getValue().expiresAtNanos()) {
                it.remove();
            }
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
    }
}
