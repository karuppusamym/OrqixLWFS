package com.orqix.hardening;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.Fixtures;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-EFF-06, hardened: {@code EffectsTest.tEff04_06} shows that {@code confirmedApplied} completes the run without a
 * further stub call. This class proves the stronger statement of S5 section 7.2 ("the delegate finds {@code
 * succeeded} and continues without calling the remote system"): the binding is disabled before the reconcile, so any
 * dispatch attempt would end in {@code CONNECTION_UNAVAILABLE}; the run still completes, from the stored result.
 * It also covers the audit and event trail of the reconcile and its idempotent replay (FR14 coverage map).
 */
class ReconcileResumeTest extends RuntimeTestBase {

    private static boolean nonIdempotentRegistered;

    @Test
    void tEff06_confirmedAppliedResumesFromTheJournalWithoutDispatchAuditedOnce() throws Exception {
        UUID workflowId = nonIdempotentWorkflow();
        Stub.requireIdempotencyKey(false);
        Stub.fault(Stub.Mode.COMMIT_THEN_TIMEOUT, 1, 6000);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, new BigDecimal("1800")).path("id")
                .asString());
        approve("mani.manager", PURCHASING, runId, "manager");
        awaitLifecycle(PURCHASING, runId, "needsAttention", "completed");
        JsonNode attention = run(PURCHASING, runId);
        assertThat(attention.path("attention").path("reason").asString()).isEqualTo("EFFECT_UNKNOWN");
        String effectId = attention.path("attention").path("effectId").asString();
        assertThat(Stub.postCount()).isEqualTo(1);
        assertThat(Stub.orderCount()).as("the remote system did create the order").isEqualTo(1);

        // Any new dispatch would now fail before the call: the binding is disabled.
        jdbc.sql("UPDATE connection_binding SET enabled = false WHERE workspace_id = :ws AND connection_key = 'procurement'")
                .param("ws", PURCHASING).update();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = reconcileBody();
        Response reconciled = reconcile(runId, effectId, key, body, "olga.operator");
        assertThat(reconciled.status()).as(reconciled.toString()).isEqualTo(200);
        assertThat(reconciled.body().path("state").asString()).isEqualTo("succeeded");
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");

        JsonNode done = run(PURCHASING, runId);
        assertThat(done.path("lifecycle").asString()).as(done.toString()).isEqualTo("completed");
        assertThat(done.path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(done.path("output").path("procurementId").asString()).isEqualTo("PO-777");
        assertThat(Stub.postCount()).as("no further call to the stub").isEqualTo(1);
        assertThat(Stub.orderCount()).isEqualTo(1);
        JsonNode effect = done.path("effects").get(0);
        assertThat(effect.path("state").asString()).isEqualTo("succeeded");
        assertThat(effect.path("attempts").asInt()).as("no new attempt was recorded").isEqualTo(1);
        assertThat(effect.path("remoteReference").asString()).isEqualTo("PO-777");
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'RUN_COMPLETED'")
                .param("r", runId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'EFFECT_RECONCILED'")
                .param("r", runId).query(Integer.class).single()).isEqualTo(1);

        // Audit: one entry by the operator with resolution and reference, not the evidence note.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit_event
                WHERE workspace_id = :ws AND action = 'effect.reconciled' AND resource_id = :effect
                  AND outcome = 'success' AND metadata->>'resolution' = 'confirmedApplied'
                  AND metadata->>'remoteReference' = 'PO-777'
                """).param("ws", PURCHASING).param("effect", effectId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'effect.reconciled' "
                + "AND resource_id = :effect").param("effect", effectId).query(String.class).single())
                .doesNotContain("Checked the procurement system");

        // The same request again replays the answer; it neither repeats the work nor the audit entry.
        Response replay = reconcile(runId, effectId, key, body, "olga.operator");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        // A new request for the already reconciled effect of a finished run is a conflict; a requester may not
        // reconcile at all.
        Response again = reconcile(runId, effectId, UUID.randomUUID().toString(), body, "olga.operator");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("RUN_NOT_ACTIVE");
        assertThat(reconcile(runId, effectId, UUID.randomUUID().toString(), body, "rhea.requester").status())
                .isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'effect.reconciled' AND resource_id = :e")
                .param("e", effectId).query(Integer.class).single()).isEqualTo(1);
        assertThat(Stub.postCount()).isEqualTo(1);
    }

    private Map<String, Object> reconcileBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", "confirmedApplied");
        body.put("evidence", Map.of("note", "Checked the procurement system by phone", "remoteReference", "PO-777"));
        body.put("result", Map.of("procurementId", "PO-777"));
        return body;
    }

    private Response reconcile(UUID runId, String effectId, String idempotencyKey, Map<String, Object> body,
            String user) throws Exception {
        return api(user).post(body, idempotencyKey, "/workspaces/{ws}/runs/{run}/effects/{effect}/reconcile",
                PURCHASING, runId, effectId);
    }

    /** procurement v2: createOrder classified nonIdempotent (no idempotency header). */
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
}
