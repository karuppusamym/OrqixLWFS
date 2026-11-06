package com.orqix.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.McpTestClient;
import com.orqix.support.McpTestTokens;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/** Shared setup of the MCP server tests (S136): the switch, signed-in clients and an exposed purchase workflow. */
abstract class McpTestBase extends RuntimeTestBase {

    @Autowired
    protected McpRateLimiter limiter;

    @BeforeEach
    void mcpOn() throws Exception {
        limiter.reset();
        setMcp(PURCHASING, true);
        setMcp(SANDBOX, false);
    }

    /** Switches the MCP server through the admin API (audited), as a workspace administrator. */
    protected void setMcp(UUID workspaceId, boolean enabled) throws Exception {
        String admin = workspaceId.equals(PURCHASING) ? "wade.admin" : "pat.platform";
        Response r = api(admin).put(Map.of("enabled", enabled), null, "/workspaces/{ws}/mcp-server", workspaceId);
        if (r.status() != 200) {
            // Sandbox has no seeded workspace administrator in every setup; fall back to the table.
            jdbc.sql("""
                    INSERT INTO mcp_workspace_setting (workspace_id, enabled, updated_by)
                    VALUES (:ws, :on, (SELECT id FROM subject ORDER BY id LIMIT 1))
                    ON CONFLICT (workspace_id) DO UPDATE SET enabled = :on
                    """).param("ws", workspaceId).param("on", enabled).update();
        }
    }

    /** An initialized MCP client of the test user. */
    protected McpTestClient client(String user, boolean elicitation) throws Exception {
        api(user).get("/me");
        McpTestClient client = new McpTestClient(mvc, json, McpTestTokens.token(user));
        McpTestClient.Reply init = client.initialize(elicitation);
        assertThat(init.status()).as(init.body()).isEqualTo(200);
        return client;
    }

    protected McpTestClient client(String user) throws Exception {
        return client(user, false);
    }

    /** A published purchase workflow offered to assistants; returns its id and tool name. */
    protected Exposed publishExposed(UUID workspaceId) throws Exception {
        UUID id = publishPurchase(workspaceId, d -> d.put("exposeAsTool", true));
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", workspaceId, id).body().path("key")
                .asString();
        return new Exposed(id, "start_" + McpTools.sanitize(key));
    }

    protected record Exposed(UUID workflowId, String tool) {
    }

    /** The structured result of a tools/call. */
    protected static JsonNode structured(McpTestClient.Reply reply) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        return reply.result().path("structuredContent");
    }

    protected static boolean isError(McpTestClient.Reply reply) {
        return reply.result().path("isError").asBoolean(false);
    }

    protected static String errorCode(McpTestClient.Reply reply) {
        return reply.result().path("structuredContent").path("error").path("code").asString(null);
    }
}
