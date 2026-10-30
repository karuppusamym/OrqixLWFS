package com.orqix.runtime.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Callback token settings (docs/build/S104), deployment-owned.
 *
 * @param retention          how long a closed callback wait (resumed, answered elsewhere, timed out, request ended) is
 *                           kept, so its token can still poll or replay the original answer; after that the row is
 *                           deleted and the token is unknown (404)
 * @param requestsPerMinute  poll and resume calls one token may make per minute on each pod (beyond that: 429)
 */
@ConfigurationProperties(prefix = "orqix.callbacks")
public record CallbackSettings(
        @DefaultValue("30d") Duration retention,
        @DefaultValue("60") int requestsPerMinute) {
    public CallbackSettings {
        if (retention == null || retention.compareTo(Duration.ofHours(1)) < 0 || retention.compareTo(Duration.ofDays(365)) > 0
                || requestsPerMinute < 1 || requestsPerMinute > 6000) {
            throw new IllegalArgumentException("Callback settings: retention must be 1h..365d and requests-per-minute 1..6000");
        }
    }
}
