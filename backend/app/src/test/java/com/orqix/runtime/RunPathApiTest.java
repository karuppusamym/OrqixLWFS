package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * getRunPath: the same access rule as the run events (run.read, initiator, task participant; others 404), a graph
 * without conditions or request data, the steps passed (silent steps inferred) and the current steps with status.
 */
class RunPathApiTest extends RuntimeTestBase {

    private static List<String> ids(JsonNode array, String field) {
        List<String> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.path(field).asString()));
        return ids;
    }

    @Test
    void theDiagramShowsWhereTheRequestIsAndWhichRoutesWereTaken() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());

        JsonNode waiting = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, runId).body();
        assertThat(waiting.path("lifecycle").asString()).isEqualTo("waiting");
        assertThat(ids(waiting.path("visited"), "nodeId")).containsExactly("start", "manager");
        assertThat(waiting.path("visited").get(1).path("outcome").isNull()).isTrue();
        assertThat(waiting.path("current")).hasSize(1);
        assertThat(waiting.path("current").get(0).path("nodeId").asString()).isEqualTo("manager");
        assertThat(waiting.path("current").get(0).path("status").asString()).isEqualTo("waiting");
        assertThat(waiting.path("current").get(0).path("candidateGroup").asString()).isEqualTo("managers");
        assertThat(waiting.path("taken")).hasSize(1);
        assertThat(waiting.path("diagram").path("nodes")).hasSize(10);

        // The graph never carries conditions, field names or what the requester typed.
        String text = waiting.path("diagram").toString();
        assertThat(text).doesNotContain("fieldRef").doesNotContain("condition\":{").doesNotContain("Replacement laptop");

        approve("mani.manager", PURCHASING, runId, "manager");
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        JsonNode done = api("olga.operator").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, runId).body();
        assertThat(done.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(ids(done.path("visited"), "nodeId")).containsExactlyInAnyOrder("start", "manager", "amountPolicy", "route",
                "procure", "notify", "fulfilled");
        assertThat(done.path("current")).isEmpty();
        List<String> taken = new ArrayList<>();
        done.path("taken").forEach(t -> taken.add(t.asString()));
        assertThat(taken).containsExactlyInAnyOrder("t1", "t2", "t4", "t6", "t9", "t10");
    }

    @Test
    void longHistoryKeepsCurrentStepsButDoesNotClaimAnIncompletePath() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        com.orqix.support.TestDatabase.legacyFixtureJdbc().sql("""
                INSERT INTO orqix.run_event(workspace_id, run_id, seq, type, occurred_at, data)
                SELECT :ws, :run, n, 'STAGE_OVERDUE', now(), '{}'::jsonb
                FROM generate_series(100, 5100) n
                """).param("ws", PURCHASING).param("run", runId).update();
        var response = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, runId);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("historyTruncated").asBoolean()).isTrue();
        assertThat(response.body().path("visited")).isEmpty();
        assertThat(response.body().path("taken")).isEmpty();
        assertThat(response.body().path("current").get(0).path("nodeId").asString()).isEqualTo("manager");
    }

    @Test
    void anApprovalParticipantMayReadThePathButAnOutsiderMayNot() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        assertThat(api("mani.manager").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, runId).status()).isEqualTo(200);
        assertThat(api("vic.viewer").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, runId).status()).isEqualTo(200);
        // Another requester in the same workspace has no relationship to this request: 404 like the events.
        assertThat(api("max.manager").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, runId).status())
                .isEqualTo(api("max.manager").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, runId).status());
        assertThat(api("otto.outsider").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, runId).status()).isEqualTo(404);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{id}/path", SANDBOX, runId).status()).isEqualTo(404);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, UUID.randomUUID()).status())
                .isEqualTo(404);
    }

    @Test
    void aRejectedRunTakesTheRejectedRoute() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        JsonNode task = openTask(PURCHASING, runId, "manager");
        assertThat(claim("mani.manager", PURCHASING, task.path("id").asString()).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, task.path("id").asString(), "reject", "No").status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, runId, "completed");
        JsonNode path = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, runId).body();
        List<String> taken = new ArrayList<>();
        path.path("taken").forEach(t -> taken.add(t.asString()));
        assertThat(taken).containsExactlyInAnyOrder("t1", "t3", "t11");
        for (JsonNode v : path.path("visited")) {
            if ("manager".equals(v.path("nodeId").asString())) {
                assertThat(v.path("outcome").asString()).isEqualTo("rejected");
            }
        }
    }
}
