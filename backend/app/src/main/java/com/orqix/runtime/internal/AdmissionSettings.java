package com.orqix.runtime.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CG-P05 admission control (docs/build/S150), deployment-owned defaults. A platform administrator may override the
 * limits for one workspace (Platform API).
 *
 * @param enabled           false switches admission control off for every workspace (overrides are kept)
 * @param startsPerMinute   default limit of new requests one workspace may start in a sliding minute; 0 = no limit
 * @param messagesPerMinute default limit of inbound messages one workspace may receive in a sliding minute; 0 = no limit
 * @param limitCacheTtl     how long a pod reuses a workspace's limits before reading them again
 */
@ConfigurationProperties(prefix = "orqix.admission")
public record AdmissionSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0") int startsPerMinute,
        @DefaultValue("0") int messagesPerMinute,
        @DefaultValue("10s") Duration limitCacheTtl) {

    public static final int MAX_LIMIT = 100_000;

    public AdmissionSettings {
        if (startsPerMinute < 0 || startsPerMinute > MAX_LIMIT) {
            throw new IllegalArgumentException("orqix.admission.starts-per-minute must be 0..100000");
        }
        if (messagesPerMinute < 0 || messagesPerMinute > MAX_LIMIT) {
            throw new IllegalArgumentException("orqix.admission.messages-per-minute must be 0..100000");
        }
        if (limitCacheTtl == null || limitCacheTtl.isNegative() || limitCacheTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("orqix.admission.limit-cache-ttl must be 0s..5m");
        }
    }
}
