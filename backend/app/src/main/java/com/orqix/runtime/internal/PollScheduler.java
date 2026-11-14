package com.orqix.runtime.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs due checks of "Keep checking until..." steps and safety-net checks (docs/build/S129). Scheduling is enabled on
 * worker and all-in-one pods only (docs/adr/0004), so API pods never check. Any number of pods may run it: rows are
 * claimed with SKIP LOCKED, leased and fenced. A tick that found a full batch runs again at once.
 */
@Component
@Profile("!migrate")
public class PollScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PollScheduler.class);

    private final PollWaits polls;
    private final java.time.Clock clock;
    private final java.time.Duration retention;

    public PollScheduler(PollWaits polls, java.time.Clock clock,
            @org.springframework.beans.factory.annotation.Value("${orqix.polling.retention:30d}") java.time.Duration retention) {
        this.polls = polls;
        this.clock = clock;
        this.retention = retention;
    }

    /** Ended checks are kept for the retention (default 30 days), then deleted with an audited count. */
    @Scheduled(fixedDelayString = "${orqix.polling.purge-delay:1h}", initialDelayString = "${orqix.polling.purge-delay:1h}")
    public void purge() {
        try {
            polls.purge(clock.instant(), retention);
        } catch (RuntimeException e) {
            LOG.warn("Poll purge failed; it runs again later ({})", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${orqix.polling.tick:2s}", initialDelayString = "${orqix.polling.tick:2s}")
    public void tick() {
        try {
            for (int round = 0; round < 10 && polls.runDue() == PollWaits.BATCH; round++) {
                // keep going while there is a backlog, a bounded number of rounds per tick
            }
        } catch (RuntimeException e) {
            LOG.warn("Poll scheduler tick failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }
}
