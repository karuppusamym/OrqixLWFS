package com.orqix.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

/**
 * S92 (docs/build/S92-scalability-qualification.md): every pooled connection plans with the actual parameter values
 * (plan_cache_mode = force_custom_plan), so a statement the JDBC driver has prepared on the server never falls back to
 * one generic plan for the optional-filter queries ("CAST(:x AS uuid) IS NULL OR col = :x"). Insights is called more
 * often than the driver's prepare threshold (5) to exercise the server-prepared path.
 */
class PlanCacheModeTest extends RuntimeTestBase {

    @Test
    void pooledConnectionsForceCustomPlansAndRepeatedInsightsStayAvailable() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(jdbc.sql("SHOW plan_cache_mode").query(String.class).single()).isEqualTo("force_custom_plan");
        }
        for (int i = 0; i < 8; i++) {
            assertThat(api("olga.operator").get("/workspaces/{ws}/insights/steps", PURCHASING).status())
                    .as("call " + (i + 1)).isEqualTo(200);
        }
    }
}
