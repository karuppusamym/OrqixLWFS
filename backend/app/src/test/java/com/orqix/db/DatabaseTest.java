package com.orqix.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.orqix.support.TestDatabase;

/**
 * T-DB-01: the migrate profile applies the migrations and the engine schema to an empty database and the runtime role
 * cannot change append-only tables or the Flyway history. T-DB-02: composite foreign keys reject cross-workspace links.
 */
class DatabaseTest {

    @Test
    void tDb01_migrateProfileOnEmptyDatabaseAndAppendOnlyPrivileges() throws Exception {
        String database = "tdb01_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection admin = superuser("orqix"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + database + " OWNER orqix_migrator");
        }
        try (Connection admin = superuser(database); Statement st = admin.createStatement()) {
            st.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            st.execute("CREATE SCHEMA orqix AUTHORIZATION orqix_migrator");
            st.execute("CREATE SCHEMA flowable AUTHORIZATION orqix_migrator");
            st.execute("GRANT USAGE ON SCHEMA orqix TO orqix_app");
        }
        String url = TestDatabase.jdbcUrl().replaceFirst("/orqix(\\?|$)", "/" + database + "$1");
        try (ConfigurableApplicationContext ignored = TestDatabase.migrate(Map.of("spring.datasource.url", url))) {
            // migration ran during startup
        }
        try (Connection app = DriverManager.getConnection(url, TestDatabase.APP_USER, TestDatabase.APP_PASSWORD);
             Statement st = app.createStatement()) {
            st.execute("SET search_path TO orqix, flowable");
            var rs = st.executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank");
            List<String> versions = new java.util.ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
            assertThat(versions).contains("1", "2", "3", "4", "5");
            rs = st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'flowable' AND table_name = 'act_ru_job'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            // Engine tables are usable by the runtime role (granted by the migration job).
            st.executeQuery("SELECT count(*) FROM act_ru_task").close();
            for (String sql : List.of(
                    "UPDATE audit_event SET outcome = 'failed'",
                    "DELETE FROM audit_event",
                    "UPDATE run_event SET type = 'X_X_X'",
                    "DELETE FROM task_decision",
                    "UPDATE workflow_release SET release_note = 'x'",
                    "DELETE FROM workflow_draft_revision",
                    "UPDATE connection_definition SET version = 9",
                    "DELETE FROM release_artifact",
                    "INSERT INTO flyway_schema_history (installed_rank, description, type, script, installed_by, execution_time, success) VALUES (999, 'x', 'SQL', 'x', 'x', 0, true)",
                    "DELETE FROM flyway_schema_history",
                    "CREATE TABLE orqix.not_allowed (id int)")) {
                assertThatThrownBy(() -> st.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }
        try (Connection admin = superuser("orqix"); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }

    @Test
    void tDb02_compositeForeignKeysRejectCrossWorkspaceLinks() throws Exception {
        TestDatabase.ensureMigrated();
        UUID purchasing = UUID.fromString("00000000-0000-7000-8000-000000000201");
        UUID facilities = UUID.fromString("00000000-0000-7000-8000-000000000203");
        UUID system = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID workflow = UUID.fromString("00000000-0000-7000-8000-000000000701"); // purchasing workflow
        try (Connection app = DriverManager.getConnection(TestDatabase.jdbcUrl(), TestDatabase.APP_USER,
                TestDatabase.APP_PASSWORD); Statement st = app.createStatement()) {
            st.execute("SET search_path TO orqix, flowable");
            assertThat(purchasing).isNotEqualTo(facilities);
            List<String> attempts = List.of(
                    // draft revision of a purchasing workflow stored under facilities
                    "INSERT INTO workflow_draft_revision (workspace_id, workflow_id, revision, definition, schema_version, saved_by) "
                            + "VALUES ('" + facilities + "', '" + workflow + "', 99, '{}', '0.1', '" + system + "')",
                    // grant for a purchasing workflow in facilities
                    "INSERT INTO permission_grant (id, workspace_id, workflow_id, permission, principal_type, principal_value) "
                            + "VALUES ('" + UUID.randomUUID() + "', '" + facilities + "', '" + workflow + "', 'workflow.start', 'idpGroup', 'x')",
                    // run in facilities of a purchasing workflow
                    "INSERT INTO workflow_run (id, workspace_id, workflow_id, release_id, initiator_subject_id, lifecycle, input) "
                            + "VALUES ('" + UUID.randomUUID() + "', '" + facilities + "', '" + workflow + "', '" + UUID.randomUUID()
                            + "', '" + system + "', 'running', '{}')",
                    // notification in facilities for a run that does not exist there
                    "INSERT INTO notification (id, workspace_id, recipient_subject_id, run_id, kind, dedup_key, message) "
                            + "VALUES ('" + UUID.randomUUID() + "', '" + facilities + "', '" + system + "', '" + UUID.randomUUID()
                            + "', 'workflowMessage', '" + UUID.randomUUID() + "', 'x')",
                    // event for a run in another workspace
                    "INSERT INTO run_event (run_id, seq, workspace_id, type) VALUES ('" + UUID.randomUUID() + "', 1, '"
                            + facilities + "', 'RUN_STARTED')",
                    "INSERT INTO task_context (engine_task_id, workspace_id, run_id, node_id, label, candidate_group_key, "
                            + "initiator_subject_id, status) VALUES ('t-" + UUID.randomUUID() + "', '" + facilities + "', '"
                            + UUID.randomUUID() + "', 'n', 'l', 'g', '" + system + "', 'open')",
                    "INSERT INTO effect_journal (id, workspace_id, run_id, node_id, effect_key, connection_key, "
                            + "connection_version, operation, effect_class, request_hash, state) VALUES ('" + UUID.randomUUID()
                            + "', '" + facilities + "', '" + UUID.randomUUID() + "', 'n', '" + UUID.randomUUID()
                            + "', 'c', 1, 'o', 'readOnly', '" + "0".repeat(64) + "', 'pending')");
            for (String sql : attempts) {
                assertThatThrownBy(() -> st.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("foreign key");
            }
            // Self-approval workflows are only allowed in test workspaces (DB check).
            assertThatThrownBy(() -> st.execute("UPDATE workspace SET allow_self_approval_workflows = true WHERE id = '"
                    + facilities + "'")).hasMessageContaining("workspace_self_approval_only_in_test");
        }
    }

    private static Connection superuser(String database) throws SQLException {
        String url = TestDatabase.jdbcUrl();
        String target = url.replaceFirst("/orqix(\\?|$)", "/" + database + "$1");
        return DriverManager.getConnection(target, "postgres", "postgres");
    }
}
