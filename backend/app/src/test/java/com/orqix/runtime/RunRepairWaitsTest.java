package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.MessageSweeper;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * EX09 extension (docs/build/S67): lost projections of date waits, message waits and external-worker steps are rebuilt
 * from what the engine holds, and refused when the engine state does not fully determine them. PostgreSQL and
 * Flowable through the public API.
 */
class RunRepairWaitsTest extends RuntimeTestBase {

    private static final String START = "{\"id\":\"start\",\"type\":\"start\",\"label\":\"Submitted\",\"config\":{}}";
    private static final String END = "{\"id\":\"end\",\"type\":\"end\",\"label\":\"Done\",\"config\":{\"businessOutcome\":\"Completed\",\"outcomeKind\":\"success\"}}";

    @Autowired RuntimeService engine;
    @Autowired ManagementService management;
    @Autowired MessageSweeper sweeper;

    private String topic;

    @BeforeEach
    void grantWork() {
        topic = "repair-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO permission_grant (id, workspace_id, permission, principal_type, principal_value, created_at)
                VALUES (:id, :ws, 'job.work', 'idpGroup', 'orqix-purchasing-workers', now()) ON CONFLICT DO NOTHING
                """).param("id", UUID.randomUUID()).param("ws", PURCHASING).update();
    }

    @AfterEach
    void revokeWork() {
        jdbc.sql("DELETE FROM permission_grant WHERE workspace_id = :ws AND permission = 'job.work'")
                .param("ws", PURCHASING).update();
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode diagnosis(UUID run) throws Exception {
        return api("olga.operator").get("/workspaces/{ws}/runs/{id}/diagnosis", PURCHASING, run).body();
    }

    private Response repair(UUID run, int version, String key) throws Exception {
        return api("olga.operator").post(Map.of("reason", "Rebuild after projection loss", "expectedVersion", version),
                key, "/workspaces/{ws}/runs/{id}/repair", PURCHASING, run);
    }

    private void blankProjection(UUID run) {
        jdbc.sql("UPDATE workflow_run SET current_steps='[]'::jsonb, updated_at=now()-interval '10 minutes' WHERE id=:id")
                .param("id", run).update();
    }

    private long events(UUID run, String type) {
        return jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:id AND type=:t").param("id", run).param("t", type)
                .query(Long.class).single();
    }

    private JsonNode steps(UUID run) {
        return json.parse(jdbc.sql("SELECT current_steps::text FROM workflow_run WHERE id=:id").param("id", run)
                .query(String.class).single());
    }

    private int version(UUID run) throws Exception {
        return run(PURCHASING, run).path("version").asInt();
    }

    private UUID publishDateWait() throws Exception {
        return publishPurchase(PURCHASING, d -> {
            d.set("nodes", json.parse("[" + START + ","
                    + "{\"id\":\"pause\",\"type\":\"wait\",\"label\":\"Wait for the start date\",\"config\":{\"untilField\":\"/input/startDate\"}},"
                    + END + "]"));
            d.set("transitions", json.parse("[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"pause\",\"on\":\"next\"},"
                    + "{\"id\":\"t2\",\"from\":\"pause\",\"to\":\"end\",\"on\":\"next\"}]"));
            d.set("decisions", json.parse("[]"));
            d.set("outputMapping", json.object());
            d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
            d.set("dependencies", json.parse("{\"connections\":[]}"));
            d.set("inputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"required\":[\"startDate\"],\"properties\":{\"startDate\":{\"type\":\"string\","
                    + "\"format\":\"date\",\"title\":\"Start date\"}}}"));
            d.remove("inputUi");
            d.remove("uiLayout");
        });
    }

    private UUID startDateWait() throws Exception {
        UUID workflow = publishDateWait();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("startDate", LocalDate.now(ZoneOffset.UTC).plusDays(5).toString());
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private UUID startPaymentWait(String orderNumber) throws Exception {
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/order-payment-wait.json")));
        UUID workflow = publishPurchase(PURCHASING, d -> example.properties().forEach(e -> {
            if (!e.getKey().equals("workflowKey") && !e.getKey().equals("name")) {
                d.set(e.getKey(), e.getValue());
            }
        }));
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow,
                "input", Map.of("orderNumber", orderNumber, "customerName", "Acme Ltd")), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private UUID startWorker() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, definition -> {
            try {
                ArrayNode nodes = (ArrayNode) definition.path("nodes");
                nodes.add(json.parse("{\"id\":\"check\",\"type\":\"externalTask\",\"label\":\"Check the address\",\"config\":{"
                        + "\"topic\":\"" + topic + "\",\"inputMapping\":{\"purpose\":\"/input/purpose\"},"
                        + "\"result\":[{\"name\":\"score\",\"type\":\"number\",\"required\":true}]}}"));
                ArrayNode transitions = (ArrayNode) definition.path("transitions");
                for (JsonNode t : transitions) {
                    if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                        ((ObjectNode) t).put("to", "check");
                    }
                }
                transitions.add(json.parse("{\"id\":\"x1\",\"from\":\"check\",\"to\":\"manager\",\"on\":\"next\"}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM external_job WHERE run_id = :run AND status = 'available'")
                .param("run", run).query(Long.class).single() == 1);
        return run;
    }

    // ------------------------------------------------------------------ date wait

    @Test
    void lostDateWaitProjectionIsRebuiltFromTheEngineTimer() throws Exception {
        UUID run = startDateWait();
        String process = processInstance(PURCHASING, run);
        JsonNode original = steps(run).get(0);
        assertThat(original.path("kind").asString()).isEqualTo("wait");
        assertThat(diagnosis(run).path("state").asString()).isEqualTo("inSync");
        String timerBefore = management.createTimerJobQuery().processInstanceId(process).singleResult().getId();

        blankProjection(run);
        JsonNode found = diagnosis(run);
        assertThat(found.path("state").asString()).isEqualTo("projectionDrift");
        assertThat(found.path("repairable").asBoolean()).isTrue();
        assertThat(found.path("engineNodeIds").get(0).asString()).isEqualTo("pause");

        int version = version(run);
        String key = UUID.randomUUID().toString();
        Response repaired = repair(run, version, key);
        assertThat(repaired.status()).as(repaired.toString()).isEqualTo(200);

        JsonNode restored = steps(run).get(0);
        assertThat(steps(run)).hasSize(1);
        assertThat(restored.path("nodeId").asString()).isEqualTo("pause");
        assertThat(restored.path("kind").asString()).isEqualTo("wait");
        assertThat(restored.path("label").asString()).isEqualTo(original.path("label").asString());
        assertThat(java.time.Instant.parse(restored.path("dueAt").asString()))
                .isEqualTo(java.time.Instant.parse(original.path("dueAt").asString()));
        assertThat(restored.path("message").isMissingNode()).isTrue();
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");

        // The engine timer is the very same job, untouched; audit and timeline once; replay safe.
        assertThat(management.createTimerJobQuery().processInstanceId(process).singleResult().getId()).isEqualTo(timerBefore);
        assertThat(events(run, "RUN_PROJECTION_REPAIRED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='run.projection-repaired' AND resource_id=:id")
                .param("id", run.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(repair(run, version, key).status()).isEqualTo(200);
        assertThat(events(run, "RUN_PROJECTION_REPAIRED")).isEqualTo(1);
        assertThat(diagnosis(run).path("state").asString()).isEqualTo("inSync");
        assertThat(repair(run, version(run), UUID.randomUUID().toString()).status()).isEqualTo(409);
        // Roles stay as in S49.
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{id}/diagnosis", PURCHASING, run).status()).isEqualTo(403);
        cancel(run);
    }

    @Test
    void anUnexplainedEngineTimerRefusesTheRebuild() throws Exception {
        UUID run = startDateWait();
        String process = processInstance(PURCHASING, run);
        blankProjection(run);
        // Simulate a state this check cannot interpret: the timer job points at an element it does not know.
        jdbc.sql("UPDATE act_ru_timer_job SET element_id_='mystery' WHERE process_instance_id_=:p").param("p", process).update();
        JsonNode found = diagnosis(run);
        assertThat(found.path("state").asString()).isEqualTo("unsupported");
        assertThat(found.path("repairable").asBoolean()).isFalse();
        assertThat(found.path("message").asString()).contains("does not fully determine");
        Response refused = repair(run, version(run), UUID.randomUUID().toString());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().path("code").asString()).isEqualTo("RUN_NOT_REPAIRABLE");
        assertThat(steps(run)).isEmpty();
        assertThat(events(run, "RUN_PROJECTION_REPAIRED")).isZero();
        cancel(run);
    }

    // ------------------------------------------------------------------ message wait

    @Test
    void lostMessageWaitProjectionIsRebuiltAndTheMessageStillArrives() throws Exception {
        String order = "SO-" + UUID.randomUUID().toString().substring(0, 8);
        UUID run = startPaymentWait(order);
        JsonNode original = steps(run).get(0);
        assertThat(original.path("message").asString()).isEqualTo("payment-received");
        assertThat(original.path("dueAt").isNull()).isFalse();
        assertThat(diagnosis(run).path("state").asString()).isEqualTo("inSync");
        String process = processInstance(PURCHASING, run);
        long subscriptionsBefore = engine.createEventSubscriptionQuery().processInstanceId(process).count();

        blankProjection(run);
        JsonNode found = diagnosis(run);
        assertThat(found.path("state").asString()).as(found.toString()).isEqualTo("projectionDrift");
        assertThat(found.path("engineNodeIds").get(0).asString()).isEqualTo("waitPayment");
        Response repaired = repair(run, version(run), UUID.randomUUID().toString());
        assertThat(repaired.status()).as(repaired.toString()).isEqualTo(200);

        JsonNode restored = steps(run).get(0);
        assertThat(steps(run)).hasSize(1);
        assertThat(restored.path("nodeId").asString()).isEqualTo("waitPayment");
        assertThat(restored.path("kind").asString()).isEqualTo("wait");
        assertThat(restored.path("message").asString()).isEqualTo("payment-received");
        assertThat(restored.path("label").asString()).isEqualTo(original.path("label").asString());
        assertThat(java.time.Instant.parse(restored.path("dueAt").asString()))
                .isEqualTo(java.time.Instant.parse(original.path("dueAt").asString()));
        assertThat(engine.createEventSubscriptionQuery().processInstanceId(process).count()).isEqualTo(subscriptionsBefore);
        assertThat(diagnosis(run).path("state").asString()).isEqualTo("inSync");

        // The repaired request still receives its message and finishes.
        Response sent = api("olga.operator").post(Map.of("name", "payment-received", "correlationKey", order,
                "payload", Map.of("paymentReference", "PAY-77", "amountPaid", 125.50)), UUID.randomUUID().toString(),
                "/workspaces/{ws}/messages", PURCHASING);
        assertThat(sent.status()).as(sent.toString()).isIn(200, 201, 202);
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(300)).until(() -> {
            sweeper.run();
            return "completed".equals(lifecycle(PURCHASING, run));
        });
    }

    @Test
    void aMessageWaitWithoutItsSubscriptionRowIsNotRebuilt() throws Exception {
        UUID run = startPaymentWait("SO-" + UUID.randomUUID().toString().substring(0, 8));
        blankProjection(run);
        jdbc.sql("UPDATE message_subscription SET status='cancelled', closed_at=now() WHERE run_id=:id").param("id", run).update();
        JsonNode found = diagnosis(run);
        assertThat(found.path("state").asString()).isEqualTo("unsupported");
        assertThat(found.path("message").asString()).contains("no open subscription");
        Response refused = repair(run, version(run), UUID.randomUUID().toString());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(steps(run)).isEmpty();
        assertThat(events(run, "RUN_PROJECTION_REPAIRED")).isZero();
        cancel(run);
    }

    // ------------------------------------------------------------------ external worker

    @Test
    void lostExternalWorkerStepIsRebuiltAndTheJobKeepsItsLeaseState() throws Exception {
        UUID run = startWorker();
        String process = processInstance(PURCHASING, run);
        JsonNode original = steps(run).get(0);
        assertThat(original.path("kind").asString()).isEqualTo("external");
        assertThat(diagnosis(run).path("state").asString()).isEqualTo("inSync");

        blankProjection(run);
        JsonNode found = diagnosis(run);
        assertThat(found.path("state").asString()).as(found.toString()).isEqualTo("projectionDrift");
        assertThat(found.path("engineNodeIds").get(0).asString()).isEqualTo("check");
        Response repaired = repair(run, version(run), UUID.randomUUID().toString());
        assertThat(repaired.status()).as(repaired.toString()).isEqualTo(200);
        JsonNode restored = steps(run).get(0);
        assertThat(restored.path("kind").asString()).isEqualTo("external");
        assertThat(restored.path("topic").asString()).isEqualTo(topic);
        assertThat(restored.path("label").asString()).isEqualTo(original.path("label").asString());
        assertThat(engine.getActiveActivityIds(process)).containsExactly("check");
        assertThat(jdbc.sql("SELECT status || '/' || attempt || '/' || fence FROM external_job WHERE run_id=:id")
                .param("id", run).query(String.class).single()).isEqualTo("available/0/0");
        assertThat(diagnosis(run).path("state").asString()).isEqualTo("inSync");

        // A worker can still lease and complete it; the request moves on to the approval.
        Response fetched = api("wally.worker").post(Map.of("workerId", "w1", "topics", List.of(topic), "maxJobs", 5,
                "leaseSeconds", 60), "/workspaces/{ws}/external-jobs/fetch-and-lock", PURCHASING);
        JsonNode job = fetched.body().path("items").get(0);
        Response done = api("wally.worker").post(Map.of("workerId", "w1", "fence", job.path("fence").asInt(),
                "result", Map.of("score", 3)), UUID.randomUUID().toString(),
                "/workspaces/{ws}/external-jobs/{id}/complete", PURCHASING, job.path("id").asString());
        assertThat(done.status()).as(done.toString()).isEqualTo(204);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(
                () -> steps(run).size() == 1 && "approval".equals(steps(run).get(0).path("kind").asString()));
        cancel(run);
    }

    @Test
    void aWorkerStepWhoseJobIsGoneIsNotRebuilt() throws Exception {
        UUID run = startWorker();
        blankProjection(run);
        jdbc.sql("UPDATE external_job SET status='cancelled', ended_at=now() WHERE run_id=:id").param("id", run).update();
        JsonNode found = diagnosis(run);
        // The engine token waits at the worker step with nothing that can move it: reported, never restarted.
        assertThat(found.path("state").asString()).isEqualTo("strandedToken");
        assertThat(found.path("repairable").asBoolean()).isFalse();
        assertThat(repair(run, version(run), UUID.randomUUID().toString()).status()).isEqualTo(409);
        assertThat(steps(run)).isEmpty();
        cancel(run);
    }

    private void cancel(UUID run) throws Exception {
        Response cancelled = api("olga.operator").post(Map.of("expectedVersion", version(run), "reason", "Test cleanup"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
    }
}
