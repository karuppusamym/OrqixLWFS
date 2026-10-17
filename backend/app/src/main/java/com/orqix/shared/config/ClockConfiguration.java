package com.orqix.shared.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** All time is UTC {@link java.time.Instant}; the clock is injected so tests can control it (S1 section 8). */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
