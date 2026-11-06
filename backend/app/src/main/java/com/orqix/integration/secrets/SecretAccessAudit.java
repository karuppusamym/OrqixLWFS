package com.orqix.integration.secrets;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.orqix.shared.web.Sql;

import jakarta.annotation.PreDestroy;

/**
 * Secret-access audit (CG-I04, docs/build/S132): every resolution of a {@code secretRef} is counted per workspace,
 * reference, provider and UTC day, as resolved, served from the cache, not found or failed. Counts are kept in memory
 * and added to {@code secret_access_count} every few seconds, so dispatch never waits for an audit write. No value,
 * hash or length of a secret is ever recorded.
 */
@Component
public class SecretAccessAudit {

    /** Workspace of resolutions that no workspace asked for (platform keys). */
    public static final UUID PLATFORM = new UUID(0, 0);

    public enum Outcome { RESOLVED, CACHED, NOT_FOUND, FAILED }

    private record Key(UUID workspaceId, String secretRef, String provider, LocalDate day) {
    }

    private static final class Counts {
        final AtomicLong resolved = new AtomicLong();
        final AtomicLong cached = new AtomicLong();
        final AtomicLong notFound = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicReference<Instant> lastResolved = new AtomicReference<>();
        final AtomicReference<Instant> lastFailed = new AtomicReference<>();
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Map<Key, Counts> pending = new ConcurrentHashMap<>();

    public SecretAccessAudit(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(UUID workspaceId, SecretRef ref, Outcome outcome) {
        Instant now = clock.instant();
        Key key = new Key(workspaceId == null ? PLATFORM : workspaceId, ref.raw(), ref.scheme(),
                LocalDate.ofInstant(now, ZoneOffset.UTC));
        Counts counts = pending.computeIfAbsent(key, k -> new Counts());
        switch (outcome) {
            case RESOLVED -> counts.resolved.incrementAndGet();
            case CACHED -> counts.cached.incrementAndGet();
            case NOT_FOUND -> counts.notFound.incrementAndGet();
            case FAILED -> counts.failed.incrementAndGet();
        }
        if (outcome == Outcome.RESOLVED || outcome == Outcome.CACHED) {
            counts.lastResolved.set(now);
        } else {
            counts.lastFailed.set(now);
        }
    }

    /** Adds the pending counts to the table; a failed write keeps them for the next flush. */
    @Scheduled(fixedDelayString = "${orqix.secrets.audit-flush-delay:5s}", initialDelayString = "${orqix.secrets.audit-flush-delay:5s}")
    public synchronized void flush() {
        for (Key key : List.copyOf(pending.keySet())) {
            Counts counts = pending.remove(key);
            if (counts == null) {
                continue;
            }
            long resolved = counts.resolved.get();
            long cached = counts.cached.get();
            long notFound = counts.notFound.get();
            long failed = counts.failed.get();
            try {
                jdbc.sql("""
                        INSERT INTO secret_access_count (workspace_id, secret_ref, provider, day, resolutions, cache_hits,
                                                         not_found, failures, last_resolved_at, last_failed_at)
                        VALUES (:ws, :ref, :provider, :day, :resolutions, :cached, :notFound, :failed, :lastResolved, :lastFailed)
                        ON CONFLICT (workspace_id, secret_ref, provider, day) DO UPDATE SET
                            resolutions = secret_access_count.resolutions + EXCLUDED.resolutions,
                            cache_hits = secret_access_count.cache_hits + EXCLUDED.cache_hits,
                            not_found = secret_access_count.not_found + EXCLUDED.not_found,
                            failures = secret_access_count.failures + EXCLUDED.failures,
                            last_resolved_at = GREATEST(secret_access_count.last_resolved_at, EXCLUDED.last_resolved_at),
                            last_failed_at = GREATEST(secret_access_count.last_failed_at, EXCLUDED.last_failed_at)
                        """)
                        .param("ws", key.workspaceId()).param("ref", key.secretRef()).param("provider", key.provider())
                        .param("day", key.day()).param("resolutions", resolved + cached).param("cached", cached)
                        .param("notFound", notFound).param("failed", failed)
                        .param("lastResolved", counts.lastResolved.get() == null ? null : Sql.ts(counts.lastResolved.get()))
                        .param("lastFailed", counts.lastFailed.get() == null ? null : Sql.ts(counts.lastFailed.get()))
                        .update();
            } catch (RuntimeException e) {
                Counts back = pending.computeIfAbsent(key, k -> new Counts());
                back.resolved.addAndGet(resolved);
                back.cached.addAndGet(cached);
                back.notFound.addAndGet(notFound);
                back.failed.addAndGet(failed);
                back.lastResolved.accumulateAndGet(counts.lastResolved.get(), SecretAccessAudit::later);
                back.lastFailed.accumulateAndGet(counts.lastFailed.get(), SecretAccessAudit::later);
                return;
            }
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    @PreDestroy
    void close() {
        try {
            flush();
        } catch (RuntimeException ignored) {
            // the database may already be closed at shutdown
        }
    }
}
