package com.orqix.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orqix.support.TestDatabase;

/**
 * S92 (docs/build/S92-scalability-qualification.md): the V155 indexes exist, are valid, and their partial predicates
 * match the application's hot queries, so the planner can use them (seq scans are disabled to make the choice
 * deterministic on the tiny test database; S92 records the plans and timings on a seeded database).
 */
class ScaleIndexesTest {

    private static final String WS = "00000000-0000-7000-8000-000000000201";

    @Test
    void v155IndexesExistAndMatchTheHotQueries() throws Exception {
        TestDatabase.ensureMigrated();
        try (Connection c = DriverManager.getConnection(TestDatabase.jdbcUrl(), "postgres", "postgres");
             Statement st = c.createStatement()) {
            st.execute("SET search_path TO orqix, flowable");
            ResultSet rs = st.executeQuery("""
                    SELECT i.relname, x.indisvalid FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
                    WHERE i.relname IN ('run_event_ws_type_time', 'task_context_ws_created_ended', 'workflow_run_ws_open',
                                        'workflow_run_ws_completed_ended')
                    """);
            Map<String, Boolean> found = new LinkedHashMap<>();
            while (rs.next()) {
                found.put(rs.getString(1), rs.getBoolean(2));
            }
            assertThat(found).hasSize(4).doesNotContainValue(false);

            st.execute("SET enable_seqscan = off");
            st.execute("SET enable_bitmapscan = on");
            Map<String, String> queries = Map.of(
                    // InsightsQueries.approvalSteps (tmo) and waitSteps
                    "run_event_ws_type_time", "SELECT run_id FROM run_event WHERE workspace_id = '" + WS
                            + "' AND type IN ('WAIT_STARTED', 'WAIT_ENDED') AND occurred_at >= now() - interval '30 days'",
                    // InsightsQueries.approvalSteps (tasks)
                    "task_context_ws_created_ended", "SELECT engine_task_id FROM task_context WHERE workspace_id = '" + WS
                            + "' AND created_at >= now() - interval '30 days' AND created_at < now() AND status <> 'open'",
                    // InsightsQueries.oldestOpen; also a run list filtered by an open lifecycle
                    "workflow_run_ws_open", "SELECT id FROM workflow_run WHERE workspace_id = '" + WS
                            + "' AND lifecycle IN ('running', 'waiting', 'needsAttention') ORDER BY created_at, id LIMIT 10",
                    // InsightsQueries.daily (completed per day)
                    "workflow_run_ws_completed_ended", "SELECT count(*) FROM workflow_run WHERE workspace_id = '" + WS
                            + "' AND lifecycle = 'completed' AND ended_at >= now() - interval '30 days' AND ended_at < now()");
            for (var q : queries.entrySet()) {
                StringBuilder plan = new StringBuilder();
                ResultSet p = st.executeQuery("EXPLAIN " + q.getValue());
                while (p.next()) {
                    plan.append(p.getString(1)).append('\n');
                }
                assertThat(plan.toString()).as(q.getKey()).contains(q.getKey());
            }
        }
    }
}
