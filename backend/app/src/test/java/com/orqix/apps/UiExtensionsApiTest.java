package com.orqix.apps;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** S111 guarded extensions: declared capabilities, admin approval by someone else, sandbox policy and server grants. */
class UiExtensionsApiTest extends PageTestBase {
    private static final String EXT = "/workspaces/{ws}/ui-extensions";
    private static final String SOURCE = "orqix.onData(function (data) { document.getElementById('root').textContent = "
            + "String(data['task.requestTitle']); });";

    private UUID register(String user, String name) throws Exception {
        var created = api(user).post(Map.of("name", name), EXT, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return UUID.fromString(created.body().path("id").asString());
    }

    private JsonNode submit(String user, UUID id, List<String> bindings, List<String> actions) throws Exception {
        var submitted = api(user).post(Map.of("source", SOURCE, "capabilities", Map.of("bindings", bindings, "actions", actions),
                "height", 160), EXT + "/{id}/versions", PURCHASING, id);
        assertThat(submitted.status()).as(submitted.toString()).isEqualTo(201);
        return submitted.body();
    }

    private int decide(String user, UUID id, int version, String decision, String expected) throws Exception {
        return api(user).post(Map.of("decision", decision, "note", "Reviewed source and capabilities", "expectedStatus", expected),
                EXT + "/{id}/versions/{v}/decision", PURCHASING, id, version).status();
    }

    @Test
    void versionsNeedAdminApprovalBySomeoneElseAndTheFrameIsSandboxed() throws Exception {
        UUID id = register("dana.designer", unique("Badge"));
        assertThat(api("vic.viewer").post(Map.of("name", unique("x")), EXT, PURCHASING).status()).isEqualTo(403);
        var bad = api("dana.designer").post(Map.of("source", "x</script><script>alert(1)", "capabilities",
                Map.of("bindings", List.of(), "actions", List.of()), "height", 160), EXT + "/{id}/versions", PURCHASING, id);
        assertThat(bad.status()).isEqualTo(422);
        var undeclared = api("dana.designer").post(Map.of("source", SOURCE, "capabilities",
                Map.of("bindings", List.of("task.secretField"), "actions", List.of("delete")), "height", 160),
                EXT + "/{id}/versions", PURCHASING, id);
        assertThat(undeclared.status()).isEqualTo(422);

        JsonNode v1 = submit("dana.designer", id, List.of("task.requestTitle"), List.of("approve"));
        assertThat(v1.path("status").asString()).isEqualTo("pending");
        assertThat(v1.path("sha256").asString()).hasSize(64);

        // Pending versions are previewable by reviewers only.
        var preview = api("dana.designer").get(EXT + "/{id}/versions/{v}/frame", PURCHASING, id, 1);
        assertThat(preview.status()).isEqualTo(200);
        String policy = preview.header("Content-Security-Policy");
        assertThat(policy).contains("sandbox allow-scripts").contains("default-src 'none'").contains("connect-src 'none'")
                .contains("frame-ancestors 'self'").doesNotContain("allow-same-origin").doesNotContain("unsafe-eval");
        assertThat(policy.split("'sha256-").length).isEqualTo(3);
        assertThat(preview.header("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(api("dana.designer").get(EXT, PURCHASING).header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(new String(preview.bytes(), StandardCharsets.UTF_8)).contains(SOURCE).contains("orqix-extension/1")
                .contains("<meta name=\"referrer\" content=\"no-referrer\">").contains("type: 'unloading'");
        // S114: no form posts or base changes, no powerful features, no referrer for a self-navigation.
        assertThat(policy).contains("form-action 'none'").contains("base-uri 'none'");
        assertThat(preview.header("Permissions-Policy")).contains("camera=()").contains("clipboard-read=()").contains("payment=()");
        assertThat(preview.header("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(api("rhea.requester").get(EXT + "/{id}/versions/{v}/frame", PURCHASING, id, 1).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get(EXT + "/{id}/versions/{v}/frame", PURCHASING, id, 1).status()).isEqualTo(404);

        assertThat(decide("dana.designer", id, 1, "approve", "pending")).isEqualTo(403);
        assertThat(decide("wade.admin", id, 1, "approve", "approved")).isEqualTo(409);
        assertThat(decide("wade.admin", id, 1, "revoke", "pending")).isEqualTo(422);
        assertThat(decide("wade.admin", id, 1, "approve", "pending")).isEqualTo(200);
        assertThat(api("rhea.requester").get(EXT + "/{id}/versions/{v}/frame", PURCHASING, id, 1).status()).isEqualTo(200);
        var listed = api("wade.admin").get(EXT, PURCHASING);
        assertThat(listed.body().path("canApprove").asBoolean()).isTrue();
        assertThat(api("vic.viewer").get(EXT, PURCHASING).body().path("canApprove").asBoolean()).isFalse();

        // An administrator who also designs cannot approve their own submission.
        var both = List.of("orqix-purchasing-admins", "orqix-purchasing-designers");
        var own = api("wade.admin", both).post(Map.of("source", SOURCE, "capabilities",
                Map.of("bindings", List.of(), "actions", List.of()), "height", 120), EXT + "/{id}/versions", PURCHASING, id);
        assertThat(own.status()).as(own.toString()).isEqualTo(201);
        var self = api("wade.admin", both).post(Map.of("decision", "approve", "note", "Self", "expectedStatus", "pending"),
                EXT + "/{id}/versions/{v}/decision", PURCHASING, id, 2);
        assertThat(self.status()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM ui_extension_decision WHERE extension_id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void pagesGrantOnlyDeclaredCapabilitiesAndRevocationIsolatesTheComponent() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        Pin approval = publishedView(approvalView());
        UUID id = register("dana.designer", unique("Summary"));
        submit("dana.designer", id, List.of("task.requestTitle", "request.amount"), List.of("claim", "approve"));
        var extension = Map.<String, Object>of("id", "badge", "type", "extension", "label", "Badge",
                "extension", Map.of("id", id.toString(), "version", 1), "bindings", List.of("task.requestTitle"),
                "chains", List.of("approveNow"), "height", 160);
        var definition = page("Decide with badge", "task", List.of(
                Map.of("id", "decision", "type", "view", "view", approval.ref()), extension),
                List.of(chain("approveNow", List.of(step("claim", "decision"), step("approve", "decision")))));

        // Saving with a pending version is allowed; publishing is not.
        UUID page = createPage(definition);
        var early = api("paul.publisher").post(Map.of("expectedRevision", 1), VIEWS + "/{id}/publish", PURCHASING, page);
        assertThat(early.status()).isEqualTo(422);
        assertThat(early.body().path("detail").asString()).contains("not approved");

        var over = new java.util.HashMap<>(extension);
        over.put("bindings", List.of("request.title"));
        var tooMuch = api("dana.designer").post(Map.of("name", unique("page"), "definition", page("Over", "task",
                List.of(Map.of("id", "decision", "type", "view", "view", approval.ref()), over),
                List.of(chain("approveNow", List.of(step("claim", "decision")))))), VIEWS, PURCHASING);
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.body().path("detail").asString()).contains("does not declare");
        var undeclaredAction = new java.util.HashMap<>(extension);
        undeclaredAction.put("chains", List.of("rejectNow"));
        assertThat(api("dana.designer").post(Map.of("name", unique("page"), "definition", page("Reject", "task",
                List.of(Map.of("id", "decision", "type", "view", "view", approval.ref()), undeclaredAction),
                List.of(chain("rejectNow", List.of(step("reject", "decision")))))), VIEWS, PURCHASING).status()).isEqualTo(422);

        assertThat(decide("wade.admin", id, 1, "approve", "pending")).isEqualTo(200);
        int version = publish(page, 1);
        String slug = unique("badge");
        route(slug, 0, page, version);

        var runtime = api("mani.manager").get(PAGES + "/{slug}", PURCHASING, slug);
        assertThat(runtime.body().path("extensions").get(0).path("status").asString()).isEqualTo("approved");
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, java.math.BigDecimal.TEN).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();

        var data = api("mani.manager").get(PAGES + "/{slug}/components/{c}/data?context=" + taskId, PURCHASING, slug, "badge");
        assertThat(data.status()).as(data.toString()).isEqualTo(200);
        assertThat(data.body().path("values").path("task.requestTitle").asString()).isEqualTo("Replacement laptop");
        assertThat(data.body().path("values").has("request.amount")).as("declared but not granted").isFalse();
        assertThat(data.body().path("values").size()).isEqualTo(1);
        // The server reads the context with the caller's own access.
        assertThat(api("fin.finance").get(PAGES + "/{slug}/components/{c}/data?context=" + taskId, PURCHASING, slug, "badge")
                .status()).isIn(403, 404);
        assertThat(api("mani.manager").get(PAGES + "/{slug}/components/{c}/data?context=" + taskId, PURCHASING, slug, "decision")
                .status()).isEqualTo(404);

        assertThat(decide("wade.admin", id, 1, "revoke", "approved")).isEqualTo(200);
        assertThat(api("mani.manager").get(PAGES + "/{slug}", PURCHASING, slug).body().path("extensions").get(0)
                .path("status").asString()).isEqualTo("revoked");
        assertThat(api("mani.manager").get(PAGES + "/{slug}/components/{c}/data?context=" + taskId, PURCHASING, slug, "badge")
                .status()).isEqualTo(403);
        assertThat(api("mani.manager").get(EXT + "/{id}/versions/{v}/frame", PURCHASING, id, 1).status()).isEqualTo(403);
        var fromExtension = api("mani.manager").post(Map.of("expectedRevision", 1, "context", taskId, "source", "badge"),
                UUID.randomUUID().toString(), PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, "approveNow");
        assertThat(fromExtension.status()).isEqualTo(403);
        assertThat(openTask(PURCHASING, runId, "manager")).isNotNull();
        // A revoked version cannot be pinned to a route again.
        var repin = api("paul.publisher").put(Map.of("expectedRevision", 0, "viewRef", Map.of("id", page.toString(),
                "version", version)), null, PAGES + "/{slug}", PURCHASING, unique("again"));
        assertThat(repin.status()).isEqualTo(422);
        // The page editor still lists where the page is used: the route is reported as blocked, not a server error.
        var uses = api("dana.designer").get(VIEWS + "/{id}/pins?targetVersion=" + version, PURCHASING, page);
        assertThat(uses.status()).as(uses.toString()).isEqualTo(200);
        JsonNode routeUse = null;
        for (JsonNode item : uses.body().path("items")) if (item.path("key").asString().equals("route:" + slug)) routeUse = item;
        assertThat(routeUse).as(uses.toString()).isNotNull();
        assertThat(routeUse.path("status").asString()).isEqualTo("current");
    }
}
