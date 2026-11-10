package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.orqix.support.Fixtures;
import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-S02 release bundles (docs/build/S147): a workflow published with every component it pins as one bundle, shown in
 * the release and its evidence pack, and promoted from the sandbox (test stage) to purchasing (production) as a unit.
 */
class ReleaseBundleApiTest extends IntegrationTestBase {

    @Autowired JdbcClient jdbc;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The shareable part of the purchase template's amount-policy table (equal to the template's own copy). */
    private static ObjectNode policyContent() {
        ObjectNode table = ((ObjectNode) Fixtures.purchaseTemplate().path("decisions").get(0)).deepCopy();
        table.remove("key");
        table.remove("version");
        return table;
    }

    private record Workflow(UUID id, String key) {
    }

    /** A sandbox workflow whose amount-policy table is linked to a new shared decision; draft revision 2. */
    private Workflow sandboxWorkflow(String assetKey) throws Exception {
        var asset = api("dana.designer").post(Map.of("key", assetKey, "name", "Bundle policy " + assetKey, "content",
                policyContent()), "/workspaces/{ws}/shared-assets/decision", SANDBOX);
        assertThat(asset.status()).as(asset.toString()).isEqualTo(201);
        String key = unique("bd");
        var created = api("dana.designer").post(Map.of("key", key, "name", "Bundle " + key, "templateId", "purchase-approval"),
                "/workspaces/{ws}/workflows", SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", SANDBOX, id)
                .body().path("definition");
        ObjectNode dependencies = (ObjectNode) definition.path("dependencies");
        ArrayNode assets = dependencies.has("assets") ? (ArrayNode) dependencies.path("assets") : dependencies.putArray("assets");
        assets.addObject().put("kind", "decision").put("key", assetKey).put("version", 1).put("target", "amount-policy");
        var saved = api("dana.designer").put(Map.of("definition", definition), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", SANDBOX, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return new Workflow(id, key);
    }

    private static JsonNode component(JsonNode bundle, String type) {
        for (JsonNode c : bundle.path("components")) {
            if (type.equals(c.path("type").asString())) {
                return c;
            }
        }
        return null;
    }

    private static JsonNode item(JsonNode plan, String type) {
        for (JsonNode c : plan.path("items")) {
            if (type.equals(c.path("type").asString())) {
                return c;
            }
        }
        return null;
    }

    private Response promote(String user, String path, UUID bundle, Integer expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("targetWorkspaceId", PURCHASING.toString());
        if (expected != null) {
            body.put("expectedDraftRevision", expected);
        }
        return api(user).post(body, "/workspaces/{ws}/bundles/{id}/" + path, SANDBOX, bundle);
    }

    private Boolean approvalGateOff() {
        Boolean previous = jdbc.sql("SELECT promotion_approval FROM workspace_environment WHERE workspace_id = :ws")
                .param("ws", PURCHASING).query(Boolean.class).optional().orElse(null);
        if (Boolean.TRUE.equals(previous)) {
            jdbc.sql("UPDATE workspace_environment SET promotion_approval = false WHERE workspace_id = :ws")
                    .param("ws", PURCHASING).update();
        }
        return previous;
    }

    private void restoreGate(Boolean previous) {
        if (Boolean.TRUE.equals(previous)) {
            jdbc.sql("UPDATE workspace_environment SET promotion_approval = true WHERE workspace_id = :ws")
                    .param("ws", PURCHASING).update();
        }
    }

    @Test
    void aBundleIsPublishedWithItsPinsShownInTheEvidencePackAndPromotedAsAUnit() throws Exception {
        String assetKey = unique("bundle-policy");
        Workflow wf = sandboxWorkflow(assetKey);
        String key = UUID.randomUUID().toString();
        var published = api("dana.designer").post(Map.of("draftRevision", 2, "acknowledgeWarnings", true), key,
                "/workspaces/{ws}/workflows/{id}/bundles", SANDBOX, wf.id());
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        JsonNode bundle = published.body().path("bundle");
        UUID bundleId = UUID.fromString(bundle.path("id").asString());
        String releaseId = published.body().path("release").path("id").asString();
        assertThat(bundle.path("releaseId").asString()).isEqualTo(releaseId);
        assertThat(bundle.path("bundleHash").asString()).matches("^[0-9a-f]{64}$");
        assertThat(component(bundle, "workflow").path("key").asString()).isEqualTo(wf.key());
        assertThat(component(bundle, "ruleTable").path("key").asString()).isEqualTo("amount-policy");
        assertThat(component(bundle, "requestForm")).isNotNull();
        JsonNode shared = component(bundle, "sharedDecision");
        assertThat(shared.path("key").asString()).isEqualTo(assetKey);
        assertThat(shared.path("version").asString()).isEqualTo("1");
        assertThat(shared.path("sha256").asString()).matches("^[0-9a-f]{64}$");
        assertThat(component(bundle, "connection").path("key").asString()).isEqualTo("procurement");
        assertThat(bundle.path("source").isNull()).isTrue();

        // Replay, read back, list.
        var replay = api("dana.designer").post(Map.of("draftRevision", 2, "acknowledgeWarnings", true), key,
                "/workspaces/{ws}/workflows/{id}/bundles", SANDBOX, wf.id());
        assertThat(replay.status()).as(replay.toString()).isEqualTo(200);
        assertThat(replay.body().path("bundle").path("id").asString()).isEqualTo(bundleId.toString());
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}/releases/{r}/bundle", SANDBOX, wf.id(), releaseId)
                .body().path("bundleHash").asString()).isEqualTo(bundle.path("bundleHash").asString());
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}/bundles", SANDBOX, wf.id()).body()
                .path("items")).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM release_bundle WHERE release_id = :r").param("r", UUID.fromString(releaseId))
                .query(Integer.class).single()).isEqualTo(1);

        Boolean gate = approvalGateOff();
        try {
            // The plan: nothing changes in the target; the shared decision would be created there.
            assertThat(promote("olga.operator", "promotion-check", bundleId, null).status()).isEqualTo(403);
            var plan = promote("dana.designer", "promotion-check", bundleId, null);
            assertThat(plan.status()).as(plan.toString()).isEqualTo(200);
            assertThat(plan.body().path("promotable").asBoolean()).as(plan.body().toString()).isTrue();
            assertThat(plan.body().path("targetStage").asString()).isEqualTo("production");
            assertThat(item(plan.body(), "sharedDecision").path("action").asString()).isEqualTo("create");
            assertThat(item(plan.body(), "connection").path("action").asString()).isEqualTo("ready");
            assertThat(item(plan.body(), "workflow").path("action").asString()).isEqualTo("create");
            assertThat(jdbc.sql("SELECT count(*) FROM decision_asset WHERE workspace_id = :ws AND key = :k")
                    .param("ws", PURCHASING).param("k", assetKey).query(Integer.class).single()).isZero();

            // Promote as a unit: the decision is created in the target and the draft pins its version there.
            var promoted = promote("dana.designer", "promote", bundleId, null);
            assertThat(promoted.status()).as(promoted.toString()).isEqualTo(201);
            assertThat(promoted.body().path("createdWorkflow").asBoolean()).isTrue();
            UUID target = UUID.fromString(promoted.body().path("targetWorkflowId").asString());
            assertThat(jdbc.sql("SELECT latest_version FROM decision_asset WHERE workspace_id = :ws AND key = :k")
                    .param("ws", PURCHASING).param("k", assetKey).query(Integer.class).single()).isEqualTo(1);
            JsonNode draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, target).body()
                    .path("definition");
            assertThat(draft.path("dependencies").path("assets").get(0).path("key").asString()).isEqualTo(assetKey);
            assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, target).body()
                    .path("activeRelease").isNull()).as("promotion never publishes").isTrue();

            // The target publishes the bundle through its own gates: same content, so the same bundle hash.
            var there = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/bundles", PURCHASING, target);
            assertThat(there.status()).as(there.toString()).isEqualTo(201);
            JsonNode source = there.body().path("bundle").path("source");
            assertThat(source.path("bundleId").asString()).isEqualTo(bundleId.toString());
            assertThat(source.path("workspaceKey").asString()).isEqualTo("purchasing-sandbox");
            assertThat(source.path("sameContent").asBoolean()).as(there.body().toString()).isTrue();
            assertThat(there.body().path("bundle").path("bundleHash").asString()).isEqualTo(bundle.path("bundleHash").asString());

            // A second promotion reuses the decision version the target now has and names the expected draft.
            var again = promote("dana.designer", "promotion-check", bundleId, 1);
            assertThat(item(again.body(), "sharedDecision").path("action").asString()).isEqualTo("reuse");
            assertThat(item(again.body(), "workflow").path("action").asString()).isEqualTo("newVersion");
            assertThat(promote("dana.designer", "promote", bundleId, null).status()).isEqualTo(422);
            assertThat(promote("dana.designer", "promote", bundleId, 9).status()).isEqualTo(412);
            var second = promote("dana.designer", "promote", bundleId, 1);
            assertThat(second.status()).as(second.toString()).isEqualTo(201);
            assertThat(second.body().path("draftRevision").asInt()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT latest_version FROM decision_asset WHERE workspace_id = :ws AND key = :k")
                    .param("ws", PURCHASING).param("k", assetKey).query(Integer.class).single()).isEqualTo(1);
        } finally {
            restoreGate(gate);
        }

        // The evidence pack of the source release shows the bundle and both promotions.
        var pack = api("dana.designer").get("/workspaces/{ws}/workflows/{wf}/releases/{rel}/evidence-pack?format=json",
                SANDBOX, wf.id(), releaseId);
        assertThat(pack.status()).as(pack.toString()).isEqualTo(200);
        assertThat(pack.body().path("bundle").path("bundleHash").asString()).isEqualTo(bundle.path("bundleHash").asString());
        assertThat(pack.body().path("bundle").path("promotions")).hasSize(2);
        assertThat(pack.body().path("controls").path("publishedAsBundle").asBoolean()).isTrue();
        assertThat(pack.body().path("bundle").path("components").toString()).contains(assetKey);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND action = 'release.bundle.received'"
                + " AND metadata->>'bundleHash' = :h").param("ws", PURCHASING).param("h", bundle.path("bundleHash").asString())
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void aBlockedComponentLeavesTheTargetUnchanged() throws Exception {
        String assetKey = unique("blocked-policy");
        Workflow wf = sandboxWorkflow(assetKey);
        var published = api("dana.designer").post(Map.of("draftRevision", 2, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/bundles", SANDBOX, wf.id());
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        UUID bundleId = UUID.fromString(published.body().path("bundle").path("id").asString());
        assertThat(api("dana.designer").post(Map.of("targetWorkspaceId", UUID.randomUUID().toString()),
                "/workspaces/{ws}/bundles/{id}/promotion-check", SANDBOX, bundleId).status()).isEqualTo(404);
        assertThat(api("dana.designer").post(Map.of("targetWorkspaceId", PURCHASING.toString()),
                "/workspaces/{ws}/bundles/{id}/promotion-check", SANDBOX, UUID.randomUUID()).status()).isEqualTo(404);

        Boolean gate = approvalGateOff();
        jdbc.sql("UPDATE connection_binding SET enabled = false WHERE workspace_id = :ws AND connection_key = 'procurement'")
                .param("ws", PURCHASING).update();
        try {
            var plan = promote("dana.designer", "promotion-check", bundleId, null);
            assertThat(plan.status()).as(plan.toString()).isEqualTo(200);
            assertThat(plan.body().path("promotable").asBoolean()).isFalse();
            JsonNode connection = item(plan.body(), "connection");
            assertThat(connection.path("action").asString()).isEqualTo("blocked");
            assertThat(connection.path("problem").asString()).contains("binding");
            var refused = promote("dana.designer", "promote", bundleId, null);
            assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
            assertThat(jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id = :ws AND key = :k")
                    .param("ws", PURCHASING).param("k", wf.key()).query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM decision_asset WHERE workspace_id = :ws AND key = :k")
                    .param("ws", PURCHASING).param("k", assetKey).query(Integer.class).single()).isZero();
        } finally {
            jdbc.sql("UPDATE connection_binding SET enabled = true WHERE workspace_id = :ws AND connection_key = 'procurement'")
                    .param("ws", PURCHASING).update();
            restoreGate(gate);
        }
    }
}
