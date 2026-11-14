package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-O03 guarded move (docs/build/S143) on PostgreSQL and Flowable through the public API: skip, re-run and move,
 * journal-aware re-runs that never call the other system twice, refusals with reasons, and the second operator in a
 * production workspace.
 */
class RunMoveTest extends RuntimeTestBase {

    private static final UUID SYSTEM = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** The purchasing workspace has no environment row, so it is production; some tests make it development. */
    private void development() {
        jdbc.sql("""
                INSERT INTO workspace_environment (workspace_id, stage, updated_by) VALUES (:ws, 'development', :by)
                ON CONFLICT (workspace_id) DO UPDATE SET stage = 'development'
                """).param("ws", PURCHASING).param("by", SYSTEM).update();
    }

    @AfterEach
    void productionAgain() {
        jdbc.sql("DELETE FROM workspace_environment WHERE workspace_id = :ws").param("ws", PURCHASING).update();
    }

    private UUID startRun(UUID workflow, String amount) throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal(amount)).path("id").asString());
    }

    private int version(UUID run) throws Exception {
        return run(PURCHASING, run).path("version").asInt();
    }

    private JsonNode plan(UUID run, String kind, String from, String to) throws Exception {
        Response r = api("olga.operator").post(Map.of("kind", kind, "fromStepId", from, "toStepId", to),
                "/workspaces/{ws}/runs/{id}/move-plan", PURCHASING, run);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body();
    }

    private Response move(String user, UUID run, String kind, String from, String to, int version, String key)
            throws Exception {
        return api(user).post(Map.of("kind", kind, "fromStepId", from, "toStepId", to, "reason", "Operator correction",
                "expectedVersion", version), key, "/workspaces/{ws}/runs/{id}/moves", PURCHASING, run);
    }

    private Response move(UUID run, String kind, String from, String to) throws Exception {
        return move("olga.operator", run, kind, from, to, version(run), UUID.randomUUID().toString());
    }

    private long events(UUID run, String type) {
        return jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :id AND type = :type").param("id", run)
                .param("type", type).query(Long.class).single();
    }

    @Test
    void skipRerunAndMoveRunAtOnceOutsideProduction() throws Exception {
        development();
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = startRun(workflow, "6000");
        String managerTask = openTask(PURCHASING, run, "manager").path("id").asString();

        JsonNode plan = plan(run, "skip", "manager", "amountPolicy");
        assertThat(plan.path("allowed").asBoolean()).as(plan.toString()).isTrue();
        assertThat(plan.path("approvalRequired").asBoolean()).isFalse();
        assertThat(plan.path("skipped").get(0).path("nodeId").asString()).isEqualTo("manager");
        assertThat(api("rhea.requester").post(Map.of("kind", "skip", "fromStepId", "manager", "toStepId", "amountPolicy"),
                "/workspaces/{ws}/runs/{id}/move-plan", PURCHASING, run).status()).isEqualTo(403);
        // A mismatched intent and an end as target are refused with reasons.
        JsonNode mismatch = plan(run, "move", "manager", "amountPolicy");
        assertThat(mismatch.path("findings").get(0).path("code").asString()).isEqualTo("MOVE_KIND_MISMATCH");
        Response toEnd = move(run, "move", "manager", "fulfilled");
        assertThat(toEnd.status()).isEqualTo(409);
        assertThat(toEnd.body().path("code").asString()).isEqualTo("RUN_NOT_MOVABLE");
        assertThat(toEnd.body().path("findings").get(0).path("code").asString()).isEqualTo("MOVE_TARGET_KIND");
        assertThat(move("olga.operator", run, "skip", "manager", "amountPolicy", version(run) + 4,
                UUID.randomUUID().toString()).status()).isEqualTo(409);

        String key = UUID.randomUUID().toString();
        int v = version(run);
        Response skipped = move("olga.operator", run, "skip", "manager", "amountPolicy", v, key);
        assertThat(skipped.status()).as(skipped.toString()).isEqualTo(200);
        assertThat(skipped.body().path("state").asString()).isEqualTo("executed");
        // The decision ran on the request data and sent 6,000 to finance; the manager task is closed.
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "finance") != null);
        assertThat(jdbc.sql("SELECT status FROM task_context WHERE engine_task_id = :t").param("t", managerTask)
                .query(String.class).single()).isEqualTo("cancelled");
        Response replay = move("olga.operator", run, "skip", "manager", "amountPolicy", v, key);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(events(run, "RUN_MOVED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata->>'kind' FROM audit_event WHERE resource_id = :id AND action = 'run.moved'")
                .param("id", run.toString()).query(String.class).single()).isEqualTo("skip");

        // Re-run from the manager: everything between runs again, nothing with an outside effect is on the way.
        JsonNode rerun = plan(run, "rerun", "finance", "manager");
        assertThat(rerun.path("allowed").asBoolean()).as(rerun.toString()).isTrue();
        assertThat(rerun.path("rerun")).extracting(n -> n.path("nodeId").asString())
                .contains("manager", "amountPolicy", "route", "finance");
        assertThat(move(run, "rerun", "finance", "manager").status()).isEqualTo(200);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "manager") != null);
        assertThat(openTask(PURCHASING, run, "finance")).isNull();
        assertThat(run(PURCHASING, run).path("currentSteps")).hasSize(1);
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "finance") != null);
        approve("fin.finance", PURCHASING, run, "finance");
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(Stub.postCount()).isEqualTo(1);
        JsonNode moves = api("olga.operator").get("/workspaces/{ws}/runs/{id}/moves", PURCHASING, run).body();
        assertThat(moves.path("items")).hasSize(2);
    }

    /** procure -> {@code between} -> receipt (approval) -> notify. */
    private UUID withReceipt(String between) throws Exception {
        return publishPurchase(PURCHASING, d -> {
            ArrayNode nodes = (ArrayNode) d.path("nodes");
            nodes.add(json.parse("{\"id\":\"receipt\",\"type\":\"approval\",\"label\":\"Confirm receipt\",\"config\":"
                    + "{\"candidateGroupRef\":\"managers\",\"rejectReasonRequired\":true}}"));
            String afterProcure = "receipt";
            if (between != null) {
                nodes.add(json.parse("{\"id\":\"" + between + "\",\"type\":\"notification\",\"label\":\"Tell the"
                        + " requester\",\"config\":{\"channel\":\"inApp\",\"recipient\":\"initiator\",\"message\":"
                        + "\"The order was placed.\"}}"));
                afterProcure = between;
            }
            for (JsonNode t : d.path("transitions")) {
                if ("t9".equals(t.path("id").asString())) {
                    ((ObjectNode) t).put("to", afterProcure);
                }
            }
            ArrayNode transitions = (ArrayNode) d.path("transitions");
            if (between != null) {
                transitions.add(json.parse("{\"id\":\"tb\",\"from\":\"" + between + "\",\"to\":\"receipt\",\"on\":\"next\"}"));
            }
            transitions.add(json.parse("{\"id\":\"tr\",\"from\":\"receipt\",\"to\":\"notify\",\"on\":\"approved\"}"));
            transitions.add(json.parse("{\"id\":\"tx\",\"from\":\"receipt\",\"to\":\"notifyRejected\",\"on\":\"rejected\"}"));
            d.remove("uiLayout");
        });
    }

    @Test
    void aReRunOfACallReusesTheRecordedResultAndOtherEffectsAreNotRepeated() throws Exception {
        development();
        UUID run = startRun(withReceipt(null), "100");
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "receipt") != null);
        assertThat(Stub.postCount()).isEqualTo(1);

        JsonNode plan = plan(run, "rerun", "receipt", "procure");
        assertThat(plan.path("allowed").asBoolean()).as(plan.toString()).isTrue();
        assertThat(plan.path("rerun").get(0).path("handling").asString()).isEqualTo("reuseRecorded");
        assertThat(move(run, "rerun", "receipt", "procure").status()).isEqualTo(200);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> fastForwarded(run)
                && openTask(PURCHASING, run, "receipt") != null);
        // The call ran again inside Orqix, but nothing was sent: the journal answered.
        assertThat(Stub.postCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id = :id").param("id", run)
                .query(Long.class).single()).isEqualTo(1);
        approve("mani.manager", PURCHASING, run, "receipt");
        awaitLifecycle(PURCHASING, run, "completed");

        // A notice between the call and the receipt would be sent twice: refused.
        UUID noticed = startRun(withReceipt("ack"), "100");
        approve("mani.manager", PURCHASING, noticed, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, noticed, "receipt") != null);
        Response refused = move(noticed, "rerun", "receipt", "procure");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().path("findings")).extracting(f -> f.path("code").asString())
                .contains("EFFECT_WOULD_REPEAT");
        // Running the receipt step itself again is fine.
        assertThat(move(noticed, "rerun", "receipt", "receipt").status()).isEqualTo(200);
        assertThat(openTask(PURCHASING, noticed, "receipt")).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :id AND node_id = 'receipt'")
                .param("id", noticed).query(Long.class).single()).isEqualTo(2);
    }

    private boolean fastForwarded(UUID run) {
        fastForward(PURCHASING, run);
        return true;
    }

    @Test
    void aFailedCallIsSkippedButACallInDoubtMustBeReconciledFirst() throws Exception {
        development();
        UUID workflow = publishPurchase(PURCHASING, null);
        Stub.fault(Stub.Mode.STATUS400, 1, 0);
        UUID run = startRun(workflow, "100");
        approve("mani.manager", PURCHASING, run, "manager");
        awaitLifecycle(PURCHASING, run, "needsAttention");
        JsonNode plan = plan(run, "skip", "procure", "notify");
        assertThat(plan.path("allowed").asBoolean()).as(plan.toString()).isTrue();
        Response skipped = move(run, "skip", "procure", "notify");
        assertThat(skipped.status()).as(skipped.toString()).isEqualTo(200);
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(Stub.postCount()).isEqualTo(1);

        // A call whose outcome nobody confirmed (the journal says unknown) is never moved away from.
        Stub.fault(Stub.Mode.STATUS400, 1, 0);
        UUID unknown = startRun(workflow, "100");
        approve("mani.manager", PURCHASING, unknown, "manager");
        awaitLifecycle(PURCHASING, unknown, "needsAttention");
        jdbc.sql("UPDATE effect_journal SET state = 'unknown' WHERE run_id = :id").param("id", unknown).update();
        Response refused = move(unknown, "skip", "procure", "notify");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().path("findings")).extracting(f -> f.path("code").asString())
                .contains("EFFECT_IN_FLIGHT");
    }

    @Test
    void inProductionASecondOperatorApprovesAndTheVersionMustStillHold() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = startRun(workflow, "6000");
        JsonNode plan = plan(run, "skip", "manager", "amountPolicy");
        assertThat(plan.path("approvalRequired").asBoolean()).isTrue();
        assertThat(plan.path("approvalReason").asString()).contains("production");

        int version = version(run);
        Response requested = move("olga.operator", run, "skip", "manager", "amountPolicy", version,
                UUID.randomUUID().toString());
        assertThat(requested.status()).as(requested.toString()).isEqualTo(200);
        assertThat(requested.body().path("state").asString()).isEqualTo("pendingApproval");
        String moveId = requested.body().path("id").asString();
        // Nothing moved yet, and the request itself is unchanged.
        assertThat(openTask(PURCHASING, run, "manager")).isNotNull();
        assertThat(version(run)).isEqualTo(version);
        assertThat(events(run, "RUN_MOVE_REQUESTED")).isEqualTo(1);
        // A second move cannot be requested while one waits.
        assertThat(move(run, "skip", "manager", "amountPolicy").body().path("code").asString())
                .isEqualTo("RUN_NOT_MOVABLE");

        Response self = api("olga.operator").post(Map.of("note", "Looks right"), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/moves/{move}/approve", PURCHASING, run, moveId);
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.body().path("code").asString()).isEqualTo("MOVE_SAME_OPERATOR");
        assertThat(api("rhea.requester").post(Map.of(), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/moves/{move}/approve", PURCHASING, run, moveId).status()).isEqualTo(403);

        String key = UUID.randomUUID().toString();
        Response approved = api("opal.operator").post(Map.of("note", "Checked with the manager"), key,
                "/workspaces/{ws}/runs/{id}/moves/{move}/approve", PURCHASING, run, moveId);
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);
        assertThat(approved.body().path("state").asString()).isEqualTo("executed");
        assertThat(approved.body().path("decidedBy").asString()).isNotEqualTo(approved.body().path("requestedBy").asString());
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "finance") != null);
        assertThat(jdbc.sql("SELECT metadata->>'fourEyes' FROM audit_event WHERE resource_id = :id AND action = 'run.moved'")
                .param("id", run.toString()).query(String.class).single()).isEqualTo("true");
        assertThat(api("opal.operator").post(Map.of("note", "Checked with the manager"), key,
                "/workspaces/{ws}/runs/{id}/moves/{move}/approve", PURCHASING, run, moveId)
                .header("Idempotent-Replayed")).isEqualTo("true");
        // The evidence pack lists the requested and the executed move among the operator corrections.
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :id AND action IN ('run.move-requested', 'run.moved')")
                .param("id", run.toString()).query(Long.class).single()).isEqualTo(2);

        // A request that changes after the move was planned: the approval finds it stale and runs nothing.
        UUID other = startRun(workflow, "6000");
        String staleId = move(other, "skip", "manager", "amountPolicy").body().path("id").asString();
        Response suspended = api("olga.operator").post(Map.of("reason", "Pause", "expectedVersion", version(other)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, other);
        assertThat(suspended.status()).isEqualTo(200);
        Response stale = api("opal.operator").post(Map.of(), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/moves/{move}/approve", PURCHASING, other, staleId);
        assertThat(stale.status()).isEqualTo(200);
        assertThat(stale.body().path("state").asString()).isEqualTo("stale");
        assertThat(openTask(PURCHASING, other, "manager")).isNotNull();

        // The requester withdraws a pending move; another operator rejects one.
        UUID third = startRun(workflow, "6000");
        String withdrawId = move(third, "skip", "manager", "amountPolicy").body().path("id").asString();
        Response withdrawn = api("olga.operator").post(Map.of("note", "Wrong step"), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/moves/{move}/reject", PURCHASING, third, withdrawId);
        assertThat(withdrawn.body().path("state").asString()).isEqualTo("withdrawn");
        String rejectId = move(third, "skip", "manager", "amountPolicy").body().path("id").asString();
        Response rejected = api("opal.operator").post(Map.of("note", "Ask the manager"), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/moves/{move}/reject", PURCHASING, third, rejectId);
        assertThat(rejected.body().path("state").asString()).isEqualTo("rejected");
        assertThat(api("opal.operator").post(Map.of(), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/moves/{move}/approve", PURCHASING, third, rejectId).body().path("code")
                .asString()).isEqualTo("MOVE_NOT_PENDING");
        assertThat(openTask(PURCHASING, third, "manager")).isNotNull();
    }
}
