package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.integration.KafkaDispatch;
import com.orqix.runtime.internal.AdmissionControl;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * CG-P05 (docs/build/S150): admission limits per workspace for starts and inbound messages, the Platform API that
 * sets them, and the Kafka/package-trigger port that turns a refusal into "try again later". Limits are set relative
 * to what the workspace already did in the last minute, and every override is removed afterwards.
 */
class AdmissionControlTest extends RuntimeTestBase {

    @Autowired
    AdmissionControl admission;

    @Autowired
    KafkaDispatch dispatch;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    Clock clock;

    @BeforeEach
    @AfterEach
    void clearOverrides() {
        jdbc.sql("DELETE FROM workspace_admission_limit WHERE workspace_id IN (:ws)")
                .param("ws", java.util.List.of(PURCHASING, SANDBOX)).update();
        admission.reset();
    }

    @Test
    void platformAdministratorsSetAndClearLimits() throws Exception {
        assertThat(api("wade.admin").get("/admin/admission").status()).isEqualTo(403);
        assertThat(api("wade.admin").put(Map.of("startsPerMinute", 5), null, "/admin/workspaces/{ws}/admission",
                SANDBOX).status()).isEqualTo(403);

        Response overview = api("pat.platform").get("/admin/admission");
        assertThat(overview.status()).as(overview.toString()).isEqualTo(200);
        assertThat(overview.body().path("enabled").asBoolean()).isTrue();
        assertThat(overview.body().path("defaults").path("startsPerMinute").asInt()).isZero();

        Response initial = api("pat.platform").get("/admin/workspaces/{ws}/admission", SANDBOX);
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.body().path("override").isNull()).isTrue();

        Response set = api("pat.platform").put(Map.of("startsPerMinute", 120), null, "/admin/workspaces/{ws}/admission",
                SANDBOX);
        assertThat(set.status()).as(set.toString()).isEqualTo(200);
        assertThat(set.body().path("effective").path("startsPerMinute").asInt()).isEqualTo(120);
        assertThat(set.body().path("effective").path("messagesPerMinute").asInt()).as("default kept").isZero();
        assertThat(set.body().path("override").path("revision").asInt()).isEqualTo(1);
        Response again = api("pat.platform").put(Map.of("startsPerMinute", 60, "messagesPerMinute", 30), null,
                "/admin/workspaces/{ws}/admission", SANDBOX);
        assertThat(again.body().path("override").path("revision").asInt()).isEqualTo(2);
        assertThat(api("pat.platform").get("/admin/admission").body().path("overrides").findValues("workspaceId")
                .toString()).contains(SANDBOX.toString());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit_event WHERE action = 'platform.admission-updated' AND resource_id = :ws
                """).param("ws", SANDBOX.toString()).query(Integer.class).single()).isGreaterThanOrEqualTo(2);

        assertThat(api("pat.platform").put(Map.of("startsPerMinute", -1), null, "/admin/workspaces/{ws}/admission",
                SANDBOX).status()).isEqualTo(400);
        assertThat(api("pat.platform").put(Map.of("startsPerMinute", 1), null, "/admin/workspaces/{ws}/admission",
                UUID.randomUUID()).status()).isEqualTo(404);

        Map<String, Object> clear = new HashMap<>();
        clear.put("startsPerMinute", null);
        clear.put("messagesPerMinute", null);
        Response cleared = api("pat.platform").put(clear, null, "/admin/workspaces/{ws}/admission", SANDBOX);
        assertThat(cleared.status()).as(cleared.toString()).isEqualTo(200);
        assertThat(cleared.body().path("override").isNull()).isTrue();
    }

    @Test
    void startsAboveTheLimitAre429WithRetryAfterAndAudited() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        setLimits(PURCHASING, startsInWindow(PURCHASING) + 2, null);

        String firstKey = UUID.randomUUID().toString();
        Response first = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input(BigDecimal.TEN)),
                firstKey, "/workspaces/{ws}/runs", PURCHASING);
        assertThat(first.status()).as(first.toString()).isEqualTo(201);
        start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN);

        // Audit rows of earlier tests (other classes share the database) are not this test's.
        java.sql.Timestamp auditFrom = java.sql.Timestamp.from(clock.instant().minusMillis(1));
        Response refused = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input(BigDecimal.TEN)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(429);
        assertThat(refused.code()).isEqualTo("RATE_LIMITED");
        assertThat(Integer.parseInt(refused.header("Retry-After"))).isBetween(1, 60);

        // A replay of an admitted start is answered, not refused.
        Response replay = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input(BigDecimal.TEN)),
                firstKey, "/workspaces/{ws}/runs", PURCHASING);
        assertThat(replay.status()).as(replay.toString()).isIn(200, 201);
        assertThat(replay.body().path("id").asString()).isEqualTo(first.body().path("id").asString());

        // Audited once per minute, not once per refusal.
        assertThat(api("rhea.requester").post(Map.of("workflowId", workflow, "input", input(BigDecimal.TEN)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING).status()).isEqualTo(429);
        JsonNode audits = jdbc.sql("""
                SELECT json_agg(metadata) FROM audit_event
                WHERE action = 'admission.limit-exceeded' AND workspace_id = :ws AND occurred_at > :since
                """).param("ws", PURCHASING).param("since", auditFrom)
                .query((rs, n) -> json.parse(rs.getString(1))).single();
        assertThat(audits.size()).isEqualTo(1);
        assertThat(audits.get(0).path("kind").asString()).isEqualTo("starts");

        // Kafka and package triggers share this port: the refusal is "later", never a dead letter.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> dispatch.start(PURCHASING,
                subject("rhea.requester"), workflow, "kafka-" + UUID.randomUUID(), null,
                (tools.jackson.databind.node.ObjectNode) json.toTree(input(BigDecimal.TEN)))))
                .isInstanceOf(KafkaDispatch.Retry.class).hasMessage("rate_limited");

        // Other workspaces are not affected.
        UUID sandboxWorkflow = publishPurchase(SANDBOX, null);
        assertThat(api("dana.designer").post(Map.of("workflowId", sandboxWorkflow, "input", input(BigDecimal.TEN)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", SANDBOX).status()).isEqualTo(201);
    }

    @Test
    void messagesAboveTheLimitAre429() throws Exception {
        setLimits(PURCHASING, null, messagesInWindow(PURCHASING) + 1);
        Response accepted = api("olga.operator").post(Map.of("name", "payment-received", "correlationKey",
                "ADM-" + UUID.randomUUID()), UUID.randomUUID().toString(), "/workspaces/{ws}/messages", PURCHASING);
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(202);
        Response refused = api("olga.operator").post(Map.of("name", "payment-received", "correlationKey",
                "ADM-" + UUID.randomUUID()), UUID.randomUUID().toString(), "/workspaces/{ws}/messages", PURCHASING);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(429);
        assertThat(refused.code()).isEqualTo("RATE_LIMITED");
        assertThat(refused.header("Retry-After")).isNotBlank();
    }

    @Test
    void zeroOrDisabledMeansNoLimit() throws Exception {
        setLimits(PURCHASING, 0, 0);
        UUID workflow = publishPurchase(PURCHASING, null);
        start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN);
        assertThat(admission.limits(PURCHASING).startsPerMinute()).isZero();
    }

    private void setLimits(UUID workspaceId, Integer starts, Integer messages) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("startsPerMinute", starts);
        body.put("messagesPerMinute", messages);
        Response set = api("pat.platform").put(body, null, "/admin/workspaces/{ws}/admission", workspaceId);
        assertThat(set.status()).as(set.toString()).isEqualTo(200);
    }

    private int startsInWindow(UUID workspaceId) {
        return jdbc.sql("SELECT count(*) FROM workflow_run WHERE workspace_id = :ws AND created_at > :since")
                .param("ws", workspaceId).param("since", java.sql.Timestamp.from(clock.instant().minusSeconds(60)))
                .query(Integer.class).single();
    }

    private int messagesInWindow(UUID workspaceId) {
        return jdbc.sql("SELECT count(*) FROM inbound_message WHERE workspace_id = :ws AND received_at > :since")
                .param("ws", workspaceId).param("since", java.sql.Timestamp.from(clock.instant().minusSeconds(60)))
                .query(Integer.class).single();
    }

    private UUID subject(String user) {
        // Subjects exist once the person has called the API (the test above did).
        return jdbc.sql("SELECT id FROM subject WHERE external_subject = :u").param("u", "kc-" + user)
                .query(UUID.class).single();
    }
}
