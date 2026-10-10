package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.AgentLlmStub;
import com.orqix.support.McpStub;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S13 section 10 on the real stack (PostgreSQL, Flowable, WireMock OpenRouter and MCP): the demo path through rules and
 * approval, the allow-list, provider retry, invalid output, budget, egress, credentials and the connection test.
 */
@ExtendWith(OutputCaptureExtension.class)
class AgentFlowsTest extends RuntimeTestBase {

    private static final String LOOKUP_ACME = "{\"vendorName\":\"Acme Ltd\"}";

    @Autowired
    OrqixProperties properties;

    @BeforeEach
    void agentStubs() {
        AgentLlmStub.reset();
        McpStub.reset();
        bindMcp(McpStub.url());
    }

    private void bindMcp(String url) {
        jdbc.sql("""
                UPDATE connection_binding SET base_url = :url, enabled = true, auth_type = 'none', secret_ref = NULL
                WHERE workspace_id = :ws AND connection_key = 'vendor-risk-assistant'
                """).param("url", url).param("ws", PURCHASING).update();
    }

    private UUID publishVendorWorkflow() throws Exception {
        JsonNode example = json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot", "../.."),
                "examples/vendor-onboarding-check.json")));
        return publishPurchase(PURCHASING, d -> {
            String key = d.path("workflowKey").asString();
            d.removeAll();
            example.properties().forEach(e -> d.set(e.getKey(), e.getValue().deepCopy()));
            d.put("workflowKey", key);
            ((ObjectNode) d.path("execution")).put("retryDelaySeconds", 5);
        });
    }

    private UUID startVendor(UUID workflow, String vendor) throws Exception {
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", Map.of("vendorName", vendor,
                "country", "United Kingdom", "annualSpend", 12000)), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private static String answer(int score, String band, boolean sanctions) {
        return "{\"riskScore\":" + score + ",\"riskBand\":\"" + band + "\",\"sanctionsHit\":" + sanctions
                + ",\"registryId\":\"VR-1\"}";
    }

    private String effectState(UUID run) {
        return jdbc.sql("SELECT state || ' attempts=' || attempts FROM effect_journal WHERE run_id = :id")
                .param("id", run).query(String.class).single();
    }

    // ------------------------------------------------------------------ the demo path

    @Test
    void highRiskGoesToComplianceAndEverythingIsRecordedWithoutPayloads() throws Exception {
        UUID workflow = publishVendorWorkflow();
        AgentLlmStub.toolCall("c1", "vendor_risk_lookup", LOOKUP_ACME, 0.0002);
        AgentLlmStub.answer(answer(72, "high", false), 0.0003);

        UUID run = startVendor(workflow, "Acme Ltd");
        awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");
        assertThat(lifecycle(PURCHASING, run)).as(run(PURCHASING, run).toString()).isEqualTo("waiting");
        assertThat(openTask(PURCHASING, run, "compliance")).isNotNull();
        approve("fin.finance", PURCHASING, run, "compliance");
        awaitLifecycle(PURCHASING, run, "completed");

        assertThat(run(PURCHASING, run).path("output").path("riskScore").asInt()).isEqualTo(72);
        // timeline: business sentence from the connection definition, never model prose
        String event = jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :id AND type = 'EFFECT_SUCCEEDED'")
                .param("id", run).query(String.class).single();
        JsonNode data = json.parse(event);
        assertThat(data.path("summary").asString())
                .isEqualTo("Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)");
        assertThat(data.path("agent").path("toolCalls").get(0).path("name").asString()).isEqualTo("vendor_risk_lookup");
        assertThat(effectState(run)).isEqualTo("succeeded attempts=1");
        // metering: one agent_call and one ai_generation row of kind agent; audit entry; tokens and cost, no payloads
        String call = jdbc.sql("SELECT to_jsonb(c)::text FROM agent_call c WHERE run_id = :id").param("id", run)
                .query(String.class).single();
        assertThat(json.parse(call).path("turns").asInt()).isEqualTo(2);
        assertThat(json.parse(call).path("tool_calls").get(0).path("outcome").asString()).isEqualTo("ok");
        assertThat(json.parse(call).path("cost_usd").decimalValue()).isEqualByComparingTo("0.0005");
        assertThat(call).doesNotContain("Acme").doesNotContain("vendorName");
        String ledger = jdbc.sql("SELECT to_jsonb(g)::text FROM ai_generation g WHERE kind = 'agent' "
                + "ORDER BY created_at DESC LIMIT 1").query(String.class).single();
        assertThat(json.parse(ledger).path("outcome").asString()).isEqualTo("success");
        assertThat(json.parse(ledger).path("prompt_version").asString()).isEqualTo("agent-v1");
        String audit = jdbc.sql("SELECT to_jsonb(a)::text FROM audit_event a WHERE action = 'agent.call' "
                + "AND metadata ->> 'effectId' = :effect").param("effect", jdbc.sql(
                        "SELECT id::text FROM effect_journal WHERE run_id = :id").param("id", run).query(String.class)
                .single()).query(String.class).single();
        assertThat(json.parse(audit).path("actor_type").asString()).isEqualTo("system");
        assertThat(audit).doesNotContain("Acme").doesNotContain("vendorName");
        // what the model and the tool server saw
        List<JsonNode> requests = AgentLlmStub.requests();
        assertThat(requests).hasSize(2);
        JsonNode first = requests.get(0);
        assertThat(first.path("model").asString()).isEqualTo(AgentLlmStub.MODEL);
        assertThat(first.path("tools")).hasSize(1);
        assertThat(first.path("tools").get(0).path("function").path("name").asString()).isEqualTo("vendor_risk_lookup");
        assertThat(first.path("provider").path("max_price").isObject()).isTrue();
        assertThat(first.path("response_format").isMissingNode()).isTrue();
        assertThat(AgentLlmStub.headers().get(0).get("authorization")).isEqualTo("Bearer " + AgentLlmStub.KEY);
        assertThat(first.toString()).doesNotContain(McpStub.url()).doesNotContain("secretRef");
        assertThat(McpStub.toolCalls()).hasSize(1);
    }

    @Test
    void lowRiskIsApprovedAutomaticallyAndASanctionsHitAlwaysNeedsCompliance() throws Exception {
        UUID workflow = publishVendorWorkflow();
        AgentLlmStub.toolCall("c1", "vendor_risk_lookup", "{\"vendorName\":\"Globex Corporation\"}", 0.0001);
        AgentLlmStub.answer(answer(18, "low", false), 0.0001);
        UUID low = startVendor(workflow, "Globex Corporation");
        awaitLifecycle(PURCHASING, low, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, low)).isEqualTo("completed");
        assertThat(openTask(PURCHASING, low, "compliance")).isNull();

        AgentLlmStub.toolCall("c2", "vendor_risk_lookup", "{\"vendorName\":\"Umbrella Trading\"}", 0.0001);
        AgentLlmStub.answer(answer(35, "low", true), 0.0001);
        UUID sanctioned = startVendor(workflow, "Umbrella Trading");
        awaitLifecycle(PURCHASING, sanctioned, "waiting", "needsAttention");
        assertThat(lifecycle(PURCHASING, sanctioned)).isEqualTo("waiting");
        assertThat(openTask(PURCHASING, sanctioned, "compliance")).isNotNull();
    }

    @Test
    void aToolOutsideTheAllowListIsNeverCalled() throws Exception {
        UUID workflow = publishVendorWorkflow();
        AgentLlmStub.toolCall("c1", "vendor_financials", LOOKUP_ACME, 0.0001);
        AgentLlmStub.toolCall("c2", "vendor_risk_lookup", LOOKUP_ACME, 0.0001);
        AgentLlmStub.answer(answer(72, "high", false), 0.0001);

        UUID run = startVendor(workflow, "Acme Ltd");
        awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");

        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(McpStub.toolCalls()).extracting(McpStub.Call::tool).containsExactly("vendor_risk_lookup");
        String tools = jdbc.sql("SELECT tool_calls::text FROM agent_call WHERE run_id = :id").param("id", run)
                .query(String.class).single();
        assertThat(tools).contains("refusedNotAllowed").contains("vendor_financials");
    }

    // ------------------------------------------------------------------ failures

    @Test
    void aProviderOutageIsRetriedAndTheRunCompletes() throws Exception {
        UUID workflow = publishVendorWorkflow();
        AgentLlmStub.fail(503);
        AgentLlmStub.toolCall("c1", "vendor_risk_lookup", "{\"vendorName\":\"Initech\"}", 0.0001);
        AgentLlmStub.answer(answer(45, "medium", false), 0.0001);

        UUID run = startVendor(workflow, "Initech");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");

        assertThat(lifecycle(PURCHASING, run)).isEqualTo("completed");
        assertThat(effectState(run)).isEqualTo("succeeded attempts=2");
        assertThat(AgentLlmStub.requests()).hasSize(3);
        assertThat(McpStub.toolCalls()).hasSize(1);
    }

    @Test
    void anAnswerThatNeverMatchesTheSchemaNeedsAttentionWithABusinessMessage() throws Exception {
        UUID workflow = publishVendorWorkflow();
        AgentLlmStub.toolCall("c1", "vendor_risk_lookup", LOOKUP_ACME, 0.0001);
        AgentLlmStub.answer("The vendor looks risky to me.", 0.0001);
        AgentLlmStub.answer("{\"riskScore\":\"high\"}", 0.0001);

        UUID run = startVendor(workflow, "Acme Ltd");
        awaitLifecycle(PURCHASING, run, "needsAttention", "completed");

        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("lifecycle").asString()).as(detail.toString()).isEqualTo("needsAttention");
        assertThat(detail.path("attention").path("reason").asString()).isEqualTo("RESPONSE_INVALID");
        assertThat(detail.path("attention").toString()).contains("could not produce a valid answer")
                .doesNotContain("looks risky");
        assertThat(effectState(run)).startsWith("permanentFailure");
    }

    @Test
    void aUsedUpBudgetNeedsAttentionWithoutCallingTheModel() throws Exception {
        UUID workflow = publishVendorWorkflow();
        UUID row = UUID.randomUUID();
        UUID subject = jdbc.sql("SELECT id FROM subject WHERE external_subject = 'kc-dana.designer'")
                .query(UUID.class).single();
        superuser("""
                INSERT INTO orqix.ai_generation (id, workspace_id, subject_id, kind, profile, model, requested_model,
                    prompt_version, attempts, input_tokens, output_tokens, cost_usd, duration_ms, outcome)
                VALUES (?, ?, ?, 'draft', 'economy', 'deepseek/deepseek-v3.2', 'deepseek/deepseek-v3.2', 'draft-v1',
                    1, 1, 1, 1000.00, 1, 'success')
                """, row, PURCHASING, subject);
        try {
            UUID run = startVendor(workflow, "Acme Ltd");
            awaitLifecycle(PURCHASING, run, "needsAttention", "completed");

            JsonNode detail = run(PURCHASING, run);
            assertThat(detail.path("lifecycle").asString()).as(detail.toString()).isEqualTo("needsAttention");
            assertThat(detail.path("attention").path("reason").asString()).isEqualTo("CONNECTION_UNAVAILABLE");
            assertThat(detail.path("attention").toString()).contains("monthly AI budget is used up");
            assertThat(AgentLlmStub.requests()).isEmpty();
            assertThat(McpStub.calls()).isEmpty();
        } finally {
            superuser("DELETE FROM orqix.ai_generation WHERE id = ?", row);
        }
    }

    @Test
    void aToolServerOutsideTheEgressPolicyIsNeverContacted() throws Exception {
        UUID workflow = publishVendorWorkflow();
        bindMcp("http://localhost:1/mcp");

        UUID run = startVendor(workflow, "Acme Ltd");
        awaitLifecycle(PURCHASING, run, "needsAttention", "completed");

        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("lifecycle").asString()).as(detail.toString()).isEqualTo("needsAttention");
        assertThat(detail.path("attention").path("reason").asString()).isEqualTo("CONNECTION_UNAVAILABLE");
        assertThat(AgentLlmStub.requests()).isEmpty();
        assertThat(McpStub.calls()).isEmpty();
    }

    // ------------------------------------------------------------------ secrets

    @Test
    void theToolServerCredentialIsUsedOnTheWireButNeverStoredReturnedOrLogged(CapturedOutput logs) throws Exception {
        String credential = "agent-secret-canary-" + UUID.randomUUID();
        String secretRef = "test-" + UUID.randomUUID();
        Path directory = properties.secrets().dir();
        Files.createDirectories(directory);
        Path file = directory.resolve(secretRef);
        Files.writeString(file, credential, StandardCharsets.UTF_8);
        try {
            UUID workflow = publishVendorWorkflow();
            jdbc.sql("UPDATE connection_binding SET auth_type = 'bearerToken', secret_ref = :ref "
                    + "WHERE workspace_id = :ws AND connection_key = 'vendor-risk-assistant'")
                    .param("ref", secretRef).param("ws", PURCHASING).update();
            McpStub.requireToken(credential);
            AgentLlmStub.toolCall("c1", "vendor_risk_lookup", LOOKUP_ACME, 0.0001);
            AgentLlmStub.answer(answer(72, "high", false), 0.0001);

            UUID run = startVendor(workflow, "Acme Ltd");
            awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");
            assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");

            assertThat(McpStub.calls()).filteredOn(c -> !"session/delete".equals(c.method()))
                    .allMatch(c -> ("Bearer " + credential).equals(c.authorization()));
            List<String> stored = new java.util.ArrayList<>();
            for (String sql : List.of("SELECT to_jsonb(e)::text FROM effect_journal e", "SELECT to_jsonb(e)::text FROM run_event e",
                    "SELECT to_jsonb(c)::text FROM agent_call c", "SELECT to_jsonb(a)::text FROM audit_event a",
                    "SELECT to_jsonb(g)::text FROM ai_generation g")) {
                stored.addAll(jdbc.sql(sql).query(String.class).list());
            }
            assertThat(String.join("\n", stored)).doesNotContain(credential);
            assertThat(run(PURCHASING, run).toString()).doesNotContain(credential);
            assertThat(api("wade.admin").get("/workspaces/{ws}/connections/{key}", PURCHASING, "vendor-risk-assistant")
                    .body().toString()).doesNotContain(credential);
            assertThat(AgentLlmStub.requests().toString()).doesNotContain(credential);
            assertThat(logs.getAll()).doesNotContain(credential);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ------------------------------------------------------------------ connection test

    @Test
    void anAdministratorCanTestAnAgentOperationWithASampleInput() throws Exception {
        AgentLlmStub.toolCall("c1", "vendor_risk_lookup", LOOKUP_ACME, 0.0002);
        AgentLlmStub.answer(answer(72, "high", false), 0.0003);
        Map<String, Object> body = Map.of("version", 1, "operation", "assessVendor", "input", Map.of("vendorName",
                "Acme Ltd", "country", "United Kingdom", "annualSpend", 100));

        Response result = api("wade.admin").post(body, "/workspaces/{ws}/connections/{key}/test", PURCHASING,
                "vendor-risk-assistant");

        assertThat(result.status()).as(result.toString()).isEqualTo(200);
        assertThat(result.body().path("outcome").asString()).isEqualTo("succeeded");
        assertThat(result.body().path("output").path("riskScore").asInt()).isEqualTo(72);
        assertThat(result.body().path("summary").asString()).isEqualTo(
                "Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)");
        // a sample that does not match the operation's input is refused before any AI is spent
        Response invalid = api("wade.admin").post(Map.of("version", 1, "operation", "assessVendor", "input",
                Map.of("vendorName", "Acme Ltd")), "/workspaces/{ws}/connections/{key}/test", PURCHASING,
                "vendor-risk-assistant");
        assertThat(invalid.status()).as(invalid.toString()).isEqualTo(422);
        assertThat(AgentLlmStub.requests()).hasSize(2);
        // only connection managers may test
        assertThat(api("rhea.requester").post(body, "/workspaces/{ws}/connections/{key}/test", PURCHASING,
                "vendor-risk-assistant").status()).isEqualTo(403);
    }

    @Test
    void theConnectionListMarksAgentConnectionsAndBindingsRefuseApiKeyHeaders() throws Exception {
        JsonNode list = api("wade.admin").get("/workspaces/{ws}/connections", PURCHASING).body();
        boolean found = false;
        for (JsonNode item : list.path("items")) {
            if ("vendor-risk-assistant".equals(item.path("key").asString())) {
                found = true;
                assertThat(item.path("kind").asString()).isEqualTo("agent");
            } else if ("procurement".equals(item.path("key").asString())) {
                assertThat(item.path("kind").asString()).isEqualTo("http");
            }
        }
        assertThat(found).isTrue();
        Response refused = api("wade.admin").put(Map.of("baseUrl", McpStub.url(), "auth", Map.of("type", "apiKeyHeader",
                "headerName", "X-Key", "secretRef", "some-secret"), "enabled", true), "\"r1\"",
                "/workspaces/{ws}/connections/{key}/binding", PURCHASING, "vendor-risk-assistant");
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
    }

    private static void superuser(String sql, Object... params) throws Exception {
        try (Connection connection = DriverManager.getConnection(TestDatabase.jdbcUrl(), "postgres", "postgres");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.executeUpdate();
        }
    }
}
