package com.orqix.runtime;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.integration.MicrosoftGraphDocuments;
import com.orqix.integration.internal.DocumentSnapshots;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.support.GraphStub;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.node.ObjectNode;

/** Snapshot capture deduplication, immutable retrieval, workspace isolation, bounded storage, and expiry. */
class DocumentSnapshotWorkflowTest extends RuntimeTestBase {
    @Autowired private OrqixProperties properties;
    @Autowired private DocumentSnapshots snapshots;

    private String register() throws Exception {
        String key = "documents-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode definition = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/connections/microsoft-documents-v2.json")));
        definition.put("key", key).put("version", 1);
        assertThat(api("wade.admin").post(Map.of("definition", definition),
                "/workspaces/{ws}/connections/{key}/versions", PURCHASING, key).status()).isEqualTo(201);
        Files.createDirectories(properties.secrets().dir());
        Files.writeString(properties.secrets().dir().resolve("graph-snapshot-stub"), "graph-stub-only");
        assertThat(api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/graph", "enabled", true,
                "auth", Map.of("type", "bearerToken", "secretRef", "graph-snapshot-stub")), null,
                "/workspaces/{ws}/connections/{key}/binding", PURCHASING, key).status()).isEqualTo(200);
        return key;
    }

    private Response capture(String key) throws Exception {
        return api("wade.admin").post(Map.of("version", 1, "operation", "pinLatest",
                "input", Map.of("driveId", "drive", "itemId", "item")),
                "/workspaces/{ws}/connections/{key}/test", PURCHASING, key);
    }

    @Test
    void captureIsDeduplicatedAndChangedSourceNeverReplacesSnapshotBytes() throws Exception {
        String key = register();
        try {
            Response captured = capture(key);
            assertThat(captured.status()).as(captured.toString()).isEqualTo(200);
            assertThat(captured.body().path("outcome").asString()).as(captured.toString()).isEqualTo("succeeded");
            String id = captured.body().path("output").path("snapshotId").asString();
            assertThat(capture(key).body().path("output").path("snapshotId").asString()).isEqualTo(id);
            GraphStub.current("Later source content.");
            int requests = GraphStub.requests();
            var snapshot = snapshots.load(PURCHASING, key, UUID.fromString(id), "drive", "item");
            assertThat(new String(snapshot.content(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("Approved text.");
            assertThat(snapshot.metadata().path("contentSha256").asString()).isEqualTo(Json.sha256Hex("Approved text."));
            Response extraction = api("wade.admin").post(Map.of("version", 1, "operation", "extractSnapshot",
                    "input", Map.of("driveId", "drive", "itemId", "item", "snapshotId", id)),
                    "/workspaces/{ws}/connections/{key}/test", PURCHASING, key);
            assertThat(extraction.body().path("outcome").asString()).as(extraction.toString()).isEqualTo("succeeded");
            assertThat(GraphStub.requests()).isEqualTo(requests);
            assertThatThrownBy(() -> snapshots.load(SANDBOX, key, UUID.fromString(id), "drive", "item"))
                    .isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                            e -> assertThat(e.code()).isEqualTo("DOCUMENT_SNAPSHOT_NOT_FOUND"));
            assertThatThrownBy(() -> snapshots.load(PURCHASING, "another-connection", UUID.fromString(id), "drive", "item"))
                    .isInstanceOf(MicrosoftGraphDocuments.Failure.class);
        } finally { Files.deleteIfExists(properties.secrets().dir().resolve("graph-snapshot-stub")); }
    }

    @Test
    void detectsCaptureRaceInsteadOfPinningUncertainLatestContent() throws Exception {
        String key = register();
        try {
            GraphStub.race();
            Response capture = capture(key);
            assertThat(capture.body().path("outcome").asString()).isEqualTo("failed");
            assertThat(capture.body().path("errorCode").asString()).isEqualTo("DOCUMENT_CHANGED_DURING_CAPTURE");
            assertThat(jdbc.sql("SELECT count(*) FROM document_snapshot WHERE workspace_id=:ws AND connection_key=:key")
                    .param("ws", PURCHASING).param("key", key).query(Long.class).single()).isZero();
        } finally { Files.deleteIfExists(properties.secrets().dir().resolve("graph-snapshot-stub")); }
    }

    @Test
    void snapshotWorkflowCapturesExtractsAndApprovesWithImmutableHash() throws Exception {
        String connection = register();
        try {
            UUID workflow = publishPurchase(PURCHASING, d -> {
                try {
                    ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/document-snapshot-review.json")));
                    String key = d.path("workflowKey").asString();
                    ((ObjectNode) example.path("dependencies").path("connections").get(0)).put("key", connection).put("definitionVersion", 1);
                    for (var node : example.path("nodes")) {
                        if ("http".equals(node.path("type").asString())) { ((ObjectNode) node.path("config")).put("connectionRef", connection); }
                    }
                    d.removeAll(); example.properties().forEach(e -> d.set(e.getKey(), e.getValue())); d.put("workflowKey", key);
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", Map.of("driveId", "drive", "itemId", "item")),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
            assertThat(started.status()).as(started.toString()).isEqualTo(201);
            UUID id = UUID.fromString(started.body().path("id").asString());
            awaitLifecycle(PURCHASING, id, "waiting", "needsAttention");
            assertThat(lifecycle(PURCHASING, id)).isEqualTo("waiting");
            UUID snapshotId = jdbc.sql("SELECT id FROM document_snapshot WHERE workspace_id=:ws AND connection_key=:key")
                    .param("ws", PURCHASING).param("key", connection).query(UUID.class).single();
            jdbc.sql("UPDATE document_snapshot SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id")
                    .param("id", snapshotId).update();
            assertThat(snapshots.load(PURCHASING, connection, snapshotId, "drive", "item").id()).isEqualTo(snapshotId);
            ObjectNode probe = json.object().put("driveId", "drive").put("itemId", "item").put("eTag", "purge-probe");
            snapshots.capture(PURCHASING, connection, probe, new byte[] {1}, "text/plain");
            assertThat(snapshots.load(PURCHASING, connection, snapshotId, "drive", "item").id()).isEqualTo(snapshotId);
            approve("mani.manager", PURCHASING, id, "review");
            assertThat(run(PURCHASING, id).path("output").path("documentHash").asString()).isEqualTo(Json.sha256Hex("Approved text."));
            assertThat(run(PURCHASING, id).path("output").path("documentSnapshot").asString()).isNotBlank();
            snapshots.capture(PURCHASING, connection, probe.put("eTag", "purge-after-completion"), new byte[] {1}, "text/plain");
            assertThatThrownBy(() -> snapshots.load(PURCHASING, connection, snapshotId, "drive", "item"))
                    .isInstanceOf(MicrosoftGraphDocuments.Failure.class);
        } finally { Files.deleteIfExists(properties.secrets().dir().resolve("graph-snapshot-stub")); }
    }

    @Test
    void expiredUnpinnedSnapshotsAreUnavailableAndWorkspaceCountIsBounded() {
        String key = "quota-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode metadata = json.object().put("driveId", "drive").put("itemId", "item").put("eTag", "old");
        var first = snapshots.capture(FACILITIES, key, metadata, new byte[] {1}, "text/plain");
        jdbc.sql("UPDATE document_snapshot SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id").param("id", first.id()).update();
        assertThatThrownBy(() -> snapshots.load(FACILITIES, key, first.id(), "drive", "item"))
                .isInstanceOf(MicrosoftGraphDocuments.Failure.class);
        for (int i = 0; i < DocumentSnapshots.MAX_COUNT; i++) { snapshots.capture(FACILITIES, key, metadata.deepCopy().put("eTag", "tag-" + i), new byte[] {1}, "text/plain"); }
        assertThatThrownBy(() -> snapshots.capture(FACILITIES, key, metadata.deepCopy().put("eTag", "over-limit"), new byte[] {1}, "text/plain"))
                .isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class, e -> assertThat(e.code()).isEqualTo("DOCUMENT_SNAPSHOT_QUOTA_EXCEEDED"));
        jdbc.sql("DELETE FROM document_snapshot WHERE workspace_id=:ws AND connection_key=:key").param("ws", FACILITIES).param("key", key).update();
    }
}
