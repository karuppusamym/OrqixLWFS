package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

/**
 * Opt-in smoke test against the real OpenRouter API (spends a fraction of a cent on the economy profile). Excluded by
 * default; run with {@code ./mvnw -pl app -am verify -Dtest=AiLiveTest -Dsurefire.failIfNoSpecifiedTests=false
 * -Dorqix.ai.live=true} (optionally {@code -Dorqix.ai.envFile=<path>}). The key is read from the repository's
 * gitignored {@code .env} and never printed.
 */
@Tag("live-ai")
@EnabledIfSystemProperty(named = "orqix.ai.live", matches = "true")
class AiLiveTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void live(DynamicPropertyRegistry registry) {
        registry.add("orqix.ai.api-key", AiLiveTest::key);
        registry.add("orqix.ai.escalation-profile", () -> "");
        registry.add("orqix.ai.budget.per-request-max-usd", () -> "0.05");
        registry.add("flowable.async-executor-activate", () -> "false");
    }

    @Test
    void economyProfileDraftsAValidatedPurchaseWorkflow() throws Exception {
        Response status = api("dana.designer").get("/workspaces/{ws}/ai/status", PURCHASING);
        assertThat(status.body().path("enabled").asBoolean()).as(status.toString()).isTrue();
        Response accepted = api("dana.designer").post(Map.of("description", "Employees request equipment purchases "
                + "with a title, a business purpose, a cost center (OPS, IT or HR) and an amount in USD. Their manager "
                + "approves every request; purchases of USD 5,000 or more also need finance approval. Tell the "
                + "requester the result.", "name", "Live purchase " + UUID.randomUUID().toString().substring(0, 6)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/ai/drafts", PURCHASING);
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(202);
        JsonNode job = accepted.body();
        long until = System.currentTimeMillis() + 170_000;
        while (!"succeeded".equals(job.path("status").asString()) && !"failed".equals(job.path("status").asString())
                && System.currentTimeMillis() < until) {
            Thread.sleep(2_000);
            job = api("dana.designer").get("/workspaces/{ws}/ai/generations/{id}", PURCHASING,
                    accepted.body().path("id").asString()).body();
        }
        assertThat(job.path("status").asString()).as(job.toString()).isEqualTo("succeeded");
        JsonNode created = job.path("result");
        JsonNode generation = created.path("generation");
        assertThat(generation.path("profile").asString()).isEqualTo("economy");
        assertThat(generation.path("attempts").asInt()).isBetween(1, 3);
        assertThat(generation.path("costUsd").isNumber()).isTrue();
        assertThat(created.path("draft").path("definition").path("security").path("selfApproval").asString())
                .isEqualTo("deny");
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
