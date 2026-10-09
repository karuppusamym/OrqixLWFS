package com.orqix.resilience;

import static com.orqix.resilience.Flows.PURCHASING;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-RES-01 (in-JVM): start runs, stop the application gracefully, start it again against the same database. Waiting
 * tasks, their inbox entries, claims and the pinned release survive; the decisions complete on the restarted
 * instance, still on the release the runs started on (S5 section 12: "Application restart while a task waits").
 * The same procedure against the Docker image is {@code tools/resilience/resilience.py restart}.
 */
@Tag("resilience")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestartTest {

    private Node node;

    @BeforeAll
    void startFirstInstance() {
        Stub.reset();
        node = Node.start(Nodes.NodeA.class);
        Flows.bindStub(node, PURCHASING, Stub.baseUrl());
    }

    @AfterAll
    void stopInstance() {
        if (node != null && node.running()) {
            node.stop();
        }
    }

    @Test
    void tRes01_waitingTaskAndPinnedReleaseSurviveARestartAndTheDecisionCompletes() throws Exception {
        UUID workflowId = Flows.publishPurchase(node, PURCHASING, 5, null);
        // run A: 6,000 -> needs finance after the manager; unclaimed. run B: 1,800 -> claimed by mani before the restart.
        UUID runA = Flows.start(node, "rhea.requester", PURCHASING, workflowId, new BigDecimal("6000"));
        UUID runB = Flows.start(node, "rhea.requester", PURCHASING, workflowId, Flows.SMALL);
        JsonNode taskA = Flows.openTask(node, PURCHASING, runA, "manager");
        JsonNode taskB = Flows.openTask(node, PURCHASING, runB, "manager");
        String taskBId = taskB.path("id").asString();
        assertThat(Flows.claim(node, "mani.manager", PURCHASING, taskBId).status()).isEqualTo(200);
        JsonNode taskBClaimed = node.as("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskBId).body();
        JsonNode releaseBefore = Flows.run(node, PURCHASING, runA).path("release");
        List<String> claimableBefore = runIds(node.as("max.manager")
                .get("/workspaces/{ws}/tasks?view=claimable&limit=100", PURCHASING).body());
        List<String> assignedBefore = runIds(node.as("mani.manager")
                .get("/workspaces/{ws}/tasks?view=assigned&limit=100", PURCHASING).body());
        assertThat(claimableBefore).contains(runA.toString());
        assertThat(assignedBefore).contains(runB.toString());
        assertThat(Stub.postCount()).isZero();

        // --- stop (graceful, like SIGTERM) and start again against the same database
        assertThat(ResilienceDatabase.appConnections()).isPositive();
        long shutdownMs = node.stop();
        assertThat(node.running()).isFalse();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(ResilienceDatabase.appConnections()).as("pool of the stopped instance").isZero());
        long started = System.nanoTime();
        node = Node.start(Nodes.NodeA.class);
        long startupMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("T-RES-01: graceful shutdown %d ms, restart to ready %d ms%n", shutdownMs, startupMs);

        // --- everything is intact
        assertThat(Flows.lifecycle(node, PURCHASING, runA)).isEqualTo("waiting");
        assertThat(Flows.lifecycle(node, PURCHASING, runB)).isEqualTo("waiting");
        JsonNode taskAAfter = Flows.openTask(node, PURCHASING, runA, "manager");
        assertThat(taskAAfter.path("id").asString()).isEqualTo(taskA.path("id").asString());
        assertThat(taskAAfter.path("version").asInt()).isEqualTo(taskA.path("version").asInt());
        JsonNode taskBAfter = node.as("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskBId).body();
        assertThat(taskBAfter.path("assignee")).isEqualTo(taskBClaimed.path("assignee"));
        assertThat(taskBAfter.path("version").asInt()).isEqualTo(taskBClaimed.path("version").asInt());
        assertThat(runIds(node.as("max.manager").get("/workspaces/{ws}/tasks?view=claimable&limit=100", PURCHASING)
                .body())).contains(runA.toString());
        assertThat(runIds(node.as("mani.manager").get("/workspaces/{ws}/tasks?view=assigned&limit=100", PURCHASING)
                .body())).contains(runB.toString());
        assertThat(Flows.run(node, PURCHASING, runA).path("release")).isEqualTo(releaseBefore);
        assertThat(Stub.postCount()).as("no effect without a decision").isZero();

        // --- a newer release is published on the restarted instance; waiting runs stay on release 1
        publishSecondRelease(workflowId);
        assertThat(Flows.run(node, PURCHASING, runA).path("release").path("releaseNumber").asInt()).isEqualTo(1);

        // --- the decisions complete on the restarted application
        Resp decidedB = Flows.decide(node, "mani.manager", PURCHASING, taskBId, "approve");
        assertThat(decidedB.status()).as(decidedB.toString()).isEqualTo(201);
        Flows.approve(node, "max.manager", PURCHASING, runA, "manager");
        Flows.awaitLifecycle(node, PURCHASING, runB, Duration.ofSeconds(60), "completed", "needsAttention");
        assertThat(Flows.run(node, PURCHASING, runB).path("outcome").asString()).isEqualTo("Fulfilled");
        // Release 1 routes 6,000 to finance although release 2 (threshold 10,000) is active.
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> Flows.openTask(node, PURCHASING, runA, "finance") != null);
        Flows.approve(node, "fin.finance", PURCHASING, runA, "finance");
        Flows.awaitLifecycle(node, PURCHASING, runA, Duration.ofSeconds(60), "completed", "needsAttention");
        JsonNode doneA = Flows.run(node, PURCHASING, runA);
        assertThat(doneA.path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(doneA.path("release").path("releaseNumber").asInt()).isEqualTo(1);
        assertThat(Stub.orderCount()).as("one order per run").isEqualTo(2);
        assertThat(Stub.postCount()).isEqualTo(2);
        for (UUID run : List.of(runA, runB)) {
            assertThat(node.jdbc().sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'RUN_COMPLETED'")
                    .param("r", run).query(Integer.class).single()).isEqualTo(1);
        }
    }

    private void publishSecondRelease(UUID workflowId) throws Exception {
        Client designer = node.as("dana.designer");
        ObjectNode definition = (ObjectNode) designer.get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING,
                workflowId).body().path("definition");
        ((ObjectNode) definition.path("decisions").get(0).path("rows").get(0).path("when")).put("value", 10000);
        Resp saved = designer.put(Map.of("definition", definition), "\"r2\"", "/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, workflowId);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Resp published = node.as("paul.publisher").post(Map.of("draftRevision", 3, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflowId);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        assertThat(published.body().path("releaseNumber").asInt()).isEqualTo(2);
    }

    private static List<String> runIds(JsonNode page) {
        return page.path("items").findValues("runId").stream().map(JsonNode::asString).toList();
    }
}
