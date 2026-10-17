package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.Fixtures;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;

/**
 * T-RUN-03 (with T-RUN-04 simple merge and T-RUN-05 one notification per outcome): every {@code scenarios} entry of
 * examples/fixtures/purchase-scenarios.json reaches the expected lifecycle, outcome, visited nodes, procurement call
 * count, notifications and outputs, through the public API and the real engine.
 */
class PurchaseScenariosTest extends RuntimeTestBase {

    @Autowired
    HistoryService historyService;

    private static UUID workflowId;

    static Stream<Arguments> scenarios() {
        List<Arguments> arguments = new ArrayList<>();
        for (JsonNode scenario : Fixtures.purchaseScenarios().path("scenarios")) {
            arguments.add(Arguments.of(scenario.path("name").asString(), scenario));
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenario(String name, JsonNode scenario) throws Exception {
        if (workflowId == null) {
            workflowId = publishPurchase(PURCHASING, null);
        }
        boolean initiatorIsManager = scenario.path("initiatorIsManager").asBoolean(false);
        String initiator = initiatorIsManager ? "mani.manager" : "rhea.requester";
        String manager = initiatorIsManager ? "max.manager" : "mani.manager";
        BigDecimal amount = scenario.path("amount").decimalValue();
        JsonNode expect = scenario.path("expect");

        JsonNode started = start(initiator, PURCHASING, workflowId, amount);
        UUID runId = UUID.fromString(started.path("id").asString());
        assertThat(started.path("lifecycle").asString()).isEqualTo("waiting");
        assertThat(started.path("currentSteps").get(0).path("nodeId").asString()).isEqualTo("manager");

        Response failure = null;
        for (JsonNode decision : scenario.path("decisions")) {
            String node = decision.path("node").asString();
            String actor = switch (decision.path("actor").asString()) {
                case "manager" -> manager;
                case "finance" -> "fin.finance";
                case "initiator" -> initiator;
                default -> throw new IllegalArgumentException(decision.toString());
            };
            awaitTask(runId, node);
            JsonNode task = openTask(PURCHASING, runId, node);
            String taskId = task.path("id").asString();
            Response claimed = claim(actor, PURCHASING, taskId);
            if (claimed.status() != 200) {
                failure = claimed;
                break;
            }
            Response decided = decide(actor, PURCHASING, taskId, decision.path("action").asString(),
                    decision.path("reason").asString(null));
            if (decided.status() != 201) {
                failure = decided;
                break;
            }
        }

        if (expect.has("httpStatus")) {
            assertThat(failure).as("expected a refused decision").isNotNull();
            assertThat(failure.status()).isEqualTo(expect.path("httpStatus").asInt());
            assertThat(failure.code()).isEqualTo(expect.path("problemCode").asString());
            assertThat(lifecycle(PURCHASING, runId)).isEqualTo(expect.path("lifecycle").asString());
            assertThat(Stub.postCount()).isEqualTo(expect.path("procurementCalls").asInt());
            return;
        }
        assertThat(failure).as("unexpected refusal %s", failure).isNull();
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention", "cancelled");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).as(run.toString()).isEqualTo(expect.path("lifecycle").asString());
        assertThat(run.path("outcome").asString()).isEqualTo(expect.path("outcome").asString());
        assertThat(run.path("outcomeKind").asString()).isEqualTo(expect.path("outcomeKind").asString());
        assertThat(Stub.postCount()).isEqualTo(expect.path("procurementCalls").asInt());
        assertThat(Stub.orderCount()).isEqualTo(expect.path("procurementCalls").asInt());
        assertThat(notifications(PURCHASING, runId, "workflowMessage")).isEqualTo(expect.path("notifications").asInt());
        for (JsonNode field : expect.path("outputHas")) {
            assertThat(run.path("output").has(field.asString())).as("output " + field).isTrue();
        }
        List<String> expectedVisited = new ArrayList<>();
        expect.path("visited").forEach(v -> expectedVisited.add(v.asString()));
        assertThat(visited(runId)).containsExactlyElementsOf(expectedVisited);
        // The initiator sees the outcome and output in their own request list.
        JsonNode mine = api(initiator).get("/workspaces/{ws}/me/requests", PURCHASING).body();
        assertThat(mine.path("items").findValues("id").stream().map(JsonNode::asString)).contains(runId.toString());
        JsonNode events = api(initiator).get("/workspaces/{ws}/runs/{id}/events", PURCHASING, runId).body();
        assertThat(events.path("items").get(0).path("type").asString()).isEqualTo("RUN_STARTED");
        assertThat(events.path("lifecycle").asString()).isEqualTo("completed");
        long previous = 0;
        for (JsonNode event : events.path("items")) {
            if ("manager".equals(event.path("nodeId").asString(null))) {
                assertThat(event.path("nodeLabel").asString()).isEqualTo("Review request");
            }
            assertThat(event.path("seq").asLong()).isEqualTo(previous + 1);
            previous = event.path("seq").asLong();
        }
    }

    /** Canonical nodes visited, in order (generated helpers like {@code manager__route} map back to their node). */
    private List<String> visited(UUID runId) {
        String pid = processInstance(PURCHASING, runId);
        List<String> visited = new ArrayList<>();
        for (HistoricActivityInstance activity : historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(pid).orderByHistoricActivityInstanceStartTime().asc()
                .orderByHistoricActivityInstanceId().asc().list()) {
            String id = activity.getActivityId();
            if ("sequenceFlow".equals(activity.getActivityType()) || id.contains("__")) {
                continue;
            }
            if (!visited.contains(id)) {
                visited.add(id);
            }
        }
        return visited;
    }

    private void awaitTask(UUID runId, String node) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            fastForward(PURCHASING, runId);
            return openTask(PURCHASING, runId, node) != null;
        });
    }
}
