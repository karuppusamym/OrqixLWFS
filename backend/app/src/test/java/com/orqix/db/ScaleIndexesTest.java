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
 * S92 (docs/build/S92-scalability-qualification.md): the V155 indexes exist, are valid, and have exactly the key
 * columns and partial predicates the hot queries need (Insights event/task scans, open requests, completed by end
 * time). Whether the planner picks them depends on the data, so the plans and timings are recorded on a seeded
 * database in S92 rather than asserted on the small, shared test database.
 */
class ScaleIndexesTest {

    @Test
    void v155IndexesExistWithTheExpectedKeysAndPredicates() throws Exception {
        TestDatabase.ensureMigrated();
        try (Connection c = DriverManager.getConnection(TestDatabase.jdbcUrl(), "postgres", "postgres");
             Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("""
                    SELECT i.relname, x.indisvalid, pg_get_indexdef(x.indexrelid) FROM pg_index x
                    JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_namespace n ON n.oid = i.relnamespace
                    WHERE n.nspname = 'orqix' AND i.relname IN ('run_event_ws_type_time', 'task_context_ws_created_ended',
                                                               'workflow_run_ws_open', 'workflow_run_ws_completed_ended')
                    """);
            Map<String, String> definitions = new LinkedHashMap<>();
            while (rs.next()) {
                assertThat(rs.getBoolean(2)).as(rs.getString(1) + " valid").isTrue();
                definitions.put(rs.getString(1), rs.getString(3));
            }
            assertThat(definitions).hasSize(4);
            assertThat(definitions.get("run_event_ws_type_time"))
                    .contains("ON orqix.run_event USING btree (workspace_id, type, occurred_at)").doesNotContain("WHERE");
            assertThat(definitions.get("task_context_ws_created_ended"))
                    .contains("ON orqix.task_context USING btree (workspace_id, created_at)")
                    .contains("WHERE (status <> 'open'::text)");
            assertThat(definitions.get("workflow_run_ws_open"))
                    .contains("ON orqix.workflow_run USING btree (workspace_id, created_at, id)")
                    .contains("lifecycle = ANY (ARRAY['running'::text, 'waiting'::text, 'needsAttention'::text])");
            assertThat(definitions.get("workflow_run_ws_completed_ended"))
                    .contains("ON orqix.workflow_run USING btree (workspace_id, ended_at)")
                    .contains("WHERE (lifecycle = 'completed'::text)");
        }
    }
}
