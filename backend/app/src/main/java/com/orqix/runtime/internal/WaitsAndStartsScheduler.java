package com.orqix.runtime.internal;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background work of docs/build/S139 on worker and all-in-one pods (scheduling is off on API pods, docs/adr/0004):
 * reminders while waiting (any number of pods; rows are claimed with SKIP LOCKED, leased and fenced) and scheduled starts
 * (one leader at a time through {@code scheduler_lease}; each planned time is also unique in {@code schedule_fire}).
 */
@Component
@Profile("!migrate")
public class WaitsAndStartsScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(WaitsAndStartsScheduler.class);

    private final WaitReminders reminders;
    private final Schedules schedules;
    private final Duration lease;

    public WaitsAndStartsScheduler(WaitReminders reminders, Schedules schedules,
            @Value("${orqix.schedules.lease:45s}") Duration lease) {
        this.reminders = reminders;
        this.schedules = schedules;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${orqix.reminders.tick:30s}", initialDelayString = "${orqix.reminders.tick:30s}")
    public void reminders() {
        try {
            for (int round = 0; round < 10 && reminders.runDue() == WaitReminders.BATCH; round++) {
                // keep going while there is a backlog, a bounded number of rounds per tick
            }
        } catch (RuntimeException e) {
            LOG.warn("Reminder tick failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${orqix.schedules.tick:15s}", initialDelayString = "${orqix.schedules.tick:15s}")
    public void schedules() {
        try {
            if (schedules.lead(lease)) {
                for (int round = 0; round < 10 && schedules.runDue(20) == 20; round++) {
                    // a bounded number of rounds per tick
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Schedule tick failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }
}
