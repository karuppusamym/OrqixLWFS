package com.orqix.runtime.internal;

import java.time.Clock;
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
 * Audits callback tokens that expired unused (S98 D5, D12), once each. Safe on every pod: rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED}. Expiry itself needs no sweep: the poll and resume compute it from the stored time.
 */
@Component
@Profile("!migrate")
public class CallbackSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackSweeper.class);

    private final CallbackStore store;
    private final AuditWriter audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CallbackSweeper(CallbackStore store, AuditWriter audit, TransactionTemplate transactions, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.callbacks.sweep-delay:30s}", initialDelayString = "${orqix.callbacks.sweep-delay:30s}")
    public void sweep() {
        try {
            run();
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
}
