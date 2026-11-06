package com.orqix.ai.tasks;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI task execution (CG-A05, docs/build/S134 section 5), prefix {@code orqix.ai.tasks}.
 *
 * @param threads          AI tasks running at the same time per pod
 * @param leaseSeconds     job lease; renewed between turns, so a task may run up to its deadline
 * @param maxAttempts      claims of one job before it ends on aiUnavailable (pod crashes, lost leases)
 * @param defaultTimeout   task deadline when the skill sets none
 * @param maxTimeout       the longest deadline a skill may ask for (10 minutes)
 * @param turnStateKeyRef  S132 secret reference of the AES key (32 bytes, Base64) sealing job input and turn state
 * @param turnStateTtl     turn state is deleted when the step ends and at most this long after it was written
 * @param pollInterval     how often an idle pod looks for AI jobs
 * @param defaultMaxCostUsd cost ceiling of one task when the skill sets none
 */
@ConfigurationProperties(prefix = "orqix.ai.tasks")
public record AiTaskProperties(@DefaultValue("4") int threads, @DefaultValue("60") int leaseSeconds,
        @DefaultValue("3") int maxAttempts, @DefaultValue("60s") Duration defaultTimeout,
        @DefaultValue("600s") Duration maxTimeout, @DefaultValue("ai-turn-state-key") String turnStateKeyRef,
        @DefaultValue("24h") Duration turnStateTtl, @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("0.05") java.math.BigDecimal defaultMaxCostUsd) {

    public AiTaskProperties {
        threads = Math.max(1, Math.min(64, threads));
        leaseSeconds = Math.max(10, Math.min(600, leaseSeconds));
        maxAttempts = Math.max(1, Math.min(10, maxAttempts));
        maxTimeout = maxTimeout == null || maxTimeout.compareTo(Duration.ofMinutes(10)) > 0 ? Duration.ofMinutes(10)
                : maxTimeout;
        defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(60) : defaultTimeout;
        if (defaultTimeout.compareTo(maxTimeout) > 0) {
            defaultTimeout = maxTimeout;
        }
        turnStateTtl = turnStateTtl == null || turnStateTtl.compareTo(Duration.ofHours(24)) > 0 ? Duration.ofHours(24)
                : turnStateTtl;
        pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
    }
}
