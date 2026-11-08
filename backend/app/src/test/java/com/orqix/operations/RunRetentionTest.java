package com.orqix.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.operations.retention.RetentionSweeper;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * CG-O07 and CG-P04 (docs/build/S144): per-workflow retention, legal holds on a request or a workflow, dry runs,
 * archive (evidence pack plus exit-export-style data, checksum verified after storing) then purge of the operational
 * rows through the guarded database function, and batch purge of the engine history. Real PostgreSQL and Flowable;
 * the archive store is the file system under the test temp directory.
 */
class RunRetentionTest extends RuntimeTestBase {

    @Autowired
    private RetentionSweeper sweeper;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM retention_policy WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        jdbc.sql("""
                UPDATE legal_hold SET released_at = now(), released_by = placed_by, release_reason = 'test cleanup'
                WHERE workspace_id = :ws AND released_at IS NULL
                """).param("ws", PURCHASING).update();
    }

    private UUID completedRun(UUID workflow) throws Exception {
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        awaitLifecycle(PURCHASING, run, "completed");
        return run;
    }

    private Response setPolicy(int keepDays, int expectedVersion, UUID workflow) throws Exception {
        return api("wade.admin").put(Map.of("keepDays", keepDays, "expectedVersion", expectedVersion), null,
                "/workspaces/{ws}/workflows/{wf}/retention-policy", PURCHASING, workflow);
    }

    private JsonNode sweep(boolean dryRun, Instant at) throws Exception {
        UUID id = sweeper.sweep(PURCHASING, dryRun, null, at);
        for (JsonNode s : api("wade.admin").get("/workspaces/{ws}/retention-sweeps", PURCHASING).body().path("items")) {
            if (s.path("id").asString().equals(id.toString())) {
                return s;
            }
        }
        throw new AssertionError("sweep not listed");
    }

    private Response hold(Map<String, Object> target) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(target);
        body.put("reason", "Litigation notice 2026-17");
        return api("wade.admin").post(body, "/workspaces/{ws}/legal-holds", PURCHASING);
    }

    private void release(Response hold) throws Exception {
        Response released = api("wade.admin").post(Map.of("reason", "Matter closed"),
                "/workspaces/{ws}/legal-holds/{id}/release", PURCHASING, hold.body().path("id").asString());
        assertThat(released.status()).as(released.toString()).isEqualTo(200);
        assertThat(released.body().path("active").asBoolean()).isFalse();
    }

    private long engineHistory(String table, String pid) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE proc_inst_id_ = :pid").param("pid", pid)
                .query(Long.class).single();
    }

    @Test
    void holdsBlockThenTheRequestIsArchivedVerifiedAndPurgedWithItsEngineHistory() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID done = completedRun(workflow);
        UUID open = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        String pid = processInstance(PURCHASING, done);
        String reference = run(PURCHASING, done).path("reference").asString();
        assertThat(engineHistory("act_hi_actinst", pid)).isPositive();

        // Only workspace administrators set policies; auditors may read; a viewer may not.
        Response denied = api("olga.operator").put(Map.of("keepDays", 1, "expectedVersion", 0), null,
                "/workspaces/{ws}/workflows/{wf}/retention-policy", PURCHASING, workflow);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(api("vic.viewer").get("/workspaces/{ws}/run-archives", PURCHASING).status()).isEqualTo(403);
        Response policy = setPolicy(1, 0, workflow);
        assertThat(policy.status()).as(policy.toString()).isEqualTo(200);
        assertThat(setPolicy(1, 0, workflow).status()).isEqualTo(409);
        Instant later = Instant.now().plus(Duration.ofDays(2));

        // A hold on the request blocks it; the open request is never a candidate.
        Response runHold = hold(Map.of("runId", done.toString()));
        assertThat(runHold.status()).as(runHold.toString()).isEqualTo(201);
        assertThat(hold(Map.of("runId", done.toString())).status()).isEqualTo(409);
        JsonNode dry = sweep(true, later);
        assertThat(dry.path("mode").asString()).isEqualTo("dryRun");
        assertThat(dry.path("held").asInt()).isEqualTo(1);
        assertThat(dry.path("eligible").asInt()).isZero();
        assertThat(dry.path("detail").path("items").get(0).path("reason").asString()).isEqualTo("legal_hold");
        assertThat(dry.path("detail").toString()).doesNotContain(open.toString());
        release(runHold);

        // A hold on the workflow blocks it too.
        Response workflowHold = hold(Map.of("workflowId", workflow.toString()));
        assertThat(workflowHold.status()).isEqualTo(201);
        assertThat(sweep(false, later).path("held").asInt()).isEqualTo(1);
        assertThat(lifecycle(PURCHASING, done)).isEqualTo("completed");
        release(workflowHold);

        // Dry run: eligible, nothing changes. A purge from the API needs confirm.
        JsonNode eligible = sweep(true, later);
        assertThat(eligible.path("eligible").asInt()).isEqualTo(1);
        assertThat(eligible.path("purged").asInt()).isZero();
        assertThat(lifecycle(PURCHASING, done)).isEqualTo("completed");
        Response unconfirmed = api("wade.admin").post(Map.of("dryRun", false), "/workspaces/{ws}/retention-sweeps",
                PURCHASING);
        assertThat(unconfirmed.status()).as(unconfirmed.toString()).isEqualTo(422);

        // Purge: archived, read back, recorded; operational rows and engine history gone; audit kept.
        JsonNode purged = sweep(false, later);
        assertThat(purged.path("purged").asInt()).as(purged.toString()).isEqualTo(1);
        assertThat(purged.path("archived").asInt()).isEqualTo(1);
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, done).status()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r").param("r", done).query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :r").param("r", done).query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :r AND action = 'run.archived-and-purged'")
                .param("r", done.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(lifecycle(PURCHASING, open)).isEqualTo("waiting");
        for (String table : List.of("act_hi_procinst", "act_hi_actinst", "act_hi_taskinst", "act_hi_varinst",
                "act_hi_identitylink")) {
            assertThat(engineHistory(table, pid)).as(table).isZero();
        }

        Response archives = api("wade.admin").get("/workspaces/{ws}/run-archives?reference=" + reference, PURCHASING);
        assertThat(archives.status()).as(archives.toString()).isEqualTo(200);
        JsonNode archive = archives.body().path("items").get(0);
        assertThat(archive.path("status").asString()).isEqualTo("purged");
        assertThat(archive.path("historyStatus").asString()).isEqualTo("purged");
        assertThat(archive.path("store").asString()).isEqualTo("filesystem");
        assertThat(archive.path("purgeCounts").path("run_event").asInt()).isPositive();
        Response download = api("wade.admin").get("/workspaces/{ws}/run-archives/{id}/content", PURCHASING,
                archive.path("id").asString());
        assertThat(download.status()).as(download.toString()).isEqualTo(200);
        assertThat(com.orqix.shared.json.Json.sha256Hex(download.bytes())).isEqualTo(archive.path("sha256").asString());
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download.bytes()))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                entries.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries.keySet()).contains("manifest.json", "evidence-pack.json", "evidence-pack.pdf",
                "history/runs.jsonl", "history/tasks.jsonl", "history/decisions.jsonl", "history/run-events.jsonl",
                "history/effects.jsonl", "audit/audit-events.jsonl", "files/attachments.jsonl", "SHA256SUMS");
        assertThat(entries.get("history/runs.jsonl")).contains(reference).contains("\"input\"");
        assertThat(json.parse(entries.get("evidence-pack.json")).path("run").path("reference").asString())
                .isEqualTo(reference);
        JsonNode manifest = json.parse(entries.get("manifest.json"));
        assertThat(manifest.path("format").asString()).isEqualTo("orqix-run-archive");
        assertThat(manifest.path("counts").path("decisions").asInt()).isEqualTo(1);
        assertThat(entries.get("SHA256SUMS")).contains("evidence-pack.json");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'retention.archive-downloaded'")
                .query(Long.class).single()).isPositive();

        // Nothing left to do: a second sweep finds nothing.
        assertThat(sweep(false, later).path("eligible").asInt()).isZero();
    }

    @Test
    void thePurgeFunctionReChecksItsGuardsAndAppendOnlyTablesStayProtected() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID open = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        assertThatThrownBy(() -> jdbc.sql("SELECT orqix_purge_run(:ws, :run)::text").param("ws", PURCHASING)
                .param("run", open).query(String.class).single()).hasMessageContaining("retention:not_ended");
        UUID done = completedRun(workflow);
        assertThatThrownBy(() -> jdbc.sql("SELECT orqix_purge_run(:ws, :run)::text").param("ws", PURCHASING)
                .param("run", done).query(String.class).single()).hasMessageContaining("retention:not_archived");
        // The runtime role still cannot delete request history directly.
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM run_event WHERE run_id = :r").param("r", done).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThat(lifecycle(PURCHASING, done)).isEqualTo("completed");
    }

    /**
     * Every table that references a request, directly or through a task, effect or other dependent row, and every
     * table with a request id column, is either purged or detached by orqix_purge_run or deliberately kept. A branch
     * that adds such a table must extend the function (or this list of kept tables).
     */
    @Test
    void thePurgeFunctionCoversEveryTableThatReferencesARequest() {
        String function = jdbc.sql("SELECT pg_get_functiondef('orqix_purge_run(uuid, uuid)'::regprocedure)")
                .query(String.class).single();
        List<String> dependents = jdbc.sql("""
                WITH RECURSIVE fk AS (
                    SELECT c.conrelid::regclass::text AS child, c.confrelid::regclass::text AS parent
                    FROM pg_constraint c WHERE c.contype = 'f'),
                tree AS (
                    SELECT child FROM fk WHERE parent IN ('workflow_run', 'orqix.workflow_run')
                    UNION SELECT fk.child FROM fk JOIN tree ON fk.parent = tree.child)
                SELECT DISTINCT regexp_replace(child, '^orqix\\.', '') FROM tree
                """).query(String.class).list();
        List<String> withRunColumn = jdbc.sql("""
                SELECT DISTINCT table_name FROM information_schema.columns
                WHERE table_schema = 'orqix' AND column_name IN ('run_id', 'last_run_id', 'parent_run_id')
                """).query(String.class).list();
        // Kept on purpose: the purge refuses a request that a case or an AI evaluation candidate still points at.
        List<String> kept = List.of("workflow_run", "run_archive", "legal_hold", "case_workflow_task", "ai_eval_candidate");
        for (String table : java.util.stream.Stream.concat(dependents.stream(), withRunColumn.stream()).distinct().toList()) {
            if (kept.contains(table)) {
                continue;
            }
            assertThat(function).as("orqix_purge_run handles " + table).contains("'" + table + "'");
        }
    }
}
