package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

/** S10: without a provider key the product is rules-only (shared test context, application-test sets no key). */
class AiDisabledTest extends IntegrationTestBase {

    /**
     * The shared base enables AI against a WireMock provider for agent steps (S13); this class needs the rules-only product.
     * No jobs run in this context, so its executor is off and cannot take work from the other contexts.
     */
    @DynamicPropertySource
    static void aiOff(DynamicPropertyRegistry registry) {
        registry.add("orqix.ai.api-key", () -> "");
        registry.add("flowable.async-executor-activate", () -> "false");
    }

    @Test
    void statusIsDisabledAndAiEndpointsAre422WhileTemplatesKeepWorking() throws Exception {
        Response status = api("dana.designer").get("/workspaces/{ws}/ai/status", PURCHASING);
        assertThat(status.status()).as(status.toString()).isEqualTo(200);
        JsonNode body = status.body();
        assertThat(body.path("enabled").asBoolean()).isFalse();
        assertThat(body.path("reason").asString()).contains("not configured");
        assertThat(body.path("profiles").isEmpty()).isTrue();
        assertThat(body.path("budget").path("monthlyUsd").decimalValue()).isPositive();

        Response draft = api("dana.designer").post(Map.of("description", "Purchase approval with finance review."),
                UUID.randomUUID().toString(), "/workspaces/{ws}/ai/drafts", PURCHASING);
        assertThat(draft.status()).as(draft.toString()).isEqualTo(422);
        assertThat(draft.code()).isEqualTo("AI_NOT_CONFIGURED");

        Response created = api("dana.designer").post(Map.of("key", "rules-" + UUID.randomUUID().toString()
                .substring(0, 8), "name", "Rules only", "templateId", "purchase-approval"), "/workspaces/{ws}/workflows",
                PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        Response explain = api("dana.designer").post(Map.of("revision", 1), "/workspaces/{ws}/workflows/{id}/explain",
                PURCHASING, created.body().path("id").asString());
        assertThat(explain.code()).as(explain.toString()).isEqualTo("AI_NOT_CONFIGURED");
        // Authorization still comes first.
        assertThat(api("vic.viewer").post(Map.of("description", "Purchase approval with finance review."),
                UUID.randomUUID().toString(), "/workspaces/{ws}/ai/drafts", PURCHASING).status()).isEqualTo(403);
    }
}
