package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.MessageSweeper;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** EX04 suspend and resume (docs/build/S37) on PostgreSQL and Flowable through the public API. */
class RunSuspensionTest extends RuntimeTestBase {

    @Autowired
    private RuntimeService engine;

    @Autowired
    private MessageSweeper sweeper;

    private UUID startPurchase() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        return UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
    }

    private Response suspend(String user, UUID run, String reason) throws Exception {
        return suspend(user, run, reason, run(PURCHASING, run).path("version").asInt(), UUID.randomUUID().toString());
    }

    private Response suspend(String user, UUID run, String reason, int version, String idempotencyKey) throws Exception {
        return api(user).post(Map.of("reason", reason, "expectedVersion", version), idempotencyKey,
                "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, run);
    }

    private Response resume(String user, UUID run, String reason) throws Exception {
        return api(user).post(Map.of("reason", reason, "expectedVersion", run(PURCHASING, run).path("version").asInt()),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/resume", PURCHASING, run);
    }

    private boolean engineSuspended(UUID run) {
        return engine.createProcessInstanceQuery().processInstanceId(processInstance(PURCHASING, run)).singleResult()
                .isSuspended();
    }

    private long suspensionRows(UUID run) {
        return jdbc.sql("SELECT count(*) FROM run_suspension WHERE run_id = :id").param("id", run)
                .query(Long.class).single();
    }

    @Test
    void suspendedApprovalCannotBeClaimedOrDecidedUntilResumed() throws Exception {
        UUID run = startPurchase();
        JsonNode open = openTask(PURCHASING, run, "manager");
        assertThat(open).isNotNull();
        String taskId = open.path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);

        Response suspended = suspend("olga.operator", run, "Policy review in progress");
        assertThat(suspended.status()).as(suspended.toString()).isEqualTo(200);
        JsonNode detail = suspended.body();
        assertThat(detail.path("lifecycle").asString()).isEqualTo("waiting");
        assertThat(detail.path("suspension").path("reason").asString()).isEqualTo("Policy review in progress");
        assertThat(detail.path("allowedActions").path("suspend").path("allowed").asBoolean()).isFalse();
        assertThat(detail.path("allowedActions").path("resume").path("allowed").asBoolean()).isTrue();
        assertThat(engineSuspended(run)).isTrue();
        assertThat(suspensionRows(run)).isEqualTo(1);

        // The requester sees that the request is paused but not the operator's reason.
        JsonNode requesterView = api("rhea.requester").get("/workspaces/{ws}/runs/{id}", PURCHASING, run).body();
        assertThat(requesterView.path("suspension").path("since").isMissingNode()).isFalse();
        assertThat(requesterView.path("suspension").path("reason").isMissingNode()).isTrue();
        assertThat(requesterView.path("allowedActions").path("resume").path("allowed").asBoolean()).isFalse();

        Response blocked = decide("mani.manager", PURCHASING, taskId, "approve", null);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.body().path("code").asString()).isEqualTo("RUN_SUSPENDED");
        JsonNode task = api("mani.manager").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
        assertThat(task.path("allowedActions").path("decide").path("reasonCode").asString()).isEqualTo("RUN_SUSPENDED");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");

        Response resumed = resume("olga.operator", run, "Review finished");
        assertThat(resumed.status()).as(resumed.toString()).isEqualTo(200);
        assertThat(resumed.body().path("suspension").isMissingNode()).isTrue();
        assertThat(engineSuspended(run)).isFalse();
        assertThat(suspensionRows(run)).isZero();

        Response decided = decide("mani.manager", PURCHASING, taskId, "approve", null);
        assertThat(decided.status()).as(decided.toString()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT type FROM run_event WHERE run_id = :id AND type IN ('RUN_SUSPENDED', 'RUN_RESUMED') ORDER BY seq")
                .param("id", run).query(String.class).list()).containsExactly("RUN_SUSPENDED", "RUN_RESUMED");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :id AND action IN ('run.suspended', 'run.resumed')")
                .param("id", run.toString()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void ruleViolationsAreRejectedAndCancelRemovesTheSuspension() throws Exception {
        UUID run = startPurchase();
        int version = run(PURCHASING, run).path("version").asInt();

        assertThat(suspend("rhea.requester", run, "I want to pause my own request").status()).isEqualTo(403);
        assertThat(resume("olga.operator", run, "Nothing to resume").body().path("code").asString())
                .isEqualTo("RUN_NOT_SUSPENDED");
        assertThat(suspend("olga.operator", run, "Stale view", version + 5, UUID.randomUUID().toString()).status())
                .isEqualTo(409);

        String idempotencyKey = UUID.randomUUID().toString();
        Response first = suspend("olga.operator", run, "Audit hold", version, idempotencyKey);
        assertThat(first.status()).isEqualTo(200);
        Response replay = suspend("olga.operator", run, "Audit hold", version, idempotencyKey);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(suspensionRows(run)).isEqualTo(1);

        Response again = suspend("olga.operator", run, "Second attempt");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().path("code").asString()).isEqualTo("RUN_SUSPENDED");
        assertThat(api("rhea.requester").post(
                Map.of("reason", "Self resume", "expectedVersion", 1), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/resume", PURCHASING, run).status()).isEqualTo(403);

        // A suspended request can still be cancelled by an operator; the suspension does not outlive it.
        int current = run(PURCHASING, run).path("version").asInt();
        Response cancelled = api("olga.operator").post(Map.of("reason", "No longer needed", "expectedVersion", current),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("cancelled");
        assertThat(suspensionRows(run)).isZero();
        Response ended = suspend("olga.operator", run, "Too late");
        assertThat(ended.body().path("code").asString()).isEqualTo("RUN_NOT_ACTIVE");
    }

    @Test
    void messageSentWhileSuspendedIsBufferedAndDeliveredAfterResume() throws Exception {
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/order-payment-wait.json")));
        UUID workflow = publishPurchase(PURCHASING, d -> example.properties().forEach(e -> {
            if (!e.getKey().equals("workflowKey") && !e.getKey().equals("name")) {
                d.set(e.getKey(), e.getValue());
            }
        }));
        String orderNumber = "SO-" + UUID.randomUUID().toString().substring(0, 8);
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow,
                "input", Map.of("orderNumber", orderNumber, "customerName", "Acme Ltd")), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID run = UUID.fromString(started.body().path("id").asString());
        // Wait until the request is parked at the message wait and its version has settled.
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(300)).until(() -> {
            JsonNode steps = run(PURCHASING, run).path("currentSteps");
            return steps.size() == 1 && !steps.get(0).path("messageName").asString("").isEmpty();
        });

        Response held = suspend("olga.operator", run, "Hold while the order is checked");
        assertThat(held.status()).as(held.toString()).isEqualTo(200);
        Response sent = api("olga.operator").post(Map.of("name", "payment-received", "correlationKey", orderNumber,
                "payload", Map.of("paymentReference", "PAY-77", "amountPaid", 125.50)), UUID.randomUUID().toString(),
                "/workspaces/{ws}/messages", PURCHASING);
        assertThat(sent.status()).as(sent.toString()).isIn(200, 201, 202);
        assertThat(sent.body().path("status").asString()).isEqualTo("buffered");
        sweeper.run();
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");

        assertThat(resume("olga.operator", run, "Order verified").status()).isEqualTo(200);
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(300)).until(() -> {
            sweeper.run();
            return "completed".equals(lifecycle(PURCHASING, run));
        });
    }
}
