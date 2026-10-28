package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;

/**
 * Audits callback tokens that expired unused (S98 D5, D12), once each, and deletes closed waits older than
 * {@code orqix.callbacks.retention} (S104). Safe on every pod: rows are claimed with {@code FOR UPDATE SKIP LOCKED}.
 * Expiry itself needs no sweep: the poll and resume compute it from the stored time.
 */
@Component
@Profile("!migrate")
public class CallbackSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackSweeper.class);

    private final CallbackStore store;
    private final AuditWriter audit;
    private final TransactionTemplate transactions;
    private final CallbackSettings settings;
    private final Clock clock;

    public CallbackSweeper(CallbackStore store, AuditWriter audit, TransactionTemplate transactions,
            CallbackSettings settings, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.transactions = transactions;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.callbacks.sweep-delay:30s}", initialDelayString = "${orqix.callbacks.sweep-delay:30s}")
    public void sweep() {
        try {
            run();
            purge(clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("Callback expiry sweep failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    /** One pass (also called by tests). @return the number of expiries recorded */
    public int run() {
        Integer count = transactions.execute(status -> {
            List<UUID[]> expired = store.lockExpired(clock.instant(), 200);
            for (UUID[] row : expired) {
                store.expiryAudited(row[0]);
                audit.write(new AuditWriter.Entry(row[1], AuditWriter.ActorType.SYSTEM, null, "callback.token-expired",
                        "callbackWait", row[0].toString(), AuditWriter.Outcome.SUCCESS,
                        Map.of("waitId", row[0].toString(), "runId", row[2].toString())));
            }
            return expired.size();
        });
        return count == null ? 0 : count;
    }

    static final int PURGE_BATCH = 500;

    /**
     * Deletes closed waits whose closing time is older than the retention, as of {@code now} (also called by tests),
     * in batches of 500 (at most 20 per pass), with one audit entry per workspace and batch.
     *
     * @return the number of waits deleted
     */
    public int purge(Instant now) {
        Instant before = now.minus(settings.retention());
        int total = 0;
        for (int batch = 0; batch < 20; batch++) {
            Integer deleted = transactions.execute(status -> {
                List<UUID> workspaces = store.purgeClosed(before, PURGE_BATCH);
                Map<UUID, Integer> counts = new LinkedHashMap<>();
                workspaces.forEach(ws -> counts.merge(ws, 1, Integer::sum));
                counts.forEach((ws, n) -> audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.SYSTEM, null,
                        "callback.waits-purged", "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS,
                        Map.of("count", n, "retentionSeconds", settings.retention().toSeconds()))));
                return workspaces.size();
            });
            int n = deleted == null ? 0 : deleted;
            total += n;
            if (n < PURGE_BATCH) {
                break;
            }
        }
        return total;
    }
}
