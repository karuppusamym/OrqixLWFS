package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;

/**
 * STU03 cross-check (docs/build/S96): a dry run whose call is answered by a mock follows the same path and ends with the
 * same outcome (and attention reason) as a real run of the same published workflow on PostgreSQL and Flowable, whose
 * call is answered the same way by the procurement stub. The dry runs themselves call nothing: the stub's POST count
 * moves only with the real runs.
 */
class SimulationFidelityTest extends RuntimeTestBase {

    private static final BigDecimal AMOUNT = new BigDecimal("1800");

    @Autowired
    HistoryService historyService;

    private record Real(String lifecycle, String outcome, String reason, List<String> visited) {
    }

    private Real real(UUID workflowId) throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, AMOUNT).path("id").asString());
        approve("mani.manager", PURCHASING, runId, "manager");
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        JsonNode run = run(PURCHASING, runId);
        List<String> visited = visited(runId);
        // A call that stops for attention fails inside its asynchronous job, so the engine history has no row for it;
        // the run names it as the step that needs attention. The dry run lists it as the step where it stopped.
        String stoppedAt = run.path("attention").path("nodeId").asString(null);
        if (stoppedAt != null && !visited.contains(stoppedAt)) {
            visited.add(stoppedAt);
        }
        return new Real(run.path("lifecycle").asString(), run.path("outcome").asString(null),
                run.path("attention").path("reason").asString(null), visited);
    }

    private JsonNode simulate(UUID workflowId, Map<String, Object> callMock) throws Exception {
        int posts = Stub.postCount();
        Response r = api("dana.designer").post(Map.of("useActiveRelease", true, "input", input(AMOUNT),
                "scenario", Map.of("calls", Map.of("procure", callMock))),
                "/workspaces/{ws}/workflows/{id}/simulate", PURCHASING, workflowId);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        assertThat(Stub.postCount()).as("a dry run never calls the system").isEqualTo(posts);
        return r.body();
    }

    private static List<String> simulatedPath(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.path("trace").forEach(e -> {
            if (!ids.contains(e.path("stepId").asString())) {
                ids.add(e.path("stepId").asString());
            }
        });
        return ids;
    }

    private void assertSame(Real real, JsonNode sim) {
        JsonNode o = sim.path("outcome");
        if ("completed".equals(real.lifecycle())) {
            assertThat(o.path("status").asString()).isEqualTo("finished");
            assertThat(o.path("businessOutcome").asString()).isEqualTo(real.outcome());
        } else {
            assertThat(real.lifecycle()).isEqualTo("needsAttention");
            assertThat(o.path("status").asString()).isEqualTo("needsAttention");
            assertThat(o.path("attention").path("reason").asString()).isEqualTo(real.reason());
        }
        assertThat(simulatedPath(sim)).as("same steps in the same order").isEqualTo(real.visited());
    }

    @Test
    void aMockedSuccessFollowsTheSamePathAsARealRun() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        Real real = real(workflowId);
        assertThat(real.lifecycle()).isEqualTo("completed");
        JsonNode sim = simulate(workflowId, Map.of("status", 201, "body", Map.of("id", "PO-1", "status", "CREATED")));
        assertSame(real, sim);
    }

    @Test
    void aMockedClientErrorStopsLikeARealRejection() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        Stub.fault(Stub.Mode.STATUS400, 1, 0);
        Real real = real(workflowId);
        assertThat(real.reason()).isEqualTo("EFFECT_REJECTED");
        assertSame(real, simulate(workflowId, Map.of("status", 400, "body", Map.of("error", "injected"))));
    }

    @Test
    void aMockedBodyThatBreaksTheResponseSchemaStopsLikeARealInvalidAnswer() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        Stub.fault(Stub.Mode.INVALID_BODY, 1, 0);
        Real real = real(workflowId);
        assertThat(real.lifecycle()).isEqualTo("needsAttention");
        assertSame(real, simulate(workflowId, Map.of("status", 201, "body", Map.of("status", "CREATED"))));
    }

    @Test
    void mockedServerErrorsOnEveryAttemptStopLikeRealExhaustedRetries() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        Stub.fault(Stub.Mode.STATUS500, 10, 0);
        Real real = real(workflowId);
        assertThat(real.reason()).isEqualTo("RETRIES_EXHAUSTED");
        assertSame(real, simulate(workflowId, Map.of("status", 500)));
    }

    /** Canonical nodes visited, in order of first visit (generated helpers such as {@code manager__route} left out). */
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
}
