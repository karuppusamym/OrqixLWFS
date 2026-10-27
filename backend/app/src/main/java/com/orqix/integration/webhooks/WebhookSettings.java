package com.orqix.integration.webhooks;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbound webhook delivery (docs/build/S99 B6, B8, B9), deployment-owned.
 *
 * @param initialBackoff wait before the second attempt; doubled for every further attempt
 * @param maxBackoff     longest wait between attempts
 * @param maxAttempts    attempts before a delivery becomes a dead letter
 * @param dispatchDelay  how often each pod looks for due deliveries
 * @param batch          deliveries one pod sends per pass
 * @param lease          how long a claimed delivery belongs to the pod that claimed it
 * @param maxPending     pending deliveries one subscription may hold (further events are dead-lettered at once)
 */
@ConfigurationProperties(prefix = "orqix.webhooks")
public record WebhookSettings(
        @DefaultValue("30s") Duration initialBackoff,
        @DefaultValue("1h") Duration maxBackoff,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("5s") Duration dispatchDelay,
        @DefaultValue("50") int batch,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("10000") int maxPending) {

    public WebhookSettings {
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.compareTo(Duration.ofHours(1)) > 0
                || maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0 || maxBackoff.compareTo(Duration.ofDays(1)) > 0
                || maxAttempts < 1 || maxAttempts > 20 || batch < 1 || batch > 500 || maxPending < 1 || maxPending > 1_000_000
                || lease == null || lease.compareTo(Duration.ofSeconds(30)) < 0 || lease.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Webhook settings exceed supported bounds");
        }
    }
}
