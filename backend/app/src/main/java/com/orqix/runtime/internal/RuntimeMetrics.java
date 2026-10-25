package com.orqix.runtime.internal;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Required custom metrics (S1 section 9). Gauges read the database on scrape (cheap indexed queries) so every pod
 * reports the same, durable values.
 */
@Component
public class RuntimeMetrics {

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Counter runsStarted;

    public RuntimeMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.clock = clock;
        this.runsStarted = Counter.builder("orqix_runs_started").description("Runs started").register(registry);
        Gauge.builder("orqix_runs_needs_attention", this, RuntimeMetrics::needsAttention)
                .description("Runs currently needing operator attention").register(registry);
        Gauge.builder("orqix_oldest_executable_job_age_seconds", this, RuntimeMetrics::oldestExecutableJobAge)
                .description("Age of the oldest executable engine job").baseUnit("seconds").register(registry);
    }

    public void runStarted() {
        runsStarted.increment();
    }

    public void taskDecided(String action) {
        Counter.builder("orqix_tasks_decided").tag("action", action).register(registry).increment();
    }

    public void effectAttempt(String effectClass, String outcome) {
        Counter.builder("orqix_effect_attempts").tag("class", effectClass).tag("outcome", outcome)
                .register(registry).increment();
    }

    public void runCompleted(String outcomeKind) {
        Counter.builder("orqix_runs_completed").tag("outcomeKind", outcomeKind == null ? "none" : outcomeKind)
                .register(registry).increment();
    }

    private double needsAttention() {
        try {
            return jdbc.sql("SELECT count(*) FROM workflow_run WHERE lifecycle = 'needsAttention'")
                    .query(Long.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private double oldestExecutableJobAge() {
        try {
            // min() over no rows is one row holding NULL, and .single() rejects a null result (that used to report NaN
            // instead of 0 when no job exists), so read it as a list.
            java.util.List<Timestamp> found = jdbc.sql("SELECT min(create_time_) FROM act_ru_job").query(Timestamp.class).list();
            Timestamp oldest = found.isEmpty() ? null : found.get(0);
            if (oldest == null) {
                return 0;
            }
            return Math.max(0, (clock.millis() - oldest.getTime()) / 1000.0);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
