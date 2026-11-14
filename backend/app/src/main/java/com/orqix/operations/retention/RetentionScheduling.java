package com.orqix.operations.retention;

import java.time.Clock;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Schedules of run retention (CG-O07, CG-P04): the daily sweep and the engine history batches. */
@Component
@Profile("!migrate")
public class RetentionScheduling {

    private final RetentionSweeper sweeper;
    private final HistoryPurger history;
    private final Clock clock;

    public RetentionScheduling(RetentionSweeper sweeper, HistoryPurger history, Clock clock) {
        this.sweeper = sweeper;
        this.history = history;
        this.clock = clock;
    }

    /** Daily at 03:15 by default. */
    @Scheduled(cron = "${orqix.retention.sweep-cron:0 15 3 * * *}")
    public void sweeps() {
        sweeper.scheduledSweeps();
    }

    @Scheduled(fixedDelayString = "${orqix.retention.history-delay:10m}",
            initialDelayString = "${orqix.retention.history-delay:10m}")
    public void history() {
        history.purgePending(clock.instant());
    }
}
