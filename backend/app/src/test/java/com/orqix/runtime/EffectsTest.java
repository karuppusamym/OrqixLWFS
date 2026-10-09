package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.Fixtures;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** T-EFF-01 … T-EFF-10: HTTP node, effect journal, outcome classification, failed jobs and operator recovery. */
class EffectsTest extends RuntimeTestBase {

    private static final BigDecimal AMOUNT = new BigDecimal("1800");
    private static boolean nonIdempotentRegistered;

    /** Starts a run of a fresh workflow and approves the manager step; returns the run id. */
    private UUID startAndApprove(UUID workflowId) throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, AMOUNT).path("id").asString());
        approve("mani.manager", PURCHASING, runId, "manager");
        return runId;
    }

    private JsonNode effect(UUID runId) throws Exception {
        return run(PURCHASING, runId).path("effects").get(0);
    }

    private Response retry(UUID runId) throws Exception {
        JsonNode run = run(PURCHASING, runId);
        return api("olga.operator").post(Map.of("reason", "Dependency recovered", "expectedVersion",
                run.path("version").asInt()), UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/retry",
                PURCHASING, runId);
    }

    private Response reconcile(UUID runId, String effectId, String resolution, Map<String, Object> result)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", resolution);
        body.put("evidence", Map.of("note", "Checked the procurement system by phone", "remoteReference", "PO-777"));
        if (result != null) {
            body.put("result", result);
        }
        return api("olga.operator").post(body, UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{run}/effects/{effect}/reconcile", PURCHASING, runId, effectId);
    }

    /** procurement v2: createOrder classified nonIdempotent (no idempotency header) for T-EFF-04/06/07/10. */
    private UUID nonIdempotentWorkflow() throws Exception {
        if (!nonIdempotentRegistered) {
            ObjectNode definition = (ObjectNode) Fixtures.procurementConnection();
            definition.put("version", 2);
            ObjectNode createOrder = (ObjectNode) definition.path("operations").path("createOrder");
            createOrder.put("effectClass", "nonIdempotent");
            createOrder.remove("idempotencyHeader");
            Response registered = api("wade.admin").post(Map.of("definition", definition),
                    "/workspaces/{ws}/connections/{key}/versions", PURCHASING, "procurement");
            assertThat(registered.status()).as(registered.toString()).isIn(201, 409);
            nonIdempotentRegistered = true;
        }
        return publishPurchase(PURCHASING, d -> ((ObjectNode) d.path("dependencies").path("connections").get(0))
                .put("definitionVersion", 2));
    }

    @Test
    void tEff01_happyPathOneOrderResultStoredFulfilled() throws Exception {
        UUID runId = startAndApprove(publishPurchase(PURCHASING, null));
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(Stub.orderCount()).isEqualTo(1);
        JsonNode effect = run.path("effects").get(0);
        assertThat(effect.path("state").asString()).isEqualTo("succeeded");
        assertThat(effect.path("attempts").asInt()).isEqualTo(1);
        assertThat(effect.path("remoteReference").asString()).startsWith("PO-");
        assertThat(run.path("output").path("procurementId").asString()).isEqualTo(effect.path("remoteReference").asString());
        // The idempotency header is the effect key.
        assertThat(Stub.postHeaders().get(0).get("idempotency-key")).hasSize(64);
        // A viewer sees the run but neither data nor effects.
        JsonNode asViewer = api("vic.viewer").get("/workspaces/{ws}/runs/{id}", PURCHASING, runId).body();
        assertThat(asViewer.path("input").isNull()).isTrue();
        assertThat(asViewer.has("effects")).isFalse();
    }

    @Test
    void tEff02_twoServerErrorsThenSuccessThreeAttemptsOneOrder() throws Exception {
        Stub.fault(Stub.Mode.STATUS500, 2, 0);
        UUID runId = startAndApprove(publishPurchase(PURCHASING, null));
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, runId)).isEqualTo("completed");
        assertThat(Stub.postCount()).isEqualTo(3);
        assertThat(Stub.orderCount()).isEqualTo(1);
        assertThat(effect(runId).path("attempts").asInt()).isEqualTo(3);
        JsonNode events = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, runId).body();
        assertThat(events.path("items").findValues("type").stream().map(JsonNode::asString)
                .filter("EFFECT_FAILED"::equals).count()).isEqualTo(2);
    }

    @Test
    void tEff03_commitThenTimeoutOnIdempotentRetriesWithSameKeyOneOrder() throws Exception {
        Stub.fault(Stub.Mode.COMMIT_THEN_TIMEOUT, 1, 6000);
        UUID runId = startAndApprove(publishPurchase(PURCHASING, null));
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, runId)).isEqualTo("completed");
        assertThat(Stub.postCount()).isEqualTo(2);
        assertThat(Stub.orderCount()).isEqualTo(1);
        List<Map<String, String>> headers = Stub.postHeaders();
        assertThat(headers.get(0).get("idempotency-key")).isEqualTo(headers.get(1).get("idempotency-key"));
    }

    @Test
    void tEff04_06_unknownOnNonIdempotentThenReconcileAppliedWithoutCallingAgain() throws Exception {
        UUID workflowId = nonIdempotentWorkflow();
        Stub.requireIdempotencyKey(false);
        Stub.fault(Stub.Mode.COMMIT_THEN_TIMEOUT, 1, 6000);
        UUID runId = startAndApprove(workflowId);
        awaitLifecycle(PURCHASING, runId, "needsAttention", "completed");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).isEqualTo("needsAttention");
        assertThat(run.path("attention").path("reason").asString()).isEqualTo("EFFECT_UNKNOWN");
        assertThat(run.path("allowedActions").path("retry").path("reasonCode").asString()).isEqualTo("EFFECT_NOT_RECONCILED");
        Thread.sleep(500);
        assertThat(Stub.postCount()).as("no automatic retry").isEqualTo(1);
        assertThat(notifications(PURCHASING, runId, "runAttention")).isEqualTo(1);
        Response refused = retry(runId);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("EFFECT_NOT_RECONCILED");

        String effectId = run.path("attention").path("effectId").asString();
        Response wrongResult = reconcile(runId, effectId, "confirmedApplied", Map.of("other", "x"));
        assertThat(wrongResult.status()).isEqualTo(422);
        Response reconciled = reconcile(runId, effectId, "confirmedApplied", Map.of("procurementId", "PO-777"));
        assertThat(reconciled.status()).as(reconciled.toString()).isEqualTo(200);
        assertThat(reconciled.body().path("state").asString()).isEqualTo("succeeded");
        awaitLifecycle(PURCHASING, runId, "completed");
        JsonNode done = run(PURCHASING, runId);
        assertThat(done.path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(done.path("output").path("procurementId").asString()).isEqualTo("PO-777");
        assertThat(Stub.postCount()).as("stub not called again").isEqualTo(1);
    }

    @Test
    void tEff07_reconcileNotAppliedThenRetryCreatesOneNewOrder() throws Exception {
        UUID workflowId = nonIdempotentWorkflow();
        Stub.requireIdempotencyKey(false);
        Stub.fault(Stub.Mode.COMMIT_THEN_TIMEOUT, 1, 6000);
        UUID runId = startAndApprove(workflowId);
        awaitLifecycle(PURCHASING, runId, "needsAttention");
        String effectId = run(PURCHASING, runId).path("attention").path("effectId").asString();
        Response reconciled = reconcile(runId, effectId, "confirmedNotApplied", null);
        assertThat(reconciled.status()).as(reconciled.toString()).isEqualTo(200);
        assertThat(reconciled.body().path("state").asString()).isEqualTo("notApplied");
        assertThat(lifecycle(PURCHASING, runId)).isEqualTo("needsAttention");
        Response retried = retry(runId);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        awaitLifecycle(PURCHASING, runId, "completed");
        assertThat(Stub.postCount()).isEqualTo(2);
    }

    @Test
    void tEff05_definitiveClientErrorIsRejectedAfterOneAttempt() throws Exception {
        Stub.fault(Stub.Mode.STATUS400, 1, 0);
        UUID runId = startAndApprove(publishPurchase(PURCHASING, null));
        awaitLifecycle(PURCHASING, runId, "needsAttention", "completed");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("attention").path("reason").asString()).isEqualTo("EFFECT_REJECTED");
        assertThat(Stub.postCount()).isEqualTo(1);
        assertThat(run.path("effects").get(0).path("state").asString()).isEqualTo("permanentFailure");
        // Operator fixes the cause and retries: the run completes.
        Response retried = retry(runId);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        awaitLifecycle(PURCHASING, runId, "completed");
    }

    @Test
    void tEff08_bindingDisabledBeforeDispatchThenReenabledAndRetried() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, AMOUNT).path("id").asString());
        jdbc.sql("UPDATE connection_binding SET enabled = false WHERE workspace_id = :ws AND connection_key = 'procurement'")
                .param("ws", PURCHASING).update();
        approve("mani.manager", PURCHASING, runId, "manager");
        awaitLifecycle(PURCHASING, runId, "needsAttention");
        assertThat(run(PURCHASING, runId).path("attention").path("reason").asString())
                .isEqualTo("CONNECTION_UNAVAILABLE");
        assertThat(Stub.postCount()).isZero();
        bindStub(PURCHASING);
        assertThat(retry(runId).status()).isEqualTo(200);
        awaitLifecycle(PURCHASING, runId, "completed");
        assertThat(Stub.orderCount()).isEqualTo(1);
    }

    @Test
    void tEff09_successWithoutIdOnIdempotentIsUnknownNeverPlainFailure() throws Exception {
        Stub.fault(Stub.Mode.INVALID_BODY, 1, 0);
        UUID runId = startAndApprove(publishPurchase(PURCHASING, null));
        awaitLifecycle(PURCHASING, runId, "needsAttention", "completed");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("attention").path("reason").asString()).isEqualTo("EFFECT_UNKNOWN");
        assertThat(run.path("effects").get(0).path("state").asString()).isEqualTo("unknown");
    }

    @Test
    void tEff10_declined429OnNonIdempotentIsRetriedAutomatically() throws Exception {
        UUID workflowId = nonIdempotentWorkflow();
        Stub.requireIdempotencyKey(false);
        Stub.fault(Stub.Mode.STATUS429, 1, 0);
        UUID runId = startAndApprove(workflowId);
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, runId)).isEqualTo("completed");
        assertThat(Stub.orderCount()).isEqualTo(1);
        assertThat(Stub.postCount()).isEqualTo(2);
    }

    @Test
    void cancelReportsCompletedEffectsNotReversed() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, AMOUNT).path("id").asString());
        JsonNode run = run(PURCHASING, runId);
        Response denied = api("rhea.requester").post(Map.of("reason", "No longer needed", "expectedVersion",
                run.path("version").asInt()), UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel",
                PURCHASING, runId);
        assertThat(denied.status()).isEqualTo(403);
        Response cancelled = api("olga.operator").post(Map.of("reason", "No longer needed", "expectedVersion",
                run.path("version").asInt()), UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel",
                PURCHASING, runId);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(cancelled.body().path("lifecycle").asString()).isEqualTo("cancelled");
        assertThat(cancelled.body().path("tasks").get(0).path("status").asString()).isEqualTo("cancelled");
        Response again = api("olga.operator").post(Map.of("reason", "No longer needed", "expectedVersion",
                cancelled.body().path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, runId);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("RUN_NOT_ACTIVE");
    }
}
