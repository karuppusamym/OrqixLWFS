package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orqix.support.IntegrationTestBase;
import com.orqix.support.McpStub;

import tools.jackson.databind.JsonNode;

/**
 * Opt-in Demo 2 smoke test with real tool calling on the economy profile (DeepSeek through OpenRouter): the model reads
 * the tool list, decides to call {@code vendor_risk_lookup} on the MCP server (WireMock with the stub's fixtures) and
 * answers in the result schema. Spends a fraction of a cent. Excluded by default; run with
 * {@code ./mvnw -pl app -am verify -Dtest=AgentLiveTest -Dsurefire.failIfNoSpecifiedTests=false -Dorqix.ai.live=true}.
 * The key is read from the repository's gitignored {@code .env} and never printed.
 */
@Tag("live-ai")
@EnabledIfSystemProperty(named = "orqix.ai.live", matches = "true")
class AgentLiveTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void live(DynamicPropertyRegistry registry) {
        registry.add("orqix.ai.api-key", AgentLiveTest::key);
        registry.add("orqix.ai.base-url", () -> "https://openrouter.ai/api/v1");
        registry.add("orqix.ai.timeout", () -> "60s");
        registry.add("orqix.ai.budget.per-request-max-usd", () -> "0.05");
        registry.add("flowable.async-executor-activate", () -> "false");
    }

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theEconomyModelCallsTheAllowListedToolAndAnswersInTheResultSchema() throws Exception {
        McpStub.reset();
        jdbc.sql("UPDATE connection_binding SET base_url = :url, enabled = true, auth_type = 'none', secret_ref = NULL "
                + "WHERE workspace_id = :ws AND connection_key = 'vendor-risk-assistant'")
                .param("url", McpStub.url()).param("ws", PURCHASING).update();
        String vendor = "Acme Ltd";
        Response result = api("wade.admin").post(Map.of("version", 1, "operation", "assessVendor", "input", Map.of(
                "vendorName", vendor, "country", "United Kingdom", "annualSpend", 12000)),
                "/workspaces/{ws}/connections/{key}/test", PURCHASING, "vendor-risk-assistant");

        assertThat(result.status()).as(result.toString()).isEqualTo(200);
        JsonNode body = result.body();
        assertThat(body.path("outcome").asString()).as(body.toString()).isEqualTo("succeeded");
        assertThat(body.path("output").path("riskScore").asInt()).isEqualTo(72);
        assertThat(body.path("output").path("riskBand").asString()).isEqualTo("high");
        assertThat(body.path("output").path("sanctionsHit").asBoolean()).isFalse();
        assertThat(body.path("summary").asString())
                .isEqualTo("Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)");
        assertThat(McpStub.toolCalls()).extracting(McpStub.Call::tool).containsOnly("vendor_risk_lookup");
        assertThat(McpStub.toolCalls().get(0).arguments().path("vendorName").asString()).isEqualTo(vendor);
        JsonNode ledger = json.parse(jdbc.sql("SELECT to_jsonb(g)::text FROM ai_generation g WHERE kind = 'agent' "
                + "ORDER BY created_at DESC LIMIT 1").query(String.class).single());
        assertThat(ledger.path("outcome").asString()).isEqualTo("success");
        assertThat(ledger.path("model").asString()).contains("deepseek");
        assertThat(ledger.path("cost_usd").decimalValue()).isPositive().isLessThan(new java.math.BigDecimal("0.05"));
        System.out.println("live-ai agent: model=" + ledger.path("model").asString() + " attempts="
                + ledger.path("attempts").asInt() + " tokens=" + ledger.path("input_tokens").asLong() + "/"
                + ledger.path("output_tokens").asLong() + " costUsd=" + ledger.path("cost_usd").asString());
    }

    private static String key() {
        try {
            Path env = Path.of(System.getProperty("orqix.ai.envFile",
                    Path.of(System.getProperty("orqix.repoRoot", "../.."), ".env").toString()));
            for (String line : Files.readAllLines(env)) {
                if (line.startsWith("OPENROUTER_API_KEY=")) {
                    return line.substring("OPENROUTER_API_KEY=".length()).trim().replace("\"", "");
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return System.getenv().getOrDefault("OPENROUTER_API_KEY", "");
    }
}
