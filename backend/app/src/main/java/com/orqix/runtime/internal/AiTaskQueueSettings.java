package com.orqix.runtime.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The runtime's view of the AI task settings ({@code orqix.ai.tasks}, docs/build/S134 section 5): worker threads,
 * lease, attempts and the hard deadline. The AI module binds the same prefix for its own part (turn state, cost).
 *
 * @param workerEnabled false stops this pod from running AI tasks (jobs wait for another pod)
 */
@ConfigurationProperties(prefix = "orqix.ai.tasks")
public record AiTaskQueueSettings(@DefaultValue("true") boolean workerEnabled, @DefaultValue("4") int threads,
        @DefaultValue("60") int leaseSeconds, @DefaultValue("3") int maxAttempts,
        @DefaultValue("600s") Duration maxTimeout) {

    public AiTaskQueueSettings {
        threads = Math.max(1, Math.min(64, threads));
        leaseSeconds = Math.max(10, Math.min(600, leaseSeconds));
        maxAttempts = Math.max(1, Math.min(10, maxAttempts));
        maxTimeout = maxTimeout == null || maxTimeout.compareTo(Duration.ofMinutes(10)) > 0 ? Duration.ofMinutes(10)
                : maxTimeout;
    }
}
