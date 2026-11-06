package com.orqix.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Helpers for the S135 agent tool tests: registered MCP servers on the WireMock MCP stub ({@code /mcp/<key>}), agent
 * connections derived from the vendor risk example, pins, bindings and connection tests. Keys are random so classes
 * sharing the test database never collide.
 */
public abstract class AgentToolsTestBase extends RuntimeTestBase {

    protected static final String ADMIN = "wade.admin";
    protected static final String ACME = "{\"vendorName\":\"Acme Ltd\"}";

    @BeforeEach
    void agentToolStubs() {
        AgentLlmStub.reset();
        McpStub.reset();
    }

    protected static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String answer(int score, String band, boolean sanctions) {
        return "{\"riskScore\":" + score + ",\"riskBand\":\"" + band + "\",\"sanctionsHit\":" + sanctions
                + ",\"registryId\":\"VR-1\"}";
    }

    /** A registered tool server with the stub's two tools (and optionally its policy resource), bound and enabled. */
    protected String server(String prefix, boolean withResource) throws Exception {
        String key = key(prefix);
        ObjectNode definition = json.object();
        definition.put("schemaVersion", "0.1").put("kind", "mcpServer").put("key", key).put("version", 1)
                .put("name", "Vendor registry " + key);
        ObjectNode spec = definition.putObject("mcpServer");
        ArrayNode tools = spec.putArray("tools");
        tools.addObject().put("name", "vendor_risk_lookup").put("label", "Vendor registry")
                .put("description", "Administrator text: looks a vendor up by legal name and returns its risk score.");
        tools.addObject().put("name", "vendor_financials").put("label", "Vendor financials")
                .put("description", "Administrator text: returns revenue and credit rating of a vendor.");
        if (withResource) {
            spec.putArray("resources").addObject().put("name", "policy").put("label", "Procurement policy")
                    .put("uri", "orqix://policy/procurement").put("maxChars", 500);
        }
        register(key, definition);
        bind(key, McpStub.url() + "/" + key);
        return key;
    }

    /** Pins the server's tools as the stub lists them now. */
    protected JsonNode pin(String serverKey) throws Exception {
        Response taken = api(ADMIN).post(Map.of(), "/workspaces/{ws}/connections/{key}/tool-snapshot", PURCHASING,
                serverKey);
        assertThat(taken.status()).as(taken.toString()).isEqualTo(200);
        return taken.body();
    }

    /** An agent like the vendor risk example whose tool list and agent part are set by {@code patch}. */
    protected String agent(String prefix, Consumer<ObjectNode> patch) throws Exception {
        String key = key(prefix);
        ObjectNode definition = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot",
                "../.."), "examples/connections/vendor-risk-assistant.connection.json")));
        definition.put("key", key).put("version", 1).put("name", "Assistant " + key);
        ObjectNode agent = (ObjectNode) definition.path("operations").path("assessVendor").path("agent");
        patch.accept(agent);
        register(key, definition);
        return key;
    }

    protected void register(String key, ObjectNode definition) throws Exception {
        Response created = api(ADMIN).post(Map.of("definition", definition), "/workspaces/{ws}/connections/{key}/versions",
                PURCHASING, key);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
    }

    protected Response registerRaw(String key, ObjectNode definition) throws Exception {
        definition.put("key", key);
        return api(ADMIN).post(Map.of("definition", definition), "/workspaces/{ws}/connections/{key}/versions",
                PURCHASING, key);
    }

    protected void bind(String key, String url) throws Exception {
        Response bound = api(ADMIN).put(Map.of("baseUrl", url, "auth", Map.of("type", "none"), "enabled", true), null,
                "/workspaces/{ws}/connections/{key}/binding", PURCHASING, key);
        assertThat(bound.status()).as(bound.toString()).isIn(200, 201);
    }

    /** The connection test of an agent: one synchronous execution with the Acme sample. */
    protected JsonNode test(String agentKey) throws Exception {
        Response result = api(ADMIN).post(Map.of("version", 1, "operation", "assessVendor", "input",
                Map.of("vendorName", "Acme Ltd", "country", "United Kingdom", "annualSpend", 100)),
                "/workspaces/{ws}/connections/{key}/test", PURCHASING, agentKey);
        assertThat(result.status()).as(result.toString()).isEqualTo(200);
        return result.body();
    }

    protected static ObjectNode tool(ArrayNode tools, String name, String label) {
        return tools.addObject().put("name", name).put("label", label);
    }
}
