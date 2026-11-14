package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.RuntimeTestBase;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * CG-P06 (docs/build/S150): one trace from the HTTP start through the engine job to the connector call, with trace ids
 * on the timeline and in the effect journal, and the Micrometer meters of the Wave A-D features.
 */
class TracingAndMetricsTest extends RuntimeTestBase {

    @Autowired
    MeterRegistry registry;

    @Test
    void startJobAndConnectorCallShareOneTrace() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("1800"))
                .path("id").asString());
        String startTrace = jdbc.sql("SELECT trace_id FROM run_event WHERE run_id = :run AND type = 'RUN_STARTED'")
                .param("run", runId).query(String.class).single();
        assertThat(startTrace).as("the start request is traced").matches("[0-9a-f]{32}");

        String variable = jdbc.sql("""
                SELECT text_ FROM act_ru_variable WHERE proc_inst_id_ = :pi AND execution_id_ = :pi
                  AND name_ = 'orqix_traceparent'
                """).param("pi", processInstance(PURCHASING, runId)).query(String.class).single();
        assertThat(variable).startsWith("00-" + startTrace + "-");

        approve("mani.manager", PURCHASING, runId, "manager");
        awaitLifecycle(PURCHASING, runId, "completed");

        List<String> effectTraces = jdbc.sql("SELECT trace_id FROM effect_journal WHERE run_id = :run")
                .param("run", runId).query(String.class).list();
        assertThat(effectTraces).as("the connector call ran in an engine job of the start's trace")
                .isNotEmpty().containsOnly(startTrace);
        String decisionTrace = jdbc.sql("""
                SELECT trace_id FROM run_event WHERE run_id = :run AND type = 'TASK_DECIDED' ORDER BY seq LIMIT 1
                """).param("run", runId).query(String.class).optional().orElse(null);
        if (decisionTrace != null) {
            assertThat(decisionTrace).as("the decision request has its own trace").isNotEqualTo(startTrace);
        }

        assertThat(registry.find("orqix.connector.call").timer()).isNotNull();
        assertThat(registry.find("orqix.connector.call").timers().stream().mapToLong(t -> t.count()).sum())
                .isPositive();
    }

    @Test
    void featureGaugesAreRegisteredAndReadable() {
        for (String name : List.of("orqix_poll_waits_active", "orqix_poll_waits_due", "orqix_poll_check_lag_seconds",
                "orqix_bulk_operations_running", "orqix_alert_incidents_open", "orqix_analytics_outbox_pending",
                "orqix_analytics_outbox_dead", "orqix_analytics_lag_seconds", "orqix_ai_task_queue_age_seconds")) {
            var gauge = registry.find(name).gauge();
            assertThat(gauge).as(name).isNotNull();
            assertThat(gauge.value()).as(name).isGreaterThanOrEqualTo(0);
        }
        assertThat(registry.find("orqix_connection_circuits").tag("state", "open").gauge()).isNotNull();
        assertThat(registry.find("orqix_ai_tasks").tag("status", "available").gauge()).isNotNull();
    }
}
