package com.orqix.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.McpTestClient;

import tools.jackson.databind.JsonNode;

/**
 * S136 end to end on PostgreSQL and Flowable with a scripted MCP client: a workspace administrator switches the server
 * on in Settings; the requester's assistant finds the start tool, prepares and confirms a purchase request; the
 * manager's assistant finds the task, decides it with confirmation; the request completes on the engine; the
 * requester's assistant explains it; Settings shows the calls without values; per-person rate limits apply.
 */
class McpEndToEndTest extends McpTestBase {

    @Test
    void requesterAndManagerAssistantsCompleteARequest() throws Exception {
        setMcp(PURCHASING, false);
        Response off = api("wade.admin").get("/workspaces/{ws}/mcp-server", PURCHASING);
        assertThat(off.status()).isEqualTo(200);
        assertThat(off.body().path("enabled").asBoolean()).isFalse();
        assertThat(off.body().path("endpoint").asString()).isEqualTo("http://localhost/mcp");
        Response on = api("wade.admin").put(Map.of("enabled", true), null, "/workspaces/{ws}/mcp-server", PURCHASING);
        assertThat(on.status()).isEqualTo(200);
        assertThat(on.body().path("enabled").asBoolean()).isTrue();
        assertThat(on.body().path("updatedBy").path("displayName").asString()).isNotBlank();
        // Only a workspace administrator: others are refused, foreign people do not see the workspace.
        assertThat(api("rhea.requester").put(Map.of("enabled", false), null, "/workspaces/{ws}/mcp-server", PURCHASING)
                .status()).isEqualTo(403);
        assertThat(api("fred.facilities").get("/workspaces/{ws}/mcp-server", PURCHASING).status()).isEqualTo(404);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/mcp-server/calls", PURCHASING).status()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'mcp.server.updated' AND workspace_id = :ws "
                + "AND metadata->>'enabled' = 'true'").param("ws", PURCHASING).query(Integer.class).single()).isPositive();

        Exposed exposed = publishExposed(PURCHASING);
        McpTestClient requester = client("rhea.requester");
        JsonNode workflows = structured(requester.call("list_workflows", Map.of()));
        assertThat(workflows.toString()).contains(exposed.tool());
        JsonNode prepared = structured(requester.call(exposed.tool(), Map.of("input", input(new BigDecimal("450")))));
        JsonNode started = structured(requester.call("confirm", Map.of("confirmationId",
                prepared.path("confirmationId").asString())));
        UUID runId = UUID.fromString(started.path("request").path("id").asString());
        JsonNode mine = structured(requester.call("search_requests", Map.of("mine", true)));
        assertThat(mine.toString()).contains(runId.toString());

        McpTestClient manager = client("mani.manager");
        // The assistant pages through the claimable queue (the shared test database holds many open tasks).
        String taskId = null;
        String cursor = null;
        for (int page = 0; page < 100 && taskId == null; page++) {
            Map<String, Object> args = new java.util.HashMap<>(Map.of("view", "claimable", "limit", 50));
            if (cursor != null) {
                args.put("cursor", cursor);
            }
            JsonNode tasks = structured(manager.call("list_my_tasks", args));
            for (JsonNode t : tasks.path("items")) {
                if (runId.toString().equals(t.path("runId").asString())) {
                    taskId = t.path("id").asString();
                }
            }
            cursor = tasks.path("nextCursor").isString() ? tasks.path("nextCursor").asString() : null;
            if (cursor == null) {
                break;
            }
        }
        assertThat(taskId).as("the manager's claimable task").isNotNull();
        JsonNode task = structured(manager.call("get_task", Map.of("taskId", taskId))).path("task");
        assertThat(task.path("request").path("input").path("amount").decimalValue()).isEqualByComparingTo("450");
        JsonNode decision = structured(manager.call("decide_task", Map.of("taskId", taskId, "action", "approve",
                "comment", "Fine")));
        assertThat(decision.path("summary").asString()).contains("Approve").contains("claimed for you first");
        JsonNode decided = structured(manager.call("confirm", Map.of("confirmationId",
                decision.path("confirmationId").asString())));
        assertThat(decided.path("action").asString()).as(decided.toString()).isEqualTo("taskDecided");

        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        JsonNode explained = structured(requester.call("explain_request", Map.of("requestId", runId.toString())));
        assertThat(explained.path("request").path("lifecycle").asString()).isIn("completed", "needsAttention");
        assertThat(explained.path("explanation").asString()).contains(started.path("request").path("reference").asString());

        // Settings, recent calls: tool, step, outcome and ids, newest first; no values anywhere.
        Response calls = api("wade.admin").get("/workspaces/{ws}/mcp-server/calls?limit=100", PURCHASING);
        assertThat(calls.status()).isEqualTo(200);
        String text = calls.body().toString();
        assertThat(text).contains("\"tool\":\"" + exposed.tool() + "\"").contains("\"tool\":\"decide_task\"")
                .contains("\"tool\":\"confirm\"").contains(runId.toString()).contains(taskId)
                .doesNotContain("Replacement laptop").doesNotContain("Fine");
        assertThat(calls.body().path("items").get(0).path("tool").asString()).isEqualTo("explain_request");
    }

    @Test
    void callsAreRateLimitedPerPerson() throws Exception {
        McpTestClient rhea = client("rhea.requester");
        McpTestClient mani = client("mani.manager");
        int limit = 60;
        for (int i = 0; i < limit; i++) {
            assertThat(isError(rhea.call("list_workflows", Map.of()))).isFalse();
        }
        McpTestClient.Reply limited = rhea.call("list_workflows", Map.of());
        assertThat(errorCode(limited)).isEqualTo("RATE_LIMITED");
        assertThat(limited.result().path("content").get(0).path("text").asString()).contains("seconds");
        // Another person is not affected.
        assertThat(isError(mani.call("list_workflows", Map.of()))).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'mcp.tool-called' "
                + "AND metadata->>'outcome' = 'rateLimited'").query(Integer.class).single()).isPositive();
    }
}
