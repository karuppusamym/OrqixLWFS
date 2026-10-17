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

import tools.jackson.databind.node.ObjectNode;

/** A requester can submit an approved file link; the engine resolves and pins it before document review. */
class DocumentLinkWorkflowTest extends RuntimeTestBase {
    @Autowired private OrqixProperties properties;
    @Test
    void submittedFileUrlResolvesToApprovedDriveThenCapturesExtractsAndApproves() throws Exception {
        String connection = "links-" + UUID.randomUUID().toString().substring(0, 8);
        Path secret = properties.secrets().dir().resolve("graph-link-stub");
        Files.createDirectories(secret.getParent()); Files.writeString(secret, "graph-stub-only");
        try {
            ObjectNode definition = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/connections/microsoft-documents-v3.json")));
            definition.put("key", connection).put("version", 1);
            var registered = api("wade.admin").post(Map.of("definition", definition), "/workspaces/{ws}/connections/{key}/versions", PURCHASING, connection);
            assertThat(registered.status()).as(registered.toString()).isEqualTo(201);
            assertThat(api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/graph", "enabled", true,
                    "auth", Map.of("type", "bearerToken", "secretRef", "graph-link-stub")), null,
                    "/workspaces/{ws}/connections/{key}/binding", PURCHASING, connection).status()).isEqualTo(200);
            var history = api("wade.admin").post(Map.of("version", 1, "operation", "listVersions", "input", Map.of("driveId", "drive", "itemId", "item")),
                    "/workspaces/{ws}/connections/{key}/test", PURCHASING, connection);
            assertThat(history.body().path("outcome").asString()).as(history.toString()).isEqualTo("succeeded");
            assertThat(history.body().path("output").path("versionCount").asInt()).isEqualTo(2);
            UUID workflow = publishPurchase(PURCHASING, d -> {
                try {
                    ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/document-link-review.json")));
                    String key = d.path("workflowKey").asString();
                    ((ObjectNode) example.path("dependencies").path("connections").get(0)).put("key", connection).put("definitionVersion", 1);
                    for (var node : example.path("nodes")) { if ("http".equals(node.path("type").asString())) { ((ObjectNode) node.path("config")).put("connectionRef", connection); } }
                    d.removeAll(); example.properties().forEach(e -> d.set(e.getKey(), e.getValue())); d.put("workflowKey", key);
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            var started = api("rhea.requester").post(Map.of("workflowId", workflow, "input",
                    Map.of("shareUrl", "https://tenant.sharepoint.com/sites/Team/policy.txt?e=sharing-token")),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
            assertThat(started.status()).as(started.toString()).isEqualTo(201);
            UUID runId = UUID.fromString(started.body().path("id").asString());
            awaitLifecycle(PURCHASING, runId, "waiting", "needsAttention");
            assertThat(lifecycle(PURCHASING, runId)).isEqualTo("waiting");
            approve("mani.manager", PURCHASING, runId, "review");
            assertThat(run(PURCHASING, runId).path("output").path("documentHash").asString()).isEqualTo(Json.sha256Hex("Approved text."));
            assertThat(run(PURCHASING, runId).path("output").path("documentSnapshot").asString()).isNotBlank();
            String resolved = jdbc.sql("SELECT result::text FROM effect_journal WHERE run_id=:id AND node_id='resolve'")
                    .param("id", runId).query(String.class).single();
            assertThat(resolved).doesNotContain("sharing-token", "must-not-return");
            assertThat(jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id=:id AND state='succeeded'")
                    .param("id", runId).query(Long.class).single()).isEqualTo(3);
        } finally { Files.deleteIfExists(secret); }
    }
}
