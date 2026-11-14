package com.orqix.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.McpTestClient;

import tools.jackson.databind.JsonNode;

/**
 * S136 permission parity: every MCP tool answers each person exactly as the REST operation behind it does — the same
 * body (what the UI shows) when allowed, the same refusal code when not — including people of another workspace and
 * a workspace where the MCP server is off (hidden like an unknown ID).
 */
class McpPermissionParityTest extends McpTestBase {

    private static final List<String> PEOPLE = List.of("rhea.requester", "mani.manager", "max.manager", "vic.viewer",
            "olga.operator", "dana.designer", "fred.facilities", "otto.outsider");

    @Test
    void readToolsReturnWhatRestReturnsToEachPerson() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, exposed.workflowId(), BigDecimal.TEN)
                .path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        for (String user : PEOPLE) {
            McpTestClient mcp = client(user);
            Map<String, String> ids = Map.of("workspaceId", PURCHASING.toString(), "requestId", runId.toString());

            parity(user + " get_request", api(user).get("/workspaces/{ws}/runs/{id}", PURCHASING, runId),
                    mcp.call("get_request", ids), "request");
            parity(user + " get_task", api(user).get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId),
                    mcp.call("get_task", Map.of("workspaceId", PURCHASING.toString(), "taskId", taskId)), "task");
            // explain_request is visible exactly when getRun and listRunEvents are.
            Response rest = api(user).get("/workspaces/{ws}/runs/{id}", PURCHASING, runId);
            McpTestClient.Reply explained = mcp.call("explain_request", ids);
            assertThat(isError(explained)).as(user + " explain_request").isEqualTo(rest.status() != 200);
            if (rest.status() == 200) {
                assertThat(structured(explained).path("explanation").asString()).contains(rest.body().path("reference").asString());
                // The explanation never carries request values the person could not see.
                if (rest.body().path("input").isNull()) {
                    assertThat(explained.body()).doesNotContain("Replacement laptop");
                }
            } else {
                assertThat(errorCode(explained)).isEqualTo(rest.code());
            }

            for (String view : List.of("assigned", "claimable", "completed")) {
                Response tasks = api(user).get("/workspaces/{ws}/tasks?view=" + view + "&limit=20", PURCHASING);
                McpTestClient.Reply listed = mcp.call("list_my_tasks", Map.of("workspaceId", PURCHASING.toString(),
                        "view", view, "limit", 20));
                assertThat(isError(listed)).as(user + " list_my_tasks " + view + " " + tasks).isEqualTo(tasks.status() != 200);
                if (tasks.status() == 200) {
                    assertThat(ids(structured(listed).path("items"))).as(user + " " + view)
                            .isEqualTo(ids(tasks.body().path("items")));
                } else {
                    assertThat(errorCode(listed)).isEqualTo(tasks.code());
                }
            }

            // search_requests: workspace search with run.read, otherwise only the person's own requests.
            Response all = api(user).get("/workspaces/{ws}/runs?limit=20", PURCHASING);
            McpTestClient.Reply searched = mcp.call("search_requests", Map.of("workspaceId", PURCHASING.toString(),
                    "limit", 20));
            if (all.status() == 200) {
                assertThat(structured(searched).path("scope").asString()).isEqualTo("workspace");
                assertThat(ids(structured(searched).path("items"))).isEqualTo(ids(all.body().path("items")));
            } else {
                Response mine = api(user).get("/workspaces/{ws}/me/requests?limit=20", PURCHASING);
                assertThat(isError(searched)).as(user + " search_requests").isEqualTo(mine.status() != 200);
                if (mine.status() == 200) {
                    assertThat(structured(searched).path("scope").asString()).isEqualTo("mine");
                    assertThat(ids(structured(searched).path("items"))).isEqualTo(ids(mine.body().path("items")));
                } else {
                    assertThat(errorCode(searched)).isEqualTo(mine.code());
                }
            }

            // list_workflows: exactly the workflows the catalog offers the person to start.
            McpTestClient.Reply workflows = mcp.call("list_workflows", Map.of());
            Set<String> mcpIds = new HashSet<>();
            boolean[] truncated = {false};
            structured(workflows).path("workspaces").forEach(ws -> {
                if (ws.path("workspaceId").asString().equals(PURCHASING.toString())) {
                    ws.path("workflows").forEach(w -> mcpIds.add(w.path("workflowId").asString()));
                    truncated[0] = ws.path("truncated").asBoolean();
                }
            });
            Set<String> restIds = new HashSet<>();
            Response startable = api(user).get("/workspaces/{ws}/workflows?startable=true&limit=100", PURCHASING);
            if (startable.status() == 200) {
                JsonNode page = api(user).getAllPages("/workspaces/{ws}/workflows?startable=true&limit=100", PURCHASING);
                page.path("items").forEach(w -> {
                    if (w.path("canStart").asBoolean()) {
                        restIds.add(w.path("id").asString());
                    }
                });
            }
            if (truncated[0]) {
                // The shared test database can hold more startable workflows than the tool lists (200): the tool
                // must then say so and list only workflows the catalog also offers.
                assertThat(mcpIds).as(user + " list_workflows (truncated)").hasSize(200);
                assertThat(restIds).as(user + " list_workflows (truncated)").containsAll(mcpIds).hasSizeGreaterThan(200);
            } else {
                assertThat(mcpIds).as(user + " list_workflows").isEqualTo(restIds);
            }
        }
    }

    @Test
    void decideTaskFollowsEligibilityAndSeparationOfDutiesLikeTheTaskPage() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        UUID runId = UUID.fromString(start("mani.manager", PURCHASING, exposed.workflowId(), BigDecimal.TEN)
                .path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        Map<String, Object> approve = Map.of("workspaceId", PURCHASING.toString(), "taskId", taskId, "action", "approve");
        // The requester may not approve their own request (selfApproval deny), a non-manager is not eligible, an
        // outsider does not see the task: refused at prepare exactly when the claim on the task page is refused.
        for (String user : List.of("mani.manager", "rhea.requester", "vic.viewer", "fred.facilities")) {
            JsonNode task = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
            McpTestClient.Reply prepared = client(user).call("decide_task", approve);
            Response restTask = api(user).get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId);
            boolean restMayClaim = restTask.status() == 200
                    && restTask.body().path("allowedActions").path("claim").path("allowed").asBoolean();
            assertThat(isError(prepared)).as(user + " " + prepared.body()).isEqualTo(!restMayClaim);
            if (restTask.status() != 200) {
                assertThat(errorCode(prepared)).isEqualTo(restTask.code());
            }
            assertThat(api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body()
                    .path("version").asInt()).isEqualTo(task.path("version").asInt());
        }
        // An eligible manager (not the requester) decides through confirm: claimed and decided as on the task page.
        McpTestClient max = client("max.manager");
        String id = structured(max.call("decide_task", approve)).path("confirmationId").asString();
        // A rejection needs a reason, as in the UI.
        McpTestClient.Reply noReason = max.call("decide_task", Map.of("workspaceId", PURCHASING.toString(),
                "taskId", taskId, "action", "reject"));
        assertThat(errorCode(noReason)).isEqualTo("REASON_REQUIRED");
        JsonNode done = structured(max.call("confirm", Map.of("confirmationId", id)));
        assertThat(done.path("action").asString()).as(done.toString()).isEqualTo("taskDecided");
        assertThat(done.path("decision").path("action").asString()).isEqualTo("approve");
        JsonNode decided = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
        assertThat(decided.path("status").asString()).isEqualTo("completed");
        assertThat(decided.path("completedBy").path("displayName").asString()).isNotBlank();
        // A second decision on the decided task is refused at prepare.
        assertThat(isError(client("max.manager").call("decide_task", approve))).isTrue();
    }

    @Test
    void sendMessageNeedsMessageSendLikeRest() throws Exception {
        Map<String, Object> message = Map.of("workspaceId", PURCHASING.toString(), "name", "payment-received",
                "correlationKey", "MCP-" + UUID.randomUUID());
        for (String user : List.of("rhea.requester", "vic.viewer", "fred.facilities")) {
            McpTestClient.Reply prepared = client(user).call("send_message", message);
            Response rest = api(user).post(Map.of("name", "payment-received", "correlationKey", "REST-" + UUID.randomUUID()),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/messages", PURCHASING);
            assertThat(rest.status()).isIn(403, 404);
            assertThat(errorCode(prepared)).as(user).isEqualTo(rest.code());
        }
        McpTestClient olga = client("olga.operator");
        JsonNode prepared = structured(olga.call("send_message", message));
        assertThat(prepared.path("summary").asString()).contains("payment-received");
        JsonNode sent = structured(olga.call("confirm", Map.of("confirmationId", prepared.path("confirmationId").asString())));
        assertThat(sent.path("action").asString()).isEqualTo("messageSent");
        assertThat(sent.path("message").path("status").asString()).isIn("delivered", "buffered");
    }

    @Test
    void aWorkspaceWithTheServerOffOrForeignIsHiddenLikeAnUnknownId() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, exposed.workflowId(), BigDecimal.TEN)
                .path("id").asString());
        Map<String, String> args = Map.of("workspaceId", PURCHASING.toString(), "requestId", runId.toString());
        Map<String, String> unknown = Map.of("workspaceId", UUID.randomUUID().toString(), "requestId", runId.toString());
        // Foreign person: same answer as an unknown workspace.
        McpTestClient fred = client("fred.facilities");
        assertThat(errorCode(fred.call("get_request", args))).isEqualTo("NOT_FOUND");
        assertThat(fred.call("get_request", args).result().path("structuredContent"))
                .isEqualTo(fred.call("get_request", unknown).result().path("structuredContent"));
        // The requester while the server is off in the workspace: hidden too, and nothing in listMcpCalls of it.
        setMcp(PURCHASING, false);
        McpTestClient rhea = client("rhea.requester");
        assertThat(errorCode(rhea.call("get_request", args))).isEqualTo("NOT_FOUND");
        assertThat(errorCode(rhea.call("get_request", Map.of("requestId", runId.toString())))).isEqualTo("MCP_DISABLED");
        assertThat(structured(rhea.call("list_workflows", Map.of())).path("workspaces").isEmpty()).isTrue();
    }

    private void parity(String what, Response rest, McpTestClient.Reply mcp, String field) {
        assertThat(isError(mcp)).as(what + " " + rest + " / " + mcp.body()).isEqualTo(rest.status() != 200);
        if (rest.status() == 200) {
            assertThat(structured(mcp).path(field)).as(what).isEqualTo(rest.body());
        } else {
            assertThat(errorCode(mcp)).as(what).isEqualTo(rest.code());
        }
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new java.util.ArrayList<>();
        items.forEach(i -> ids.add(i.path("id").asString()));
        return ids;
    }
}
