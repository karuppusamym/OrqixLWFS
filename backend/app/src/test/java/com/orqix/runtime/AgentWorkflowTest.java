package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orqix.support.AgentLlmStub;
import com.orqix.support.McpStub;
import com.orqix.support.RuntimeTestBase;

/** Verifies the agent connection through publication, effect dispatch, tool calls, rules and tasks. */
class AgentWorkflowTest extends RuntimeTestBase {
    @BeforeEach
    void agentStubs() {
        AgentLlmStub.reset();
        McpStub.reset();
        jdbc.sql("UPDATE connection_binding SET base_url = :url WHERE workspace_id = :ws AND connection_key = 'vendor-risk-assistant'")
                .param("url", McpStub.url()).param("ws", PURCHASING).update();
    }

    private UUID vendor(int score, String band, boolean sanctioned) throws Exception {
        UUID wf = publishPurchase(PURCHASING, d -> {
            try {
                var example = json.parse(Files.readString(Path.of("../../examples/vendor-onboarding-check.json")));
                String key = d.path("workflowKey").asString();
                d.removeAll();
                example.properties().forEach(e -> d.set(e.getKey(), e.getValue()));
                d.put("workflowKey", key);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        AgentLlmStub.toolCall("lookup-1", "vendor_risk_lookup", "{\"vendorName\":\"Acme Office Supplies\"}", 0.0001);
        AgentLlmStub.answer(json.write(Map.of("riskScore", score, "riskBand", band, "sanctionsHit", sanctioned,
                "registryId", "US-ACME-001")), 0.0001);
        var started = api("rhea.requester").post(Map.of("workflowId", wf, "input", Map.of(
                "vendorName", "Acme Office Supplies", "country", "United States", "annualSpend", 1000)), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    @Test
    void lowRiskToolResultCompletesRunAndRecordsAgentUsage() throws Exception {
        UUID run = vendor(15, "low", false);
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        var detail = run(PURCHASING, run);
        assertThat(detail.path("lifecycle").asString()).as(detail.toString()).isEqualTo("completed");
        assertThat(detail.path("output").path("riskScore").asInt()).isEqualTo(15);
        assertThat(McpStub.toolCalls()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM agent_call WHERE run_id = :id AND outcome = 'succeeded'")
                .param("id", run).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void highRiskResultWaitsForComplianceApproval() throws Exception {
        UUID run = vendor(80, "high", true);
        awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");
        var detail = run(PURCHASING, run);
        assertThat(detail.path("lifecycle").asString()).as(detail.toString()).isEqualTo("waiting");
        approve("fin.finance", PURCHASING, run, "compliance");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("completed");
        assertThat(McpStub.toolCalls()).hasSize(1);
    }
}
