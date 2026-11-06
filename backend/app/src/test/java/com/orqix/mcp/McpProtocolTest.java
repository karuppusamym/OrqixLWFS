package com.orqix.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.support.McpTestClient;
import com.orqix.support.McpTestTokens;
import com.orqix.support.TestUsers;

import tools.jackson.databind.JsonNode;

/**
 * S136 protocol and authorization: initialize and version negotiation, tools/list, tools/call, JSON-RPC errors,
 * transport rules, the 401 challenge with protected resource metadata (RFC 9728), and tokens with the wrong audience,
 * issuer, key or expiry.
 */
class McpProtocolTest extends McpTestBase {

    @Test
    void initializeNegotiatesTheVersionAndIssuesASessionBoundToThePerson() throws Exception {
        McpTestClient client = new McpTestClient(mvc, json, McpTestTokens.token("rhea.requester"));
        McpTestClient.Reply init = client.initialize(true);
        assertThat(init.status()).isEqualTo(200);
        assertThat(init.contentType()).startsWith("application/json");
        assertThat(init.result().path("protocolVersion").asString()).isEqualTo("2025-11-25");
        assertThat(init.result().path("capabilities").path("tools").isObject()).isTrue();
        assertThat(init.result().path("serverInfo").path("name").asString()).isEqualTo("orqix");
        assertThat(init.sessionId()).isNotBlank();

        // An older supported revision is echoed; an unknown one gets the latest.
        McpTestClient.Reply old = new McpTestClient(mvc, json, McpTestTokens.token("rhea.requester"))
                .request("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "x", "version", "1")));
        assertThat(old.result().path("protocolVersion").asString()).isEqualTo("2025-06-18");
        McpTestClient.Reply future = new McpTestClient(mvc, json, McpTestTokens.token("rhea.requester"))
                .request("initialize", Map.of("protocolVersion", "2099-01-01", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "x", "version", "1")));
        assertThat(future.result().path("protocolVersion").asString()).isEqualTo("2025-11-25");

        // ping; the session of rhea used with mani's token is unknown (404: initialize again).
        assertThat(client.request("ping", Map.of()).result().isObject()).isTrue();
        McpTestClient.Reply stolen = new McpTestClient(mvc, json, McpTestTokens.token("mani.manager"))
                .sessionId(client.sessionId()).request("ping", Map.of());
        assertThat(stolen.status()).isEqualTo(404);
        McpTestClient.Reply forged = new McpTestClient(mvc, json, McpTestTokens.token("rhea.requester"))
                .sessionId(client.sessionId() + "x").request("ping", Map.of());
        assertThat(forged.status()).isEqualTo(404);
    }

    @Test
    void toolsListHasTheFixedToolsAndOneStartToolPerExposedWorkflow() throws Exception {
        Exposed exposed = publishExposed(PURCHASING);
        java.util.UUID hidden = publishPurchase(PURCHASING, null);
        McpTestClient client = client("rhea.requester");
        McpTestClient.Reply list = client.listTools();
        List<String> names = new ArrayList<>();
        list.result().path("tools").forEach(t -> names.add(t.path("name").asString()));
        assertThat(names).contains("list_workflows", "search_requests", "get_request", "explain_request", "list_my_tasks",
                "get_task", "decide_task", "send_message", "confirm", exposed.tool());
        String hiddenKey = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, hidden).body()
                .path("key").asString();
        assertThat(names).doesNotContain("start_" + McpTools.sanitize(hiddenKey));
        JsonNode start = tool(list, exposed.tool());
        // Typed from the request form: the purchase form's fields, required ones required, no file fields.
        JsonNode input = start.path("inputSchema").path("properties").path("input");
        assertThat(input.path("properties").path("amount").path("type").asString()).isEqualTo("number");
        assertThat(input.path("properties").has("title")).isTrue();
        assertThat(input.path("required").toString()).contains("amount");
        assertThat(start.path("inputSchema").path("required").toString()).contains("input");
        assertThat(start.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(tool(list, "get_request").path("annotations").path("readOnlyHint").asBoolean()).isTrue();

        // The same person without workflow.start sees no start tool (viewer).
        McpTestClient.Reply viewer = client("vic.viewer").listTools();
        List<String> viewerNames = new ArrayList<>();
        viewer.result().path("tools").forEach(t -> viewerNames.add(t.path("name").asString()));
        assertThat(viewerNames).doesNotContain(exposed.tool()).contains("get_request");

        // Switched off: the start tool disappears at once.
        setMcp(PURCHASING, false);
        List<String> off = new ArrayList<>();
        client("rhea.requester").listTools().result().path("tools").forEach(t -> off.add(t.path("name").asString()));
        assertThat(off).doesNotContain(exposed.tool());
    }

    @Test
    void jsonRpcErrors() throws Exception {
        McpTestClient client = client("rhea.requester");
        McpTestClient.Reply unknownMethod = client.request("resources/list", Map.of());
        assertThat(unknownMethod.error().path("code").asInt()).isEqualTo(-32601);
        McpTestClient.Reply unknownTool = client.call("drop_tables", Map.of());
        assertThat(unknownTool.error().path("code").asInt()).isEqualTo(-32602);
        McpTestClient.Reply noName = client.request("tools/call", Map.of("arguments", Map.of()));
        assertThat(noName.error().path("code").asInt()).isEqualTo(-32602);
        McpTestClient.Reply parse = client.post("{not json");
        assertThat(parse.status()).isEqualTo(400);
        assertThat(parse.error().path("code").asInt()).isEqualTo(-32700);
        McpTestClient.Reply batch = client.post("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
        assertThat(batch.status()).isEqualTo(400);
        assertThat(batch.error().path("code").asInt()).isEqualTo(-32600);
        McpTestClient.Reply notRpc = client.post("{\"id\":1,\"method\":\"ping\"}");
        assertThat(notRpc.error().path("code").asInt()).isEqualTo(-32600);
        // Bad arguments are tool errors (the assistant can correct itself), not protocol errors.
        McpTestClient.Reply badLimit = client.call("list_my_tasks", Map.of("limit", 500));
        assertThat(isError(badLimit)).isTrue();
        assertThat(errorCode(badLimit)).isEqualTo("MALFORMED_REQUEST");
        // Notifications are accepted without a body.
        assertThat(client.notify("notifications/cancelled").status()).isEqualTo(202);
        // Unsupported protocol version header.
        McpTestClient.Reply version = client.send(MockMvcRequestBuilders.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON).header("Accept", "application/json, text/event-stream")
                .content("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}"));
        assertThat(version.status()).isEqualTo(200);
        McpTestClient.Reply wrong = new McpTestClient(mvc, json, McpTestTokens.token("rhea.requester"))
                .send(MockMvcRequestBuilders.post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .header("MCP-Protocol-Version", "1999-01-01")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}"));
        assertThat(wrong.status()).isEqualTo(400);
        // No server stream, no session deletion.
        assertThat(client.send(MockMvcRequestBuilders.get("/mcp")).status()).isEqualTo(405);
        assertThat(client.send(MockMvcRequestBuilders.delete("/mcp")).status()).isEqualTo(405);
        // Another browser origin is refused (DNS rebinding protection).
        McpTestClient.Reply origin = client.send(MockMvcRequestBuilders.post("/mcp").header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        assertThat(origin.status()).isEqualTo(403);
    }

    @Test
    void withoutAUsableTokenTheAnswerIs401WithTheResourceMetadata() throws Exception {
        McpTestClient anonymous = new McpTestClient(mvc, json, null);
        McpTestClient.Reply none = anonymous.request("initialize", Map.of());
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.wwwAuthenticate()).startsWith("Bearer ")
                .contains("resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"");

        var metadata = mvc.perform(MockMvcRequestBuilders.get("/.well-known/oauth-protected-resource/mcp")).andReturn();
        assertThat(metadata.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.parse(metadata.getResponse().getContentAsString());
        assertThat(body.path("resource").asString()).isEqualTo("http://localhost/mcp");
        assertThat(body.path("authorization_servers").get(0).asString()).isEqualTo(TestUsers.ISSUER);
        assertThat(body.path("bearer_methods_supported").toString()).contains("header");

        Instant later = Instant.now().plusSeconds(300);
        // A token for the REST API (audience orqix-api) is not a token for this resource: no passthrough of audiences.
        assertInvalid(McpTestTokens.token("rhea.requester", "orqix-api", later, TestUsers.ISSUER, false), "not issued");
        // The resource URI itself is an accepted audience (RFC 8707 resource indicator).
        McpTestClient byResource = new McpTestClient(mvc, json,
                McpTestTokens.token("rhea.requester", "http://localhost/mcp", later, TestUsers.ISSUER, false));
        assertThat(byResource.initialize(false).status()).isEqualTo(200);
        // Expired (beyond the 30 s skew), another issuer, a key nobody trusts, garbage.
        assertInvalid(McpTestTokens.token("rhea.requester", McpTestTokens.AUDIENCE, Instant.now().minusSeconds(120),
                TestUsers.ISSUER, false), "expired");
        assertInvalid(McpTestTokens.token("rhea.requester", McpTestTokens.AUDIENCE, later,
                "https://idp.other.example/realms/x", false), "not valid");
        assertInvalid(McpTestTokens.token("rhea.requester", McpTestTokens.AUDIENCE, later, TestUsers.ISSUER, true),
                "not valid");
        assertInvalid("machine.rhea", "not valid");
    }

    private void assertInvalid(String token, String description) throws Exception {
        McpTestClient.Reply reply = new McpTestClient(mvc, json, token).request("initialize", Map.of());
        assertThat(reply.status()).as(reply.body()).isEqualTo(401);
        assertThat(reply.wwwAuthenticate()).contains("error=\"invalid_token\"").contains(description)
                .contains("resource_metadata=");
    }

    private static JsonNode tool(McpTestClient.Reply list, String name) {
        for (JsonNode t : list.result().path("tools")) {
            if (name.equals(t.path("name").asString())) {
                return t;
            }
        }
        throw new AssertionError("no tool " + name);
    }
}
