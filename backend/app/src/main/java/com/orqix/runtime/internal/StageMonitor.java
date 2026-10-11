package com.orqix.runtime.internal;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Looks for stages past their due time (docs/build/S17 section 4). Runs on every pod; the work is idempotent and
 * takes row locks with SKIP LOCKED, so the timeline event and the notifications are written once.
 */
@Component
@Profile("!migrate")
class StageMonitor {

    private final RunStages stages;

    StageMonitor(RunStages stages) {
        this.stages = stages;
    }

    @Scheduled(fixedDelayString = "${orqix.stages.poll-delay:60s}")
    void check() {
        stages.detectOverdue();
    }
}
