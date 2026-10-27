package com.orqix.integration.webhooks;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Outbound webhooks get their own scheduler thread (S99 B8): a slow or hanging receiver must never occupy the shared
 * scheduler that runs the message, job, callback and watchdog sweepers.
 */
@Configuration
@Profile("!migrate")
class WebhookScheduling {

    // Non-default candidate, as for stageMailScheduler: Boot's applicationTaskExecutor stays the default executor.
    @Bean(name = "webhookScheduler", defaultCandidate = false)
    ThreadPoolTaskScheduler webhookScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("orqix-webhooks-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
