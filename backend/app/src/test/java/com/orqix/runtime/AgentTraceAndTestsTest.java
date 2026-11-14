package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.AgentLlmStub;
import com.orqix.support.AgentToolsTestBase;
import com.orqix.support.McpStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-M05 and CG-M07 on the real stack (docs/build/S135): the opt-in, redacted conversation trace linked to the step,
 * the agent dashboard, and agent test suites with fixture tools, tool-sequence assertions and a pinned judge model.
 */
class AgentTraceAndTestsTest extends AgentToolsTestBase {

    private static final String ASSISTANT = "vendor-risk-assistant";

    @org.springframework.beans.factory.annotation.Autowired
    com.orqix.integration.agenttools.AgentTrace traces;

    private UUID publishVendor() throws Exception {
        jdbc.sql("""
                UPDATE connection_binding SET base_url = :url, enabled = true, auth_type = 'none', secret_ref = NULL
                WHERE workspace_id = :ws AND connection_key = :key
                """).param("url", McpStub.url()).param("ws", PURCHASING).param("key", ASSISTANT).update();
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

    private UUID start(UUID workflow) throws Exception {
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", Map.of("vendorName",
                "Acme Ltd", "country", "United Kingdom", "annualSpend", 12000)), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private Response traceSettings(boolean enabled, int expectedVersion) throws Exception {
        return api(ADMIN).put(Map.of("enabled", enabled, "retentionDays", 7, "redactFields", List.of("vendorName"),
                "redactPatterns", List.of("VR-[0-9]+"), "expectedVersion", expectedVersion), null,
                "/workspaces/{ws}/agent-trace-settings", PURCHASING);
    }

    @Test
    void theTraceIsOffByDefaultAndWhenEnabledKeepsRedactedTurnsAndCallsForTheStep() throws Exception {
        UUID workflow = publishVendor();
        JsonNode settings = api(ADMIN).get("/workspaces/{ws}/agent-trace-settings", PURCHASING).body();
        int version = settings.path("version").asInt();
        if (settings.path("enabled").asBoolean()) {
            version = traceSettings(false, version).body().path("version").asInt();
        }
        // off: metadata only, nothing in the trace
        AgentLlmStub.toolCall("c1", "vendor_risk_lookup", ACME, 0.0001);
        AgentLlmStub.answer(answer(72, "high", false), 0.0001);
        UUID quiet = start(workflow);
        awaitLifecycle(PURCHASING, quiet, "waiting", "needsAttention");
        assertThat(jdbc.sql("SELECT count(*) FROM agent_trace_event WHERE run_id = :run").param("run", quiet)
                .query(Integer.class).single()).isZero();

        // a pattern that could make matching slow is refused; only workspace access managers change the setting
        assertThat(api(ADMIN).put(Map.of("enabled", true, "redactPatterns", List.of("(a+)+$"), "expectedVersion",
                version), null, "/workspaces/{ws}/agent-trace-settings", PURCHASING).status()).isEqualTo(422);
        assertThat(api("rhea.requester").put(Map.of("enabled", true, "expectedVersion", version), null,
                "/workspaces/{ws}/agent-trace-settings", PURCHASING).status()).isEqualTo(403);
        Response on = traceSettings(true, version);
        assertThat(on.status()).as(on.toString()).isEqualTo(200);
        try {
            McpStub.toolText("Contact jane.doe@example.com, card 4111 1111 1111 1111, registry VR-12345.");
            AgentLlmStub.toolCall("c2", "vendor_risk_lookup", ACME, 0.0001);
            AgentLlmStub.answer(answer(72, "high", false), 0.0001);
            UUID traced = start(workflow);
            awaitLifecycle(PURCHASING, traced, "waiting", "needsAttention");

            JsonNode trace = api("olga.operator").get("/workspaces/{ws}/runs/{id}/agent-trace?nodeId=assess",
                    PURCHASING, traced).body();
            assertThat(trace.path("enabled").asBoolean()).isTrue();
            List<String> kinds = new java.util.ArrayList<>();
            trace.path("events").forEach(e -> kinds.add(e.path("kind").asString()));
            assertThat(kinds).containsExactly("input", "turn", "toolCall", "turn", "end");
            String text = trace.toString();
            assertThat(text).doesNotContain("Acme").doesNotContain("jane.doe@example.com")
                    .doesNotContain("4111 1111").doesNotContain("VR-12345");
            assertThat(text).contains("[redacted]").contains("[email]").contains("[number]");
            JsonNode turn = trace.path("events").get(1);
            assertThat(turn.path("model").asString()).isEqualTo(AgentLlmStub.MODEL);
            assertThat(turn.path("costUsd").decimalValue()).isPositive();
            // only operators read it; other workspaces see nothing
            assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{id}/agent-trace", PURCHASING, traced)
                    .status()).isEqualTo(403);
            assertThat(api("fred.facilities").get("/workspaces/{ws}/runs/{id}/agent-trace", PURCHASING, traced)
                    .status()).isEqualTo(404);
            // the journal, audit and call log stay metadata only
            assertThat(jdbc.sql("SELECT tool_calls::text FROM agent_call WHERE run_id = :run").param("run", traced)
                    .query(String.class).single()).doesNotContain("jane.doe");
            // retention: expired rows are deleted
            assertThat(traces.purge(java.time.Instant.now().plus(java.time.Duration.ofDays(8)))).isPositive();
            assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{id}/agent-trace", PURCHASING, traced).body()
                    .path("events")).isEmpty();
        } finally {
            int current = api(ADMIN).get("/workspaces/{ws}/agent-trace-settings", PURCHASING).body().path("version")
                    .asInt();
            traceSettings(false, current);
        }
    }

    @Test
    void theDashboardShowsCallsCostRefusalsAndSwitchesToOperators() throws Exception {
        UUID workflow = publishVendor();
        AgentLlmStub.toolCall("c1", "vendor_financials", ACME, 0.0001);
        AgentLlmStub.toolCall("c2", "vendor_risk_lookup", ACME, 0.0001);
        AgentLlmStub.answer(answer(72, "high", false), 0.0001);
        UUID run = start(workflow);
        awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");

        Response dashboard = api("olga.operator").get("/workspaces/{ws}/agent-dashboard?days=7", PURCHASING);
        assertThat(dashboard.status()).as(dashboard.toString()).isEqualTo(200);
        JsonNode body = dashboard.body();
        JsonNode assistant = null;
        for (JsonNode agent : body.path("agents")) {
            if (ASSISTANT.equals(agent.path("connectionKey").asString())) {
                assistant = agent;
            }
        }
        assertThat(assistant).isNotNull();
        assertThat(assistant.path("calls").asInt()).isPositive();
        assertThat(assistant.path("costUsd").decimalValue()).isPositive();
        assertThat(assistant.path("p95DurationMs").asLong()).isNotNegative();
        assertThat(body.path("refusedToolCalls").path("refusedNotAllowed").asInt()).isPositive();
        assertThat(body.path("aiTaskAgreement").isNull()).isTrue();
        assertThat(api("rhea.requester").get("/workspaces/{ws}/agent-dashboard", PURCHASING).status()).isEqualTo(403);
        assertThat(api("fred.facilities").get("/workspaces/{ws}/agent-dashboard", PURCHASING).status()).isEqualTo(404);
    }

    private ObjectNode suite(String judgeModel) {
        ObjectNode suite = json.object();
        suite.put("operation", "assessVendor");
        suite.putObject("judge").put("profile", "economy").put("model", judgeModel);
        ObjectNode high = suite.putArray("cases").addObject();
        high.put("name", "High risk vendor goes to compliance");
        high.putObject("input").put("vendorName", "Acme Ltd").put("country", "United Kingdom").put("annualSpend", 100);
        ObjectNode fixture = high.putObject("fixtures").putArray("vendor_risk_lookup").addObject();
        fixture.putObject("args").put("vendorName", "Acme Ltd");
        fixture.putObject("result").put("riskScore", 72).put("riskBand", "high").put("sanctionsHit", false)
                .put("registryId", "VR-1");
        ObjectNode expect = high.putObject("expect");
        expect.putArray("calledOnce").add("vendor_risk_lookup");
        expect.putArray("never").add("vendor_financials");
        expect.put("neverWriteTools", true);
        expect.putObject("output").put("riskBand", "high").put("riskScore", 72);
        expect.putObject("judge").put("criteria", "The answer reports the registry's values unchanged.")
                .put("minScore", 4);
        return suite;
    }

    @Test
    void anAgentTestSuiteRunsAgainstFixturesAssertsToolSequencesAndUsesAPinnedJudge() throws Exception {
        JsonNode current = api(ADMIN).get("/workspaces/{ws}/connections/{key}/agent-tests", PURCHASING, ASSISTANT).body();
        Response saved = api(ADMIN).put(Map.of("suite", suite(AgentLlmStub.MODEL), "expectedVersion",
                current.path("version").asInt()), null, "/workspaces/{ws}/connections/{key}/agent-tests", PURCHASING,
                ASSISTANT);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        AgentLlmStub.toolCall("c1", "vendor_risk_lookup", ACME, 0.0001);
        AgentLlmStub.answer(answer(72, "high", false), 0.0001);
        AgentLlmStub.answer("{\"score\":5,\"reason\":\"The values match the registry.\"}", 0.00005);

        Response ran = api(ADMIN).post(Map.of(), "/workspaces/{ws}/connections/{key}/agent-tests/run", PURCHASING,
                ASSISTANT);

        assertThat(ran.status()).as(ran.toString()).isEqualTo(200);
        JsonNode result = ran.body().path("lastResult");
        assertThat(result.path("passed").asInt()).as(result.toString()).isEqualTo(1);
        JsonNode testCase = result.path("cases").get(0);
        assertThat(testCase.path("toolSequence")).extracting(JsonNode::asString).containsExactly("vendor_risk_lookup");
        JsonNode judge = null;
        for (JsonNode a : testCase.path("assertions")) {
            if ("judge".equals(a.path("kind").asString())) {
                judge = a;
            }
        }
        assertThat(judge.path("score").asInt()).isEqualTo(5);
        assertThat(judge.path("judgeModel").asString()).isEqualTo(AgentLlmStub.MODEL);
        // the tools were fixtures: no MCP server was contacted and nothing was journaled
        assertThat(McpStub.calls()).isEmpty();
        assertThat(AgentLlmStub.requests().get(2).path("response_format").isObject()).isTrue();

        // a judge profile that resolves to another model than the pinned one fails the case instead of judging
        int version = ran.body().path("version").asInt();
        api(ADMIN).put(Map.of("suite", suite("another/judge-model"), "expectedVersion", version), null,
                "/workspaces/{ws}/connections/{key}/agent-tests", PURCHASING, ASSISTANT);
        AgentLlmStub.toolCall("c2", "vendor_risk_lookup", ACME, 0.0001);
        AgentLlmStub.answer(answer(72, "high", false), 0.0001);
        JsonNode changed = api(ADMIN).post(Map.of(), "/workspaces/{ws}/connections/{key}/agent-tests/run", PURCHASING,
                ASSISTANT).body().path("lastResult");
        assertThat(changed.path("failed").asInt()).isEqualTo(1);
        assertThat(changed.toString()).contains("JUDGE_MODEL_CHANGED");
        assertThat(AgentLlmStub.pending()).isZero();

        // suites name tools of the agent only; only connection managers save and run them
        ObjectNode wrong = suite(AgentLlmStub.MODEL);
        ((ObjectNode) wrong.path("cases").get(0).path("fixtures")).putArray("delete_everything");
        assertThat(api(ADMIN).put(Map.of("suite", wrong, "expectedVersion", version + 1), null,
                "/workspaces/{ws}/connections/{key}/agent-tests", PURCHASING, ASSISTANT).status()).isEqualTo(422);
        assertThat(api("rhea.requester").post(Map.of(), "/workspaces/{ws}/connections/{key}/agent-tests/run",
                PURCHASING, ASSISTANT).status()).isEqualTo(403);
    }
}
