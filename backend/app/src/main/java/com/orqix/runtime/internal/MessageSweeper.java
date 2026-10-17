package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.shared.web.Sql;

/**
 * Background upkeep of messages (S16 section 3), safe on every pod at once: it gives buffered messages to waits that
 * started after them (also the ones missed when a pod stopped between a wait's commit and the first look), expires
 * buffered messages after their window, closes waits of requests that ended, and removes old closed messages.
 * Every step is idempotent and takes row locks with SKIP LOCKED or the delivery's own lock order.
 */
@Component
@Profile("!migrate")
public class MessageSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(MessageSweeper.class);

    private final MessageStore store;
    private final MessageDelivery delivery;
    private final MessageSettings settings;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public MessageSweeper(MessageStore store, MessageDelivery delivery, MessageSettings settings, JdbcClient jdbc,
            TransactionTemplate transactions, Clock clock) {
        this.store = store;
        this.delivery = delivery;
        this.settings = settings;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.messages.sweep-delay:5s}", initialDelayString = "${orqix.messages.sweep-delay:5s}")
    public void sweep() {
        try {
            run();
        } catch (RuntimeException e) {
            LOG.warn("Message sweep failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    /** One pass (also called by tests). @return the number of buffered messages given to waiting requests */
    public int run() {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            store.expireBuffered(now);
            store.cancelOrphans(now);
            store.purgeClosed(now.minus(settings.retention()),now);
        });
        int delivered = 0;
        List<UUID> candidates = store.waitingWithBufferedMessage(now, 50);
        for (UUID subscriptionId : candidates) {
            UUID workspaceId = jdbc.sql("SELECT workspace_id FROM message_subscription WHERE id = :id")
                    .param("id", subscriptionId).query(UUID.class).optional().orElse(null);
            if (workspaceId == null) {
                continue;
            }
            try {
                if (delivery.consumeBuffered(workspaceId, subscriptionId)) {
                    delivered++;
                }
            } catch (RuntimeException e) {
                LOG.debug("Buffered message not delivered yet ({}); trying again", e.getClass().getSimpleName());
            }
        }
        return delivered;
    }
}
