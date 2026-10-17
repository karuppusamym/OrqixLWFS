package com.orqix.runtime.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers worker jobs whose lease ran out (docs/build/S43). Idempotent; several instances can run it at once. */
@Component
@Profile("!migrate")
public class ExternalJobSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalJobSweeper.class);

    private final ExternalJobService jobs;

    public ExternalJobSweeper(ExternalJobService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${orqix.external-jobs.sweep-delay:5s}", initialDelayString = "${orqix.external-jobs.sweep-delay:5s}")
    public void sweep() {
        try {
            jobs.recoverExpired();
        } catch (RuntimeException e) {
            LOG.warn("External job sweep failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }
}
