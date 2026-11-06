package com.orqix.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Model endpoints and AI task profiles of the S134 tests, all pointing at {@link AiTaskStub} (never a real provider),
 * set as system properties so every test context of the JVM runs AI jobs the same way: AI jobs are claimed by whichever
 * context's worker polls first. Credentials are test values in the test secrets directory (S132 file provider).
 */
public final class AiTaskFixtures {

    /** Test-only AES key sealing AI job input and turn state (not a real secret). */
    public static final String TURN_STATE_KEY = Base64.getEncoder().encodeToString(
            "orqix-test-turn-state-key-32byte".getBytes(StandardCharsets.UTF_8));

    private static boolean done;

    private AiTaskFixtures() {
    }

    static synchronized void configure() {
        if (done) {
            return;
        }
        done = true;
        String base = AiTaskStub.baseUrl();
        endpoint("stub-openai", "openaiCompatible", base + "/openai", "ai-stub-key", "eu");
        endpoint("stub-anthropic", "anthropic", base + "/anthropic", "ai-stub-key", "eu");
        endpoint("stub-bedrock", "bedrock", base + "/bedrock", "ai-stub-aws", "eu");
        System.setProperty("orqix.ai.model-providers.endpoints.stub-bedrock.region", "eu-central-1");
        endpoint("stub-azure", "azureOpenai", base + "/azure", "ai-stub-key", "eu");
        System.setProperty("orqix.ai.model-providers.endpoints.stub-azure.deployments.gpt-test", "dep-gpt");
        endpoint("stub-gemini", "vertex", base + "/gemini", "ai-stub-key", "us");
        endpoint("stub-private", "openaiCompatible", base + "/openai", "", "private");
        profile("task-openai", "stub-openai", "test/task-model");
        profile("task-anthropic", "stub-anthropic", "claude-haiku-5-5");
        profile("task-bedrock", "stub-bedrock", "anthropic.claude-haiku-5-5");
        profile("task-azure", "stub-azure", "gpt-test");
        profile("task-gemini", "stub-gemini", "gemini-test");
        profile("task-private", "stub-private", "local/llama");
        System.setProperty("orqix.ai.model-providers.profiles.task-pricey.provider", "stub-openai");
        System.setProperty("orqix.ai.model-providers.profiles.task-pricey.model", "test/pricey");
        System.setProperty("orqix.ai.model-providers.profiles.task-pricey.input-per-million", "50");
        System.setProperty("orqix.ai.model-providers.profiles.task-pricey.output-per-million", "100");
        System.setProperty("orqix.ai.tasks.poll-interval", "200ms");
        System.setProperty("orqix.ai.tasks.recover-interval", "1s");
        writeSecret("ai-stub-key", AiTaskStub.KEY);
        writeSecret("ai-stub-aws", "{\"accessKeyId\":\"AKIDTESTONLY\",\"secretAccessKey\":\"test-secret-not-real\"}");
        writeSecret("ai-turn-state-key", TURN_STATE_KEY);
    }

    private static void endpoint(String id, String kind, String baseUrl, String secretRef, String residency) {
        String prefix = "orqix.ai.model-providers.endpoints." + id + ".";
        System.setProperty(prefix + "kind", kind);
        System.setProperty(prefix + "base-url", baseUrl);
        System.setProperty(prefix + "secret-ref", secretRef);
        System.setProperty(prefix + "residency[0]", residency);
        System.setProperty(prefix + "timeout", "5s");
    }

    private static void profile(String id, String endpoint, String model) {
        String prefix = "orqix.ai.model-providers.profiles." + id + ".";
        System.setProperty(prefix + "provider", endpoint);
        System.setProperty(prefix + "model", model);
        System.setProperty(prefix + "label", id);
        System.setProperty(prefix + "input-per-million", "0.10");
        System.setProperty(prefix + "output-per-million", "0.20");
    }

    private static void writeSecret(String name, String value) {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "orqix-test-secrets");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
