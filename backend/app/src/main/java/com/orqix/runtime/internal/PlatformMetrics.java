package com.orqix.runtime.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * CG-P06 (docs/build/S150): metrics of the Wave A-D features. Counters count what this pod did; gauges read the
 * database (indexed counts) so every pod reports the same durable value, and dashboards take {@code max} over pods. A
 * gauge reading is reused for {@link #GAUGE_TTL}, so several Prometheus scrapes and pods cost one query set per pod
 * per 15 seconds.
 *
 * <p>Names follow the existing {@code orqix_*} meters (Prometheus adds {@code _total} to counters); tags are low
 * cardinality only (states, outcomes, actions), never workspace, run or connection ids.
 */
@Component
public class PlatformMetrics {

    static final Duration GAUGE_TTL = Duration.ofSeconds(15);

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Map<String, Cached> cache = new HashMap<>();

    private record Cached(long readAt, double value) {
    }

    public PlatformMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.clock = clock;
        // CG-W01/W03 poll waits (S129)
        gauge("orqix_poll_waits_active", "Keep-checking steps and message safety nets that are waiting or checking",
                "SELECT count(*) FROM poll_wait WHERE state IN ('waiting', 'checking')");
        gauge("orqix_poll_waits_due", "Poll checks whose next check time has passed and that no pod has claimed",
                "SELECT count(*) FROM poll_wait WHERE state = 'waiting' AND next_check_at <= now()");
        ageGauge("orqix_poll_check_lag_seconds", "How late the most overdue unclaimed poll check is",
                "SELECT min(next_check_at) FROM poll_wait WHERE state = 'waiting' AND next_check_at <= now()");
        // CG-C09 circuits (S131)
        for (String state : List.of("open", "halfOpen")) {
            gauge("orqix_connection_circuits", "Connection bindings whose circuit is in this state",
                    "SELECT count(*) FROM connection_circuit WHERE state = '" + state + "'", "state", state);
        }
        // CG-O03 bulk operations (S142)
        gauge("orqix_bulk_operations_running", "Bulk operations that are running",
                "SELECT count(*) FROM bulk_operation WHERE state = 'running'");
        // CG-O05 alerts (S144)
        gauge("orqix_alert_incidents_open", "Alert incidents that are open",
                "SELECT count(*) FROM alert_incident WHERE status = 'firing'");
        // CG-N analytics read model (S145)
        gauge("orqix_analytics_outbox_pending", "Timeline changes not yet projected into the analytics read model",
                "SELECT count(*) FROM analytics_outbox WHERE NOT dead");
        gauge("orqix_analytics_outbox_dead", "Analytics projections that gave up after their retries",
                "SELECT count(*) FROM analytics_outbox WHERE dead");
        ageGauge("orqix_analytics_lag_seconds", "Age of the oldest timeline change not yet projected into analytics",
                "SELECT min(created_at) FROM analytics_outbox WHERE NOT dead");
        // CG-A AI tasks (S134)
        for (String status : List.of("available", "running")) {
            gauge("orqix_ai_tasks", "AI task jobs in this status",
                    "SELECT count(*) FROM ai_task_job WHERE status = '" + status + "'", "status", status);
        }
        ageGauge("orqix_ai_task_queue_age_seconds", "How long the oldest available AI task job has waited",
                "SELECT min(available_at) FROM ai_task_job WHERE status = 'available' AND available_at <= now()");
    }

    /** A poll check ended with this outcome (met, notYet, failed, deadline, busy, deferred ...). */
    public void pollCheck(String outcome) {
        Counter.builder("orqix_poll_checks").description("Poll checks by outcome").tag("outcome", outcome)
                .register(registry).increment();
    }

    /** One item of a bulk operation was handled. */
    public void bulkItem(String action, String outcome) {
        Counter.builder("orqix_bulk_items").description("Bulk operation items by action and outcome")
                .tag("action", action).tag("outcome", outcome).register(registry).increment();
    }

    /** An AI task job ended (completed, failed, cancelled). */
    public void aiTaskEnded(String status) {
        Counter.builder("orqix_ai_tasks_ended").description("AI task jobs that ended, by final status")
                .tag("status", status).register(registry).increment();
    }

    /** CG-P05: a start or an inbound message was refused by the workspace's admission limit. */
    public void admissionRejected(String kind) {
        Counter.builder("orqix_admission_rejected").description("Starts and messages refused by admission control")
                .tag("kind", kind).register(registry).increment();
    }

    private void gauge(String name, String description, String sql, String... tags) {
        String key = name + String.join(",", tags);
        Gauge.builder(name, () -> cached(key, () -> count(sql))).description(description).tags(tags)
                .register(registry);
    }

    private void ageGauge(String name, String description, String sql) {
        Gauge.builder(name, () -> cached(name, () -> age(sql))).description(description).baseUnit("seconds")
                .register(registry);
    }

    private synchronized double cached(String key, Supplier<Double> read) {
        long now = clock.millis();
        Cached hit = cache.get(key);
        if (hit != null && now - hit.readAt() < GAUGE_TTL.toMillis()) {
            return hit.value();
        }
        double value = read.get();
        cache.put(key, new Cached(now, value));
        return value;
    }

    private double count(String sql) {
        try {
            return jdbc.sql(sql).query(Long.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private double age(String sql) {
        try {
            // min() over no rows is one NULL row; read it as a list (see RuntimeMetrics).
            List<Timestamp> found = jdbc.sql(sql).query(Timestamp.class).list();
            Timestamp oldest = found.isEmpty() ? null : found.get(0);
            return oldest == null ? 0 : Math.max(0, (clock.millis() - oldest.getTime()) / 1000.0);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
