package com.orqix.hardening;

import static com.orqix.resilience.Flows.PURCHASING;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.orqix.resilience.Flows;
import com.orqix.resilience.JobExecutionRecorder;
import com.orqix.resilience.Node;
import com.orqix.resilience.Nodes;
import com.orqix.resilience.Resp;
import com.orqix.support.Fixtures;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-RES-05 (in-JVM): the instance that is executing an HTTP job is shut down gracefully (the context close that a
 * SIGTERM triggers: lifecycle callbacks run, the job executor drains) while the call to the procurement stub is in
 * flight. S5 section 11/12: the job finishes or is safely re-run by the other instance, and there is never a
 * duplicate effect. The second instance keeps running throughout, so a re-run would be picked up by it.
 *
 * <p>The same procedure with a real SIGTERM (docker stop) on the Compose stack is
 * {@code tools/resilience/resilience.py shutdown}.
 */
@Tag("resilience")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GracefulShutdownTest {

    private static final int CALL_DELAY_MS = 8000;
    private static boolean nonIdempotentRegistered;

    private Node a;
    private Node b;

    @BeforeAll
    void startBothInstances() {
        a = Node.start(Nodes.NodeA.class);
        b = Node.start(Nodes.NodeB.class);
        Flows.bindStub(a, PURCHASING, Stub.baseUrl());
    }

    @BeforeEach
    void resetAndRestoreInstances() {
        Stub.reset();
        JobExecutionRecorder.clear();
        if (!a.running()) {
            a = Node.start(Nodes.NodeA.class);
        }
        if (!b.running()) {
            b = Node.start(Nodes.NodeB.class);
        }
    }

    @AfterAll
    void stopInstances() {
        if (b != null && b.running()) {
            b.stop();
        }
        if (a != null && a.running()) {
            a.stop();
        }
    }

    @Test
    void tRes05_idempotentCallInFlightInstanceStoppedGracefullyOneOrderRunCompletes() throws Exception {
        UUID workflowId = Flows.publishPurchase(a, PURCHASING, 20, null);
        Stub.fault(Stub.Mode.DELAY, 1, CALL_DELAY_MS);
        UUID runId = Flows.start(a, "rhea.requester", PURCHASING, workflowId, Flows.SMALL);
        Flows.approve(a, "mani.manager", PURCHASING, runId, "manager");

        Node executing = awaitCallInFlight(runId);
        Node survivor = executing == a ? b : a;
        assertThat(Stub.orderCount()).as("the stub committed the order while the call is in flight").isEqualTo(1);

        long shutdownMs = executing.stop();
        String lifecycleAtShutdown = Flows.lifecycle(survivor, PURCHASING, runId);
        int callsAtShutdown = Stub.postCount();
        Flows.awaitLifecycle(survivor, PURCHASING, runId, Duration.ofSeconds(120), "completed", "needsAttention");

        JsonNode run = Flows.run(survivor, PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).as(run.toString()).isEqualTo("completed");
        assertThat(run.path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(Stub.orderCount()).as("exactly one order").isEqualTo(1);
        assertThat(Stub.postCount()).as("one call, or one resend with the same key").isBetween(1, 2);
        List<Map<String, String>> headers = Stub.postHeaders();
        assertThat(headers.stream().map(h -> h.get("idempotency-key")).distinct().count())
                .as("every resend carries the original effect key").isEqualTo(1);
        List<Map<String, Object>> journal = survivor.jdbc().sql(
                "SELECT state, attempts FROM effect_journal WHERE workspace_id = :ws AND run_id = :r")
                .param("ws", PURCHASING).param("r", runId).query().listOfRows();
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0)).containsEntry("state", "succeeded").containsEntry("attempts", Stub.postCount());
        assertEventCount(survivor, runId, "EFFECT_SUCCEEDED", 1);
        assertEventCount(survivor, runId, "RUN_COMPLETED", 1);
        assertNoJobsLeft(survivor);
        // A drained job commits like any other: its lock is not taken away while it runs, so no attempt fails, no
        // retry is consumed and the timeline shows no failure for a call that succeeded (S5 section 11: drain).
        assertThat(JobExecutionRecorder.failedAttempts()).as("failed job attempts during a graceful shutdown")
                .isEmpty();
        assertEventCount(survivor, runId, "EFFECT_FAILED", 0);
        assertThat(Stub.postCount()).as("drained: the call was made once and never repeated").isEqualTo(1);
        System.out.printf("T-RES-05 (idempotent): %s stopped in %d ms with the call in flight (stub delay %d ms); run "
                + "was '%s' with %d stub call(s) at shutdown; stub calls %d, orders %d; journal attempts %s; "
                + "failed job attempts %d; run events %s; job executions %s%n", executing.name(), shutdownMs,
                CALL_DELAY_MS, lifecycleAtShutdown, callsAtShutdown, Stub.postCount(), Stub.orderCount(),
                journal.get(0).get("attempts"), JobExecutionRecorder.failedAttempts().size(), eventTypes(survivor, runId),
                JobExecutionRecorder.executions().stream()
                        .map(e -> e.lockOwner() + (e.success() ? " ok" : " FAILED")).toList());
    }

    @Test
    void tRes05_nonIdempotentCallInFlightNeverRepeatedAndNeverShownAsPlainFailure() throws Exception {
        UUID workflowId = nonIdempotentWorkflow();
        Stub.requireIdempotencyKey(false);
        Stub.fault(Stub.Mode.DELAY, 1, CALL_DELAY_MS);
        UUID runId = Flows.start(b, "rhea.requester", PURCHASING, workflowId, Flows.SMALL);
        Flows.approve(b, "max.manager", PURCHASING, runId, "manager");

        Node executing = awaitCallInFlight(runId);
        Node survivor = executing == a ? b : a;
        long shutdownMs = executing.stop();
        Flows.awaitLifecycle(survivor, PURCHASING, runId, Duration.ofSeconds(120), "completed", "needsAttention");

        JsonNode run = Flows.run(survivor, PURCHASING, runId);
        String lifecycle = run.path("lifecycle").asString();
        assertThat(Stub.orderCount()).as("never a second order").isEqualTo(1);
        assertThat(Stub.postCount()).as("a non-idempotent call is never resent blindly").isEqualTo(1);
        if ("needsAttention".equals(lifecycle)) {
            // The drain was cut short: the outcome is unknown and an operator has to reconcile it.
            assertThat(run.path("attention").path("reason").asString()).isEqualTo("EFFECT_UNKNOWN");
            Resp refused = survivor.as("olga.operator").post(Map.of("reason", "Dependency recovered",
                    "expectedVersion", run.path("version").asInt()), UUID.randomUUID().toString(),
                    "/workspaces/{ws}/runs/{id}/retry", PURCHASING, runId);
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.code()).isEqualTo("EFFECT_NOT_RECONCILED");
        } else {
            assertThat(lifecycle).isEqualTo("completed");
            assertThat(run.path("outcome").asString()).isEqualTo("Fulfilled");
        }
        assertEventCount(survivor, runId, "RUN_COMPLETED", "completed".equals(lifecycle) ? 1 : 0);
        assertThat(JobExecutionRecorder.failedAttempts()).as("failed job attempts during a graceful shutdown")
                .isEmpty();
        assertEventCount(survivor, runId, "EFFECT_FAILED", 0);
        System.out.printf("T-RES-05 (nonIdempotent): %s stopped in %d ms with the call in flight; run ended '%s'; "
                + "stub calls %d, orders %d; failed job attempts %d; run events %s%n", executing.name(), shutdownMs,
                lifecycle, Stub.postCount(), Stub.orderCount(), JobExecutionRecorder.failedAttempts().size(),
                eventTypes(survivor, runId));
    }

    /** Waits until the stub holds the call of the run and returns the instance whose lease the journal shows. */
    private Node awaitCallInFlight(UUID runId) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .until(() -> Stub.postCount() >= 1 && leaseOwner(runId) != null);
        String owner = leaseOwner(runId);
        assertThat(owner).isIn(a.lockOwner(), b.lockOwner());
        return owner.equals(a.lockOwner()) ? a : b;
    }

    private String leaseOwner(UUID runId) {
        return a.running()
                ? a.jdbc().sql("SELECT lease_owner FROM effect_journal WHERE workspace_id = :ws AND run_id = :r")
                        .param("ws", PURCHASING).param("r", runId).query(String.class).optional().orElse(null)
                : b.jdbc().sql("SELECT lease_owner FROM effect_journal WHERE workspace_id = :ws AND run_id = :r")
                        .param("ws", PURCHASING).param("r", runId).query(String.class).optional().orElse(null);
    }

    /** procurement v2: createOrder classified nonIdempotent (no idempotency header). */
    private UUID nonIdempotentWorkflow() throws Exception {
        if (!nonIdempotentRegistered) {
            ObjectNode definition = (ObjectNode) Fixtures.procurementConnection();
            definition.put("version", 2);
            ObjectNode createOrder = (ObjectNode) definition.path("operations").path("createOrder");
            createOrder.put("effectClass", "nonIdempotent");
            createOrder.remove("idempotencyHeader");
            Resp registered = a.as("wade.admin").post(Map.of("definition", definition),
                    "/workspaces/{ws}/connections/{key}/versions", PURCHASING, "procurement");
            assertThat(registered.status()).as(registered.toString()).isIn(201, 409);
            nonIdempotentRegistered = true;
        }
        return Flows.publishPurchase(a, PURCHASING, 20,
                d -> ((ObjectNode) d.path("dependencies").path("connections").get(0)).put("definitionVersion", 2));
    }

    private static List<String> eventTypes(Node node, UUID runId) {
        return node.jdbc().sql("SELECT type || coalesce('(' || (data->>'errorCode') || ')', '') FROM run_event "
                + "WHERE run_id = :r ORDER BY seq").param("r", runId).query(String.class).list();
    }

    private static void assertEventCount(Node node, UUID runId, String type, int expected) {
        assertThat(node.jdbc().sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = :t")
                .param("r", runId).param("t", type).query(Integer.class).single()).as(type).isEqualTo(expected);
    }

    private static void assertNoJobsLeft(Node node) {
        for (String table : List.of("act_ru_job", "act_ru_timer_job", "act_ru_deadletter_job", "act_ru_suspended_job")) {
            assertThat(node.jdbc().sql("SELECT count(*) FROM flowable." + table).query(Integer.class).single())
                    .as(table).isZero();
        }
    }
}
