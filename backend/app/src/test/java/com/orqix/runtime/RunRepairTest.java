package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.runtime.internal.RunWatchdog;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/** EX09 consistency check and projection repair (docs/build/S46) on PostgreSQL and Flowable through the public API. */
class RunRepairTest extends RuntimeTestBase {

    @Autowired RuntimeService engine;
    @Autowired TaskService engineTasks;
    @Autowired RunWatchdog watchdog;
    @Autowired TransactionTemplate transactions;

    private UUID startPurchase() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        return UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
    }

    private JsonNode diagnosis(String user, UUID run) throws Exception {
        return api(user).get("/workspaces/{ws}/runs/{id}/diagnosis", PURCHASING, run).body();
    }

    private Response repair(String user, UUID run, int version, String key) throws Exception {
        return api(user).post(Map.of("reason", "Rebuild after projection loss", "expectedVersion", version), key,
                "/workspaces/{ws}/runs/{id}/repair", PURCHASING, run);
    }

    private void blankProjection(UUID run) {
        jdbc.sql("UPDATE workflow_run SET current_steps='[]'::jsonb, updated_at=now()-interval '10 minutes' WHERE id=:id")
                .param("id", run).update();
    }

    private long events(UUID run, String type) {
        return jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:id AND type=:t").param("id", run).param("t", type)
                .query(Long.class).single();
    }

    @Test
    void lostApprovalProjectionIsRebuiltFromTheEngineWithoutTouchingIt() throws Exception {
        UUID run = startPurchase();
        String engineTask = openTask(PURCHASING, run, "manager").path("id").asString();
        String process = processInstance(PURCHASING, run);
        assertThat(diagnosis("olga.operator", run).path("state").asString()).isEqualTo("inSync");

        blankProjection(run);
        // The watchdog must leave a request that the engine still works on alone.
        watchdog.inspect();
        assertThat(lifecycle(PURCHASING, run)).isNotEqualTo("needsAttention");

        JsonNode found = diagnosis("olga.operator", run);
        assertThat(found.path("state").asString()).isEqualTo("projectionDrift");
        assertThat(found.path("repairable").asBoolean()).isTrue();
        assertThat(found.path("engineNodeIds").get(0).asString()).isEqualTo("manager");
        int version = run(PURCHASING, run).path("version").asInt();

        // Permission: the requester can neither inspect nor repair.
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{id}/diagnosis", PURCHASING, run).status()).isEqualTo(403);
        assertThat(repair("rhea.requester", run, version, UUID.randomUUID().toString()).status()).isEqualTo(403);
        assertThat(events(run, "RUN_PROJECTION_REPAIRED")).isZero();

        // A stale version is refused without changing anything.
        assertThat(repair("olga.operator", run, version + 7, UUID.randomUUID().toString()).status()).isEqualTo(409);

        String key = UUID.randomUUID().toString();
        Response repaired = repair("olga.operator", run, version, key);
        assertThat(repaired.status()).as(repaired.toString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT jsonb_array_length(current_steps) FROM workflow_run WHERE id=:id").param("id", run)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT current_steps->0->>'nodeId' || '/' || (current_steps->0->>'taskId') || '/' || (current_steps->0->>'kind') FROM workflow_run WHERE id=:id")
                .param("id", run).query(String.class).single()).isEqualTo("manager/" + engineTask + "/approval");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");

        // Audited once, replay-safe, and the engine is exactly as before.
        assertThat(events(run, "RUN_PROJECTION_REPAIRED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='run.projection-repaired' AND resource_id=:id")
                .param("id", run.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(repair("olga.operator", run, version, key).status()).isEqualTo(200);
        assertThat(events(run, "RUN_PROJECTION_REPAIRED")).isEqualTo(1);
        assertThat(engineTasks.createTaskQuery().processInstanceId(process).list()).extracting(t -> t.getId())
                .containsExactly(engineTask);

        // Nothing left to repair, and the approval still completes the request.
        assertThat(diagnosis("olga.operator", run).path("state").asString()).isEqualTo("inSync");
        Response again = repair("olga.operator", run, run(PURCHASING, run).path("version").asInt(), UUID.randomUUID().toString());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().path("code").asString()).isEqualTo("RUN_NOT_REPAIRABLE");
        approve("mani.manager", PURCHASING, run, "manager");
        assertThat(engineTasks.createTaskQuery().taskId(engineTask).count()).isZero();
    }

    @Test
    void strandedTokenIsReportedNeverRestartedAndCanBeCancelled() throws Exception {
        UUID run = startPurchase();
        String process = processInstance(PURCHASING, run);
        // Lose the engine task row: the token stays at the approval but nothing can move it.
        jdbc.sql("DELETE FROM act_ru_identitylink WHERE proc_inst_id_=:p OR task_id_ IN (SELECT id_ FROM act_ru_task WHERE proc_inst_id_=:p)")
                .param("p", process).update();
        jdbc.sql("DELETE FROM act_ru_task WHERE proc_inst_id_=:p").param("p", process).update();
        assertThat(engine.getActiveActivityIds(process)).containsExactly("manager");

        JsonNode found = diagnosis("olga.operator", run);
        assertThat(found.path("state").asString()).isEqualTo("strandedToken");
        assertThat(found.path("repairable").asBoolean()).isFalse();
        int version = run(PURCHASING, run).path("version").asInt();
        Response refused = repair("olga.operator", run, version, UUID.randomUUID().toString());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().path("code").asString()).isEqualTo("RUN_NOT_REPAIRABLE");
        assertThat(engine.getActiveActivityIds(process)).containsExactly("manager");
        assertThat(events(run, "RUN_PROJECTION_REPAIRED")).isZero();
        assertThat(run(PURCHASING, run).path("version").asInt()).isEqualTo(version);

        Response cancelled = api("olga.operator").post(Map.of("expectedVersion", version, "reason", "Stranded token"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("cancelled");
    }

    @Test
    void suspendedAndEngineMissingRequestsAreNotRepaired() throws Exception {
        UUID suspended = startPurchase();
        Response s = api("olga.operator").post(Map.of("reason", "Review", "expectedVersion",
                run(PURCHASING, suspended).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, suspended);
        assertThat(s.status()).as(s.toString()).isEqualTo(200);
        blankProjection(suspended);
        assertThat(diagnosis("olga.operator", suspended).path("state").asString()).isEqualTo("protected");
        Response blocked = repair("olga.operator", suspended, run(PURCHASING, suspended).path("version").asInt(),
                UUID.randomUUID().toString());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.body().path("code").asString()).isEqualTo("RUN_NOT_REPAIRABLE");

        UUID missing = startPurchase();
        String process = processInstance(PURCHASING, missing);
        transactions.executeWithoutResult(status -> engine.deleteProcessInstance(process, "Simulate lost engine state"));
        assertThat(diagnosis("olga.operator", missing).path("state").asString()).isEqualTo("engineMissing");
        Response none = repair("olga.operator", missing, run(PURCHASING, missing).path("version").asInt(),
                UUID.randomUUID().toString());
        assertThat(none.status()).isEqualTo(409);
        assertThat(none.body().path("code").asString()).isEqualTo("RUN_NOT_REPAIRABLE");
    }
}
