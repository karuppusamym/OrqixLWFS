package com.orqix.stub;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

/**
 * Stub configuration.
 *
 * @param contractResource       connection definition that carries the request schema (shared with the backend)
 * @param contractOperation      operation whose {@code requestSchema} validates {@code POST /orders}
 * @param commitThenTimeoutMs    default sleep of the {@code commitThenTimeout} fault (longer than a 20 s client timeout)
 * @param maxFaultDelayMs        upper bound accepted for a fault {@code delayMs}
 * @param maxBodyBytes           maximum accepted request body
 */
@ConfigurationProperties("stub")
public record StubProperties(
        @DefaultValue("classpath:procurement.connection.json") Resource contractResource,
        @DefaultValue("createOrder") String contractOperation,
        @DefaultValue("35000") long commitThenTimeoutMs,
        @DefaultValue("600000") long maxFaultDelayMs,
        @DefaultValue("65536") int maxBodyBytes) {
}
