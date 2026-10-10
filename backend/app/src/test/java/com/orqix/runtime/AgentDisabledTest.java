package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orqix.support.AgentLlmStub;
import com.orqix.support.IntegrationTestBase;
import com.orqix.support.McpStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S13 section 7 without a provider key: publishing a workflow that asks an agent shows a warning that must be
 * acknowledged, validation reports it, and testing the connection says that AI is not configured. No job runs in this
 * context (its executor is off), so it cannot take work from the other contexts.
 */
class AgentDisabledTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void aiOff(DynamicPropertyRegistry registry) {
        registry.add("orqix.ai.api-key", () -> "");
        registry.add("flowable.async-executor-activate", () -> "false");
    }

    private UUID vendorDraft() throws Exception {
        String key = "vd-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = api("dana.designer").post(Map.of("key", key, "name", "Vendor " + key, "templateId",
                "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        ObjectNode definition = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot",
                "../.."), "examples/vendor-onboarding-check.json")));
        definition.put("workflowKey", key);
        Response saved = api("dana.designer").put(Map.of("definition", definition), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return id;
    }

    @Test
    void publishingAnAgentWorkflowWarnsThatAiIsNotAvailable() throws Exception {
        UUID workflow = vendorDraft();

        JsonNode validation = api("dana.designer").post(Map.of("revision", 2), "/workspaces/{ws}/workflows/{id}/validate",
                PURCHASING, workflow).body();
        assertThat(validation.path("publishable").asBoolean()).isTrue();
        JsonNode warning = null;
        for (JsonNode issue : validation.path("issues")) {
            if ("WF_AGENT_AI_UNAVAILABLE".equals(issue.path("code").asString())) {
                warning = issue;
            }
        }
        assertThat(warning).as(validation.toString()).isNotNull();
        assertThat(warning.path("severity").asString()).isEqualTo("warning");
        assertThat(warning.path("nodeId").asString()).isEqualTo("assess");
        assertThat(warning.path("message").asString()).contains("AI is not configured");

        Response refused = api("paul.publisher").post(Map.of("draftRevision", 2), UUID.randomUUID().toString(),
                "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("WARNINGS_NOT_ACKNOWLEDGED");
        assertThat(refused.body().path("issues").toString()).contains("WF_AGENT_AI_UNAVAILABLE");

        Response published = api("paul.publisher").post(Map.of("draftRevision", 2, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        assertThat(published.body().toString()).contains("WF_AGENT_AI_UNAVAILABLE");
    }

    @Test
    void testingAnAgentConnectionSaysThatAiIsNotConfigured() throws Exception {
        AgentLlmStub.reset();
        McpStub.reset();

        Response result = api("wade.admin").post(Map.of("version", 1, "operation", "assessVendor", "input", Map.of(
                "vendorName", "Acme Ltd", "country", "United Kingdom", "annualSpend", 100)),
                "/workspaces/{ws}/connections/{key}/test", PURCHASING, "vendor-risk-assistant");

        assertThat(result.status()).as(result.toString()).isEqualTo(422);
        assertThat(result.code()).isEqualTo("AI_NOT_CONFIGURED");
        assertThat(McpStub.calls()).isEmpty();
        assertThat(AgentLlmStub.requests()).isEmpty();
    }
}
