package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/** PL02 (docs/build/S54): environment stages and promotion of a release to a later stage with portable bindings. */
class ReleasePromotionTest extends RuntimeTestBase {

    private String releasePath(UUID wf, UUID release, String suffix) {
        return "/workspaces/{ws}/workflows/" + wf + "/releases/" + release + suffix;
    }

    private UUID activeRelease(UUID ws, UUID wf) throws Exception {
        JsonNode items = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/releases", ws, wf).body().path("items");
        return UUID.fromString(items.get(0).path("id").asString());
    }

    private Response check(String user, UUID ws, UUID wf, UUID release, UUID target) throws Exception {
        return api(user).post(Map.of("targetWorkspaceId", target.toString()), releasePath(wf, release, "/promotion-check"),
                ws);
    }

    private Response promote(String user, UUID ws, UUID wf, UUID release, UUID target, Integer expectedDraft)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("targetWorkspaceId", target.toString());
        if (expectedDraft != null) {
            body.put("expectedDraftRevision", expectedDraft);
        }
        return api(user).post(body, releasePath(wf, release, "/promote"), ws);
    }

    private JsonNode targetWorkflow(String key) throws Exception {
        for (JsonNode w : api("dana.designer").get("/workspaces/{ws}/workflows?q=" + key, PURCHASING).body().path("items")) {
            if (key.equals(w.path("key").asString())) {
                return w;
            }
        }
        return null;
    }

    @Test
    void promotionSavesADraftInTheLaterStageAndTheTargetPublishesItNormally() throws Exception {
        UUID source = publishPurchase(SANDBOX, null);
        UUID release = activeRelease(SANDBOX, source);
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", SANDBOX, source).body().path("key").asString();

        assertThat(api("dana.designer").get("/workspaces/{ws}/environment", SANDBOX).body().path("stage").asString())
                .isEqualTo("test");
        assertThat(api("dana.designer").get("/workspaces/{ws}/environment", PURCHASING).body().path("stage").asString())
                .isEqualTo("production");

        var report = check("dana.designer", SANDBOX, source, release, PURCHASING);
        assertThat(report.status()).as(report.toString()).isEqualTo(200);
        assertThat(report.body().path("compatible").asBoolean()).as(report.body().toString()).isTrue();
        assertThat(report.body().path("targetWorkflow").path("exists").asBoolean()).isFalse();
        assertThat(report.body().path("sourceStage").asString()).isEqualTo("test");
        assertThat(report.body().path("targetStage").asString()).isEqualTo("production");
        assertThat(report.body().path("connections").toString()).contains("procurement").contains("ready");
        assertThat(report.body().path("groups")).isNotEmpty();
        assertThat(targetWorkflow(key)).as("a check changes nothing").isNull();

        var promoted = promote("dana.designer", SANDBOX, source, release, PURCHASING, null);
        assertThat(promoted.status()).as(promoted.toString()).isEqualTo(201);
        assertThat(promoted.body().path("createdWorkflow").asBoolean()).isTrue();
        assertThat(promoted.body().path("draftRevision").asInt()).isEqualTo(1);
        UUID created = UUID.fromString(promoted.body().path("targetWorkflowId").asString());

        // Nothing is published or active in the target, and no binding or secret was copied.
        JsonNode targetView = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, created).body();
        assertThat(targetView.path("activeRelease").isNull()).isTrue();
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, created).body()
                .path("definition").path("workflowKey").asString()).isEqualTo(key);

        // The target publishes through its own normal path.
        var published = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, created);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);

        // A second promotion into the now-existing workflow must name the draft revision it expects.
        UUID target = created;
        assertThat(promote("dana.designer", SANDBOX, source, release, PURCHASING, null).status()).isEqualTo(422);
        assertThat(promote("dana.designer", SANDBOX, source, release, PURCHASING, 7).status()).isEqualTo(412);
        var again = promote("dana.designer", SANDBOX, source, release, PURCHASING, 1);
        assertThat(again.status()).as(again.toString()).isEqualTo(201);
        assertThat(again.body().path("createdWorkflow").asBoolean()).isFalse();
        assertThat(again.body().path("targetWorkflowId").asString()).isEqualTo(target.toString());
        assertThat(again.body().path("draftRevision").asInt()).isEqualTo(2);

        // History: incoming in the target, outgoing in the source (the caller sees both workspaces).
        JsonNode incoming = api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/promotions", PURCHASING, created).body()
                .path("items");
        assertThat(incoming).hasSize(2);
        assertThat(incoming.get(0).path("direction").asString()).isEqualTo("incoming");
        assertThat(incoming.get(0).path("sourceWorkspaceKey").asString()).isEqualTo("purchasing-sandbox");
        JsonNode outgoing = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/promotions", SANDBOX, source).body()
                .path("items");
        assertThat(outgoing).hasSize(2);
        assertThat(outgoing.get(0).path("direction").asString()).isEqualTo("outgoing");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'release.promoted' AND workspace_id = :ws")
                .param("ws", SANDBOX).query(Integer.class).single()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void anUnboundTargetBlocksPromotionAndCreatesNothing() throws Exception {
        UUID source = publishPurchase(SANDBOX, null);
        UUID release = activeRelease(SANDBOX, source);
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", SANDBOX, source).body().path("key").asString();
        jdbc.sql("UPDATE connection_binding SET enabled = false WHERE workspace_id = :ws AND connection_key = 'procurement'")
                .param("ws", PURCHASING).update();
        try {
            var report = check("dana.designer", SANDBOX, source, release, PURCHASING);
            assertThat(report.status()).as(report.toString()).isEqualTo(200);
            assertThat(report.body().path("compatible").asBoolean()).isFalse();
            assertThat(report.body().path("connections").toString()).contains("bindingMissing");
            assertThat(report.body().path("issues")).isNotEmpty();
            var refused = promote("dana.designer", SANDBOX, source, release, PURCHASING, null);
            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.code()).isEqualTo("WORKFLOW_INVALID");
            assertThat(targetWorkflow(key)).isNull();
        } finally {
            bindStub(PURCHASING);
        }
    }

    @Test
    void promotionNeedsExportHereEditThereALaterStageAndTheSameOrganization() throws Exception {
        UUID source = publishPurchase(SANDBOX, null);
        UUID release = activeRelease(SANDBOX, source);
        // Publishers can export but not edit in the target; viewers are not members of the sandbox at all.
        assertThat(check("paul.publisher", SANDBOX, source, release, PURCHASING).status()).isEqualTo(403);
        assertThat(promote("paul.publisher", SANDBOX, source, release, PURCHASING, null).status()).isEqualTo(403);
        assertThat(check("vic.viewer", SANDBOX, source, release, PURCHASING).status()).isEqualTo(404);
        assertThat(check("otto.outsider", SANDBOX, source, release, PURCHASING).status()).isEqualTo(404);
        // A target the caller cannot see is indistinguishable from one that does not exist.
        assertThat(check("dana.designer", SANDBOX, source, release, FACILITIES).status()).isEqualTo(404);
        assertThat(check("dana.designer", SANDBOX, source, release, UUID.randomUUID()).status()).isEqualTo(404);
        // Same workspace, and a move to an earlier or equal stage, are refused.
        assertThat(check("dana.designer", SANDBOX, source, release, SANDBOX).status()).isEqualTo(422);
        UUID inProduction = publishPurchase(PURCHASING, null);
        UUID productionRelease = activeRelease(PURCHASING, inProduction);
        var backwards = check("dana.designer", PURCHASING, inProduction, productionRelease, SANDBOX);
        assertThat(backwards.status()).isEqualTo(422);
        assertThat(backwards.body().path("detail").asString()).contains("later stage");
        // The release must belong to the workflow in the path workspace.
        assertThat(check("dana.designer", SANDBOX, source, productionRelease, PURCHASING).status()).isEqualTo(404);
    }

    @Test
    void environmentStageIsManagedByAccessAdminsWithVersionChecks() throws Exception {
        UUID original = SANDBOX;
        JsonNode before = api("wade.admin").get("/workspaces/{ws}/environment", original).body();
        assertThat(api("dana.designer").put(Map.of("stage", "staging", "expectedVersion", before.path("version").asInt()), null,
                "/workspaces/{ws}/environment", original).status()).isEqualTo(403);
        assertThat(api("otto.outsider").put(Map.of("stage", "staging", "expectedVersion", 0), null,
                "/workspaces/{ws}/environment", original).status()).isEqualTo(404);
        assertThat(api("wade.admin").put(Map.of("stage", "weekend", "expectedVersion", before.path("version").asInt()), null,
                "/workspaces/{ws}/environment", original).status()).isEqualTo(422);
        try {
            var set = api("wade.admin").put(Map.of("stage", "production", "expectedVersion", before.path("version").asInt()),
                    null, "/workspaces/{ws}/environment", original);
            assertThat(set.status()).as(set.toString()).isEqualTo(200);
            assertThat(set.body().path("explicit").asBoolean()).isTrue();
            assertThat(set.body().path("version").asInt()).isEqualTo(before.path("version").asInt() + 1);
            // The same version again is stale.
            assertThat(api("wade.admin").put(Map.of("stage", "test", "expectedVersion", before.path("version").asInt()), null,
                    "/workspaces/{ws}/environment", original).status()).isEqualTo(409);
            // Equal stages no longer allow promotion.
            UUID source = publishPurchase(SANDBOX, null);
            UUID release = activeRelease(SANDBOX, source);
            assertThat(check("dana.designer", SANDBOX, source, release, PURCHASING).status()).isEqualTo(422);
        } finally {
            int version = api("wade.admin").get("/workspaces/{ws}/environment", original).body().path("version").asInt();
            assertThat(api("wade.admin").put(Map.of("stage", "test", "expectedVersion", version), null,
                    "/workspaces/{ws}/environment", original).status()).isEqualTo(200);
        }
    }
}
