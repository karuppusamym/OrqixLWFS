package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.Fixtures;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * PL01 shared assets (docs/build/S61): shared decisions, document templates and connections with owners, versioned
 * references from workflows that are checked (never silently resolved) at validation and publish, where-used and
 * search. References and owners never grant access.
 */
class SharedAssetsApiTest extends RuntimeTestBase {

    private static final String ASSETS = "/workspaces/{ws}/shared-assets";

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The shareable part of the purchase template's amount-policy table. */
    private ObjectNode policyContent(int threshold) {
        ObjectNode table = ((ObjectNode) Fixtures.purchaseTemplate().path("decisions").get(0)).deepCopy();
        table.remove("key");
        table.remove("version");
        ((ObjectNode) table.path("rows").get(0).path("when")).put("value", threshold);
        return table;
    }

    private JsonNode createDecision(String key, int threshold) throws Exception {
        var created = api("dana.designer").post(Map.of("key", key, "name", "Policy " + key, "content", policyContent(threshold)),
                ASSETS + "/decision", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body();
    }

    private record Draft(UUID id, int revision, ObjectNode definition) {
    }

    private Draft workflow(String user, UUID ws) throws Exception {
        return workflow(api(user), ws);
    }

    private Draft workflow(Api api, UUID ws) throws Exception {
        var created = api.post(Map.of("key", unique("sa"), "name", "Shared " + unique("w"), "templateId", "purchase-approval"),
                "/workspaces/{ws}/workflows", ws);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        return new Draft(id, 1, (ObjectNode) api.get("/workspaces/{ws}/workflows/{id}/draft", ws, id).body().path("definition"));
    }

    private Draft save(String user, UUID ws, Draft draft) throws Exception {
        return save(api(user), ws, draft);
    }

    private Draft save(Api api, UUID ws, Draft draft) throws Exception {
        var saved = api.put(Map.of("definition", draft.definition()), "\"r" + draft.revision() + "\"",
                "/workspaces/{ws}/workflows/{id}/draft", ws, draft.id());
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return new Draft(draft.id(), draft.revision() + 1, draft.definition());
    }

    private static void link(ObjectNode definition, String kind, String key, int version, String target) {
        ObjectNode dependencies = (ObjectNode) definition.path("dependencies");
        ArrayNode assets = dependencies.has("assets") ? (ArrayNode) dependencies.path("assets") : dependencies.putArray("assets");
        assets.addObject().put("kind", kind).put("key", key).put("version", version).put("target", target);
    }

    private static void setThreshold(ObjectNode definition, int threshold) {
        ((ObjectNode) definition.path("decisions").get(0).path("rows").get(0).path("when")).put("value", threshold);
    }

    private JsonNode validate(String user, UUID ws, Draft draft) throws Exception {
        var report = api(user).post(Map.of("revision", draft.revision()), "/workspaces/{ws}/workflows/{id}/validate", ws, draft.id());
        assertThat(report.status()).as(report.toString()).isEqualTo(200);
        return report.body();
    }

    /** Issue codes about shared asset references (paths under /dependencies/assets or a named connection). */
    private static List<String> assetCodes(JsonNode report, String pathPrefix) {
        List<String> codes = new ArrayList<>();
        for (JsonNode issue : report.path("issues")) {
            if (issue.path("path").asString("").startsWith(pathPrefix)) {
                codes.add(issue.path("code").asString() + ":" + issue.path("severity").asString());
            }
        }
        return codes;
    }

    private Response publish(Draft draft, boolean acknowledge) throws Exception {
        return api("paul.publisher").post(Map.of("draftRevision", draft.revision(), "acknowledgeWarnings", acknowledge),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, draft.id());
    }

    @Test
    void decisionReferenceIsCheckedAtValidationAndPublishAndNeverSilentlyFollowsTheAsset() throws Exception {
        String key = unique("limit");
        createDecision(key, 5000);
        Draft draft = workflow("dana.designer", PURCHASING);
        link(draft.definition(), "decision", key, 1, "amount-policy");
        draft = save("dana.designer", PURCHASING, draft);

        // The pinned copy equals version 1: no finding, publishable.
        JsonNode clean = validate("dana.designer", PURCHASING, draft);
        assertThat(assetCodes(clean, "/dependencies/assets")).isEmpty();
        assertThat(clean.path("publishable").asBoolean()).isTrue();

        // A newer version is a warning the publisher must acknowledge; the release keeps version 1.
        var v2 = api("dana.designer").post(Map.of("expectedVersion", 1, "content", policyContent(7000), "changeNote", "Raise"),
                ASSETS + "/decision/{key}/versions", PURCHASING, key);
        assertThat(v2.status()).as(v2.toString()).isEqualTo(201);
        assertThat(v2.body().path("asset").path("latestVersion").asInt()).isEqualTo(2);
        assertThat(v2.body().path("versions").get(0).path("content").path("rows").get(0).path("when").path("value").asInt())
                .isEqualTo(7000);
        assertThat(assetCodes(validate("vic.viewer", PURCHASING, draft), "/dependencies/assets"))
                .containsExactly("WF_ASSET_STALE:warning");
        var unacknowledged = publish(draft, false);
        assertThat(unacknowledged.status()).as(unacknowledged.toString()).isEqualTo(422);
        assertThat(unacknowledged.code()).isEqualTo("WARNINGS_NOT_ACKNOWLEDGED");
        assertThat(unacknowledged.toString()).contains("WF_ASSET_STALE");
        var published = publish(draft, true);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        JsonNode release = api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/releases/{rid}", PURCHASING, draft.id(),
                published.body().path("id").asString()).body();
        assertThat(release.path("manifest").path("assets").get(0).path("version").asInt()).isEqualTo(1);
        assertThat(release.path("definition").path("decisions").get(0).path("rows").get(0).path("when").path("value").asInt())
                .isEqualTo(5000);
        assertThat(release.path("warnings").toString()).contains("WF_ASSET_STALE");

        // Where-used: the draft and the active release pin version 1 of 2, so the usage is stale.
        var usage = api("vic.viewer").get(ASSETS + "/decision/{key}/usage", PURCHASING, key);
        assertThat(usage.status()).as(usage.toString()).isEqualTo(200);
        assertThat(usage.body().path("latestVersion").asInt()).isEqualTo(2);
        JsonNode used = usage.body().path("items").get(0);
        assertThat(used.path("workflowId").asString()).isEqualTo(draft.id().toString());
        assertThat(used.path("draftVersion").asInt()).isEqualTo(1);
        assertThat(used.path("activeReleaseVersion").asInt()).isEqualTo(1);
        assertThat(used.path("stale").asBoolean()).isTrue();

        // Editing the copy while still linked is reported as a difference and blocks publish.
        setThreshold(draft.definition(), 6000);
        draft = save("dana.designer", PURCHASING, draft);
        JsonNode diverged = validate("dana.designer", PURCHASING, draft);
        assertThat(assetCodes(diverged, "/dependencies/assets")).contains("WF_ASSET_DIVERGED:blocker");
        assertThat(diverged.path("publishable").asBoolean()).isFalse();
        var blocked = publish(draft, true);
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.code()).isEqualTo("WORKFLOW_INVALID");
        assertThat(blocked.toString()).contains("WF_ASSET_DIVERGED");

        // Updating the copy and the pin to version 2 is clean again.
        setThreshold(draft.definition(), 7000);
        ((ObjectNode) draft.definition().path("dependencies").path("assets").get(0)).put("version", 2);
        draft = save("dana.designer", PURCHASING, draft);
        assertThat(assetCodes(validate("dana.designer", PURCHASING, draft), "/dependencies/assets")).isEmpty();

        // Archiving the asset blocks validation and publish; the published release is untouched.
        int revision = api("vic.viewer").get(ASSETS + "/decision/{key}", PURCHASING, key).body().path("asset").path("revision").asInt();
        var archived = api("dana.designer").put(Map.of("archived", true, "expectedRevision", revision), null,
                ASSETS + "/decision/{key}/archived", PURCHASING, key);
        assertThat(archived.status()).as(archived.toString()).isEqualTo(200);
        assertThat(archived.body().path("asset").path("archived").asBoolean()).isTrue();
        assertThat(api("dana.designer").put(Map.of("archived", false, "expectedRevision", revision), null,
                ASSETS + "/decision/{key}/archived", PURCHASING, key).status()).isEqualTo(409);
        assertThat(assetCodes(validate("dana.designer", PURCHASING, draft), "/dependencies/assets"))
                .containsExactly("WF_ASSET_ARCHIVED:blocker");
        assertThat(publish(draft, true).code()).isEqualTo("WORKFLOW_INVALID");
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 2, "content", policyContent(8000)),
                ASSETS + "/decision/{key}/versions", PURCHASING, key).status()).isEqualTo(422);
        assertThat(api("vic.viewer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, draft.id()).body()
                .path("activeRelease").path("releaseNumber").asInt()).isEqualTo(1);
        assertThat(api("dana.designer").put(Map.of("archived", false, "expectedRevision", revision + 1), null,
                ASSETS + "/decision/{key}/archived", PURCHASING, key).status()).isEqualTo(200);
        assertThat(assetCodes(validate("dana.designer", PURCHASING, draft), "/dependencies/assets")).isEmpty();
    }

    @Test
    void missingTargetsDuplicatesAndOtherWorkspacesAreReportedNotResolved() throws Exception {
        String key = unique("limit");
        createDecision(key, 5000);
        Draft draft = workflow("dana.designer", PURCHASING);
        link(draft.definition(), "decision", unique("nothing"), 1, "amount-policy");
        draft = save("dana.designer", PURCHASING, draft);
        assertThat(assetCodes(validate("dana.designer", PURCHASING, draft), "/dependencies/assets"))
                .containsExactly("WF_ASSET_MISSING:blocker");

        ((ArrayNode) draft.definition().path("dependencies").path("assets")).removeAll();
        link(draft.definition(), "decision", key, 9, "amount-policy");
        link(draft.definition(), "decision", key, 1, "amount-policy");
        link(draft.definition(), "decision", key, 1, "no-such-table");
        link(draft.definition(), "documentTemplate", key, 1, "manager");
        draft = save("dana.designer", PURCHASING, draft);
        JsonNode report = validate("dana.designer", PURCHASING, draft);
        assertThat(assetCodes(report, "/dependencies/assets/0")).containsExactly("WF_ASSET_MISSING:blocker");
        assertThat(assetCodes(report, "/dependencies/assets/1")).containsExactly("WF_ASSET_REFERENCE_INVALID:blocker");
        assertThat(assetCodes(report, "/dependencies/assets/2")).containsExactly("WF_ASSET_REFERENCE_INVALID:blocker");
        assertThat(assetCodes(report, "/dependencies/assets/3")).containsExactly("WF_ASSET_REFERENCE_INVALID:blocker");

        // The same definition in another workspace resolves nothing from this one: the key does not exist there.
        String lim = unique("sa");
        var groups = List.of("orqix-" + lim);
        var org = api("pat.platform").post(Map.of("key", lim, "name", "Assets org"), "/admin/organizations");
        assertThat(org.status()).as(org.toString()).isEqualTo(201);
        var ws = api("pat.platform").post(Map.of("organizationId", org.body().path("id").asString(), "key", "ws-" + lim,
                "name", "Assets", "isTest", false, "allowSelfApprovalWorkflows", false,
                "initialAdmins", List.of(Map.of("type", "idpGroup", "value", groups.get(0)))), "/admin/workspaces");
        assertThat(ws.status()).as(ws.toString()).isEqualTo(201);
        UUID other = UUID.fromString(ws.body().path("id").asString());
        assertThat(api("lim.admin", groups).post(Map.of("role", "WORKFLOW_DESIGNER",
                "principal", Map.of("type", "idpGroup", "value", groups.get(0))), "/workspaces/{ws}/access/role-bindings", other)
                .status()).isEqualTo(201);
        Draft copy = workflow(api("lim.admin", groups), other);
        ObjectNode definition = copy.definition();
        link(definition, "decision", key, 1, "amount-policy");
        copy = save(api("lim.admin", groups), other, new Draft(copy.id(), copy.revision(), definition));
        var report2 = api("lim.admin", groups).post(Map.of("revision", copy.revision()), "/workspaces/{ws}/workflows/{id}/validate",
                other, copy.id());
        assertThat(assetCodes(report2.body(), "/dependencies/assets")).containsExactly("WF_ASSET_MISSING:blocker");
        assertThat(api("lim.admin", groups).get(ASSETS + "/decision/{key}", other, key).status()).isEqualTo(404);
        assertThat(api("lim.admin", groups).get(ASSETS + "/decision/{key}", PURCHASING, key).status()).isEqualTo(404);
    }

    @Test
    void documentTemplateReferencesUseTheTemplateKeyAndDetectEdits() throws Exception {
        String templateKey = unique("letter");
        var template = api("dana.designer").post(Map.of("name", "Letter " + templateKey, "key", templateKey, "format", "pdf",
                "body", "Dear {{name}}"), "/workspaces/{ws}/document-templates", PURCHASING);
        assertThat(template.status()).as(template.toString()).isEqualTo(201);
        assertThat(template.body().path("key").asString()).isEqualTo(templateKey);
        assertThat(template.body().path("owner").path("displayName").asString()).contains("Dana");
        // A derived key comes from the name and is numbered when taken.
        String name = "Derived " + UUID.randomUUID().toString().substring(0, 6);
        var derived = api("dana.designer").post(Map.of("name", name, "format", "pdf", "body", "Hi {{name}}"),
                "/workspaces/{ws}/document-templates", PURCHASING);
        assertThat(derived.body().path("key").asString()).isEqualTo(name.toLowerCase().replace(' ', '-'));
        assertThat(api("dana.designer").post(Map.of("name", "Other " + templateKey, "key", templateKey, "format", "pdf",
                "body", "x"), "/workspaces/{ws}/document-templates", PURCHASING).status()).isEqualTo(409);

        Draft draft = workflow("dana.designer", PURCHASING);
        ArrayNode nodes = (ArrayNode) draft.definition().path("nodes");
        ObjectNode step = nodes.addObject();
        step.put("id", "letter").put("type", "generateDocument").put("label", "Letter");
        step.putObject("config").put("format", "pdf").put("fileName", "letter").put("template", "Dear {{name}}")
                .putObject("inputMapping").put("name", "/input/title");
        link(draft.definition(), "documentTemplate", templateKey, 1, "letter");
        draft = save("dana.designer", PURCHASING, draft);
        assertThat(assetCodes(validate("dana.designer", PURCHASING, draft), "/dependencies/assets")).isEmpty();

        ((ObjectNode) step.path("config")).put("format", "docx");
        draft = save("dana.designer", PURCHASING, draft);
        JsonNode report = validate("dana.designer", PURCHASING, draft);
        assertThat(assetCodes(report, "/dependencies/assets")).containsExactly("WF_ASSET_DIVERGED:blocker");
        assertThat(report.path("issues").toString()).contains("\"nodeId\":\"letter\"");

        var usage = api("vic.viewer").get(ASSETS + "/documentTemplate/{key}/usage", PURCHASING, templateKey);
        assertThat(usage.status()).as(usage.toString()).isEqualTo(200);
        assertThat(usage.body().path("items").get(0).path("draftVersion").asInt()).isEqualTo(1);
        assertThat(usage.body().path("items").get(0).path("activeReleaseVersion").isMissingNode()
                || usage.body().path("items").get(0).path("activeReleaseVersion").isNull()).isTrue();
    }

    @Test
    void connectionsArePinnedSharedAssetsManagedOnlyByConnectionManagers() throws Exception {
        String key = unique("assetconn");
        for (int version = 1; version <= 2; version++) {
            ObjectNode definition = (ObjectNode) Fixtures.procurementConnection();
            definition.put("key", key).put("version", version).put("name", "Shared connection " + key);
            if (version == 2) {
                definition.put("description", "Second version");
            }
            var registered = api("wade.admin").post(Map.of("definition", definition),
                    "/workspaces/{ws}/connections/{key}/versions", PURCHASING, key);
            assertThat(registered.status()).as(registered.toString()).isEqualTo(201);
        }
        Draft draft = workflow("dana.designer", PURCHASING);
        ((ArrayNode) draft.definition().path("dependencies").path("connections")).addObject().put("key", key)
                .put("definitionVersion", 1);
        draft = save("dana.designer", PURCHASING, draft);
        String path = "/dependencies/connections/1";
        assertThat(assetCodes(validate("dana.designer", PURCHASING, draft), path)).contains("WF_ASSET_STALE:warning");

        // Viewers have no connection.read: search skips connections and the asset itself is forbidden.
        var viewerSearch = api("vic.viewer").get(ASSETS + "?q=" + key, PURCHASING);
        assertThat(viewerSearch.status()).isEqualTo(200);
        assertThat(viewerSearch.body().path("searchedKinds").toString()).doesNotContain("connection");
        assertThat(viewerSearch.body().path("items").size()).isZero();
        assertThat(api("vic.viewer").get(ASSETS + "/connection/{key}", PURCHASING, key).status()).isEqualTo(403);
        var designerSearch = api("dana.designer").get(ASSETS + "?q=" + key, PURCHASING);
        assertThat(designerSearch.body().path("items").get(0).path("kind").asString()).isEqualTo("connection");
        assertThat(designerSearch.body().path("items").get(0).path("latestVersion").asInt()).isEqualTo(2);
        assertThat(designerSearch.body().path("items").get(0).path("revision").asInt()).isZero();
        var detail = api("dana.designer").get(ASSETS + "/connection/{key}", PURCHASING, key);
        assertThat(detail.body().path("versions").size()).isEqualTo(2);

        // Designers may use a connection but not archive it; a connection manager can.
        assertThat(api("dana.designer").put(Map.of("archived", true, "expectedRevision", 0), null,
                ASSETS + "/connection/{key}/archived", PURCHASING, key).status()).isEqualTo(403);
        var archived = api("wade.admin").put(Map.of("archived", true, "expectedRevision", 0), null,
                ASSETS + "/connection/{key}/archived", PURCHASING, key);
        assertThat(archived.status()).as(archived.toString()).isEqualTo(200);
        assertThat(archived.body().path("asset").path("revision").asInt()).isEqualTo(1);
        assertThat(api("wade.admin").put(Map.of("archived", false, "expectedRevision", 0), null,
                ASSETS + "/connection/{key}/archived", PURCHASING, key).status()).isEqualTo(409);
        assertThat(assetCodes(validate("dana.designer", PURCHASING, draft), path)).contains("WF_ASSET_ARCHIVED:blocker");
        var usage = api("olga.operator").get(ASSETS + "/connection/{key}/usage", PURCHASING, key);
        assertThat(usage.status()).as(usage.toString()).isEqualTo(200);
        assertThat(usage.body().path("items").get(0).path("draftVersion").asInt()).isEqualTo(1);
        // Archiving changed nothing about the binding-free definition records.
        assertThat(api("wade.admin").get("/workspaces/{ws}/connections/{key}", PURCHASING, key).status()).isEqualTo(200);
    }

    @Test
    void searchOwnersAndPermissionBoundaries() throws Exception {
        String key = unique("searchable");
        JsonNode created = createDecision(key, 4000);
        assertThat(created.path("asset").path("owner").path("displayName").asString()).contains("Dana");
        assertThat(created.path("asset").path("revision").asInt()).isEqualTo(1);

        // Search by key, by name and by owner; kind filter.
        assertThat(api("vic.viewer").get(ASSETS + "?q=" + key, PURCHASING).body().path("items").get(0).path("key").asString())
                .isEqualTo(key);
        assertThat(api("vic.viewer").get(ASSETS + "?q=" + key.toUpperCase(), PURCHASING).body().path("items").size())
                .isEqualTo(1);
        String danaId = created.path("asset").path("owner").path("id").asString();
        JsonNode byOwner = api("vic.viewer").get(ASSETS + "?kind=decision&owner=" + danaId, PURCHASING).body();
        assertThat(byOwner.path("items").toString()).contains(key);
        assertThat(byOwner.path("searchedKinds").toString()).isEqualTo("[\"decision\"]");
        assertThat(api("vic.viewer").get(ASSETS + "?q=dana", PURCHASING).body().path("items").toString()).contains(key);
        assertThat(api("vic.viewer").get(ASSETS + "?owner=nope", PURCHASING).status()).isEqualTo(400);
        assertThat(api("vic.viewer").get(ASSETS + "?kind=widget", PURCHASING).status()).isEqualTo(400);

        // Requesters (start only) cannot read assets; foreign people cannot tell they exist.
        assertThat(api("rhea.requester").get(ASSETS, PURCHASING).status()).isEqualTo(403);
        assertThat(api("rhea.requester").get(ASSETS + "/decision/{key}", PURCHASING, key).status()).isEqualTo(403);
        for (String user : List.of("fred.facilities", "otto.outsider")) {
            assertThat(api(user).get(ASSETS, PURCHASING).status()).isEqualTo(404);
            assertThat(api(user).get(ASSETS + "/decision/{key}", PURCHASING, key).status()).isEqualTo(404);
        }
        // Creating and versioning need workflow.edit; publishers and viewers are refused.
        for (String user : List.of("vic.viewer", "paul.publisher")) {
            assertThat(api(user).post(Map.of("key", unique("x"), "name", unique("X"), "content", policyContent(1)),
                    ASSETS + "/decision", PURCHASING).status()).isEqualTo(403);
        }
        assertThat(api("dana.designer").post(Map.of("key", key, "name", unique("Again"), "content", policyContent(1)),
                ASSETS + "/decision", PURCHASING).status()).isEqualTo(409);
        ObjectNode bad = policyContent(1);
        bad.put("hitPolicy", "COLLECT");
        assertThat(api("dana.designer").post(Map.of("key", unique("bad"), "name", unique("Bad"), "content", bad),
                ASSETS + "/decision", PURCHASING).status()).isIn(400, 422);
        ObjectNode extra = policyContent(1);
        extra.put("key", "sneaky");
        assertThat(api("dana.designer").post(Map.of("key", unique("bad"), "name", unique("Bad"), "content", extra),
                ASSETS + "/decision", PURCHASING).status()).isIn(400, 422);
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 1, "content", policyContent(4000)),
                ASSETS + "/decision/{key}/versions", PURCHASING, key).status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 2, "content", policyContent(4100)),
                ASSETS + "/decision/{key}/versions", PURCHASING, key).status()).isEqualTo(409);

        // Owner: only someone who can edit workflows now; versioned; ownership grants nothing.
        var second = List.of("orqix-purchasing-designers");
        String secondId = api("sam.second", second).get("/me").body().path("subject").path("id").asString();
        String viewerId = api("vic.viewer").get("/me").body().path("subject").path("id").asString();
        var candidates = api("dana.designer").get("/workspaces/{ws}/shared-asset-owner-candidates?kind=decision", PURCHASING);
        assertThat(candidates.status()).as(candidates.toString()).isEqualTo(200);
        assertThat(candidates.body().path("items").toString()).contains(secondId).doesNotContain(viewerId);
        assertThat(api("vic.viewer").get("/workspaces/{ws}/shared-asset-owner-candidates?kind=decision", PURCHASING).status())
                .isEqualTo(403);
        assertThat(api("dana.designer").get("/workspaces/{ws}/shared-asset-owner-candidates?kind=connection", PURCHASING)
                .status()).isEqualTo(403);
        assertThat(api("dana.designer").put(Map.of("ownerSubjectId", viewerId, "expectedRevision", 1), null,
                ASSETS + "/decision/{key}/owner", PURCHASING, key).status()).isEqualTo(422);
        assertThat(api("vic.viewer").put(Map.of("ownerSubjectId", secondId, "expectedRevision", 1), null,
                ASSETS + "/decision/{key}/owner", PURCHASING, key).status()).isEqualTo(403);
        var changed = api("dana.designer").put(Map.of("ownerSubjectId", secondId, "expectedRevision", 1), null,
                ASSETS + "/decision/{key}/owner", PURCHASING, key);
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
        assertThat(changed.body().path("asset").path("owner").path("id").asString()).isEqualTo(secondId);
        assertThat(changed.body().path("asset").path("revision").asInt()).isEqualTo(2);
        assertThat(api("dana.designer").put(Map.of("ownerSubjectId", danaId, "expectedRevision", 1), null,
                ASSETS + "/decision/{key}/owner", PURCHASING, key).status()).isEqualTo(409);
        assertThat(api("vic.viewer").get(ASSETS + "?owner=" + secondId, PURCHASING).body().path("items").toString()).contains(key);
        // The previous owner keeps exactly what her role gives her.
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 1, "content", policyContent(4200)),
                ASSETS + "/decision/{key}/versions", PURCHASING, key).status()).isEqualTo(201);
        // Audit carries the kind and counts, never decision content.
        String metadata = jdbc.sql("SELECT string_agg(metadata::text, ' ') FROM audit_event WHERE resource_type = 'sharedAsset'"
                + " AND resource_id = :id").param("id", "decision:" + key).query(String.class).single();
        assertThat(metadata).contains("\"kind\": \"decision\"").doesNotContain("4200").doesNotContain("financeReview");
    }
}
