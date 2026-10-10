package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

/** Published document read flows use the ordinary effect journal, workspace permissions, and approval services. */
class DocumentWorkflowTest extends RuntimeTestBase {
    @Autowired private OrqixProperties properties;

    @Test
    void readsPinnedDocumentThenApprovesWithVersionAndHashInTheOutput() throws Exception {
        Path secret = properties.secrets().dir().resolve("graph-workflow-stub");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "graph-stub-only");
        try {
            var definition = json.parse(Files.readString(Path.of("../../examples/connections/microsoft-documents-v1.json")));
            var registered = api("wade.admin").post(Map.of("definition", definition),
                    "/workspaces/{ws}/connections/{key}/versions", PURCHASING, "microsoft-documents");
            assertThat(registered.status()).as(registered.toString()).isEqualTo(201);
            var binding = api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/graph", "enabled", true,
                    "auth", Map.of("type", "bearerToken", "secretRef", "graph-workflow-stub")), null,
                    "/workspaces/{ws}/connections/{key}/binding", PURCHASING, "microsoft-documents");
            assertThat(binding.status()).as(binding.toString()).isEqualTo(200);
            UUID workflow = publishPurchase(PURCHASING, d -> {
                try {
                    var example = json.parse(Files.readString(Path.of("../../examples/document-review.json")));
                    String key = d.path("workflowKey").asString();
                    d.removeAll();
                    example.properties().forEach(e -> d.set(e.getKey(), e.getValue()));
                    d.put("workflowKey", key);
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            var started = api("rhea.requester").post(Map.of("workflowId", workflow, "input",
                    Map.of("driveId", "drive", "itemId", "item", "versionId", "1.0")), UUID.randomUUID().toString(),
                    "/workspaces/{ws}/runs", PURCHASING);
            assertThat(started.status()).as(started.toString()).isEqualTo(201);
            UUID runId = UUID.fromString(started.body().path("id").asString());
            awaitLifecycle(PURCHASING, runId, "waiting", "needsAttention");
            assertThat(lifecycle(PURCHASING, runId)).isEqualTo("waiting");
            approve("mani.manager", PURCHASING, runId, "review");
            assertThat(lifecycle(PURCHASING, runId)).isEqualTo("completed");
            var output = run(PURCHASING, runId).path("output");
            assertThat(output.path("documentName").asString()).isEqualTo("policy.txt");
            assertThat(output.path("documentVersion").asString()).isEqualTo("1.0");
            assertThat(output.path("documentHash").asString()).isEqualTo(Json.sha256Hex("Approved text."));
            assertThat(jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id = :run AND state = 'succeeded'")
                    .param("run", runId).query(Long.class).single()).isEqualTo(1);
        } finally {
            Files.deleteIfExists(secret);
        }
    }
}
