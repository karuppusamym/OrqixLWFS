package com.orqix.runtime.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Message settings (docs/build/S16 section 3), deployment-owned.
 *
 * @param bufferTtl              how long a message that finds no waiting request is kept for a wait that starts later
 * @param maxPayloadBytes        largest serialized payload accepted by the API
 * @param maxBufferedPerWorkspace buffered messages one workspace may hold at a time (beyond that: 429 RATE_LIMITED)
 * @param maxBufferedPerKey      buffered messages for one (name, correlation key) at a time
 * @param maxFanOut              waiting requests one message may be delivered to at once
 * @param sweepDelay             how often buffered messages are matched with waits and old rows are removed
 * @param retention              how long delivered, expired and discarded rows are kept (without payloads)
 */
@ConfigurationProperties(prefix = "orqix.messages")
public record MessageSettings(
        @DefaultValue("24h") Duration bufferTtl,
        @DefaultValue("16384") int maxPayloadBytes,
        @DefaultValue("5000") int maxBufferedPerWorkspace,
        @DefaultValue("10") int maxBufferedPerKey,
        @DefaultValue("25") int maxFanOut,
        @DefaultValue("5s") Duration sweepDelay,
        @DefaultValue("7d") Duration retention) {
    public MessageSettings {
        if (bufferTtl==null || bufferTtl.compareTo(Duration.ofSeconds(1))<0 || bufferTtl.compareTo(Duration.ofDays(7))>0
                || sweepDelay==null || sweepDelay.compareTo(Duration.ofSeconds(1))<0 || sweepDelay.compareTo(Duration.ofHours(1))>0
                || retention==null || retention.compareTo(Duration.ofHours(1))<0 || retention.compareTo(Duration.ofDays(365))>0
                || maxPayloadBytes<1 || maxPayloadBytes>65536 || maxBufferedPerWorkspace<1 || maxBufferedPerWorkspace>100000
                || maxBufferedPerKey<1 || maxBufferedPerKey>100 || maxBufferedPerKey>maxBufferedPerWorkspace || maxFanOut<1 || maxFanOut>25)
            throw new IllegalArgumentException("Message settings exceed supported payload, buffering, fanout or duration bounds");
    }
}
