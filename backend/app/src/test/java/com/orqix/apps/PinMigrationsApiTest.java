package com.orqix.apps;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S114 reviewed version migration: every pin of a view is listed with diagnostics against a chosen version; applying is
 * refused when the reviewed plan changed, moves only ready holders through their own services in one transaction, and
 * never changes a published release or page version.
 */
class PinMigrationsApiTest extends PageTestBase {
    private static final List<String> BOTH = List.of("orqix-purchasing-designers", "orqix-purchasing-publishers");

    @Test
    void reviewedMigrationMovesDraftPinsAndKeepsReleasesAndPublishedPages() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        Pin form = publishedView(formView("Purchase"));
        Pin approval = publishedView(fullApproval("Summary", true));
        ObjectNode draft = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow)
                .body().path("definition");
        ((ObjectNode) draft.path("inputUi")).putObject("viewRef").put("id", form.id().toString()).put("version", 1);
        for (JsonNode node : draft.path("nodes")) {
            if ("manager".equals(node.path("id").asString())) {
                ((ObjectNode) node.path("config")).putObject("viewBinding").put("id", approval.id().toString()).put("version", 1)
                        .set("definition", json.mapper().valueToTree(fullApproval("Summary", true)));
            }
        }
        assertThat(api("dana.designer").put(Map.of("definition", draft), "\"r2\"", "/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, workflow).status()).isEqualTo(200);
        UUID page = createPage(page("Buy", "none", List.of(
                Map.of("id", "form", "type", "view", "view", form.ref(), "workflowId", workflow.toString()),
                Map.of("id", "bar", "type", "actions", "chains", List.of("send"))),
                List.of(chain("send", List.of(step("submit", "form"))))));
        publish(page, 1);
        assertThat(republish(form.id(), formView("Purchase details"), 1)).isEqualTo(2);

        String path = VIEWS + "/{id}/pins";
        assertThat(api("rhea.requester").get(path, PURCHASING, form.id()).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get(path, PURCHASING, form.id()).status()).isEqualTo(404);
        JsonNode plan = api("dana.designer").get(path, PURCHASING, form.id()).body();
        assertThat(plan.path("targetVersion").asInt()).isEqualTo(2);
        assertThat(item(plan, "workflow:" + workflow).path("status").asString()).as(plan.toString()).isEqualTo("ready");
        assertThat(item(plan, "workflow:" + workflow).path("location").asString()).isEqualTo("Request form");
        assertThat(item(plan, "page:" + page).path("status").asString()).isEqualTo("ready");
        assertThat(item(plan, "pageVersion:" + page).path("status").asString()).isEqualTo("readOnly");
        assertThat(api("dana.designer").get(path + "?targetVersion=1", PURCHASING, form.id()).body().path("items").findValuesAsString("status"))
                .doesNotContain("ready");

        String migrations = VIEWS + "/{id}/pin-migrations";
        List<String> keys = List.of("workflow:" + workflow, "page:" + page);
        var stale = api("dana.designer").post(Map.of("targetVersion", 2, "planHash", "0".repeat(64), "items", keys, "reason", "Adopt"),
                migrations, PURCHASING, form.id());
        assertThat(stale.status()).isEqualTo(409);
        String hash = plan.path("planHash").asString();
        assertThat(api("dana.designer").post(Map.of("targetVersion", 2, "planHash", hash, "items", List.of("pageVersion:" + page),
                "reason", "Adopt"), migrations, PURCHASING, form.id()).status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("targetVersion", 2, "planHash", hash, "items", keys, "reason", " "),
                migrations, PURCHASING, form.id()).status()).isEqualTo(422);
        var applied = api("dana.designer").post(Map.of("targetVersion", 2, "planHash", hash, "items", keys,
                "reason", "Adopt the clearer labels"), migrations, PURCHASING, form.id());
        assertThat(applied.status()).as(applied.toString()).isEqualTo(200);
        assertThat(applied.body().path("items")).hasSize(2);
        // The same reviewed plan cannot apply twice: the pins changed.
        assertThat(api("dana.designer").post(Map.of("targetVersion", 2, "planHash", hash, "items", keys, "reason", "Again"),
                migrations, PURCHASING, form.id()).status()).isEqualTo(409);

        JsonNode moved = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body();
        assertThat(moved.path("revision").asInt()).isEqualTo(4);
        assertThat(moved.path("definition").path("inputUi").path("viewRef").path("version").asInt()).isEqualTo(2);
        assertThat(moved.path("definition").path("inputUi").path("layout").path("sections").get(0).path("title").asString())
                .isEqualTo("Purchase details");
        JsonNode pageDraft = api("dana.designer").get(VIEWS + "/{id}", PURCHASING, page).body();
        assertThat(pageDraft.path("draftRevision").asInt()).isEqualTo(2);
        assertThat(pageDraft.path("definition").path("regions").get(0).path("components").get(0).path("view").path("version").asInt()).isEqualTo(2);
        assertThat(api("dana.designer").get(VIEWS + "/{id}/versions/1", PURCHASING, page).body().path("definition")
                .path("regions").get(0).path("components").get(0).path("view").path("version").asInt()).isEqualTo(1);

        // The migrated draft passes the release's own view-pin checks; the release then shows as a fixed pin.
        var released = api("paul.publisher").post(Map.of("draftRevision", 4, "acknowledgeWarnings", true), UUID.randomUUID().toString(),
                "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(released.status()).as(released.toString()).isEqualTo(201);
        JsonNode after = api("dana.designer").get(path, PURCHASING, form.id()).body();
        assertThat(item(after, "workflow:" + workflow).path("status").asString()).isEqualTo("current");
        assertThat(item(after, "release:" + workflow).path("status").asString()).isEqualTo("readOnly");
        assertThat(item(after, "release:" + workflow).path("pinnedVersions").get(0).asInt()).isEqualTo(2);
        JsonNode history = api("dana.designer").get(migrations, PURCHASING, form.id()).body().path("items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("reason").asString()).isEqualTo("Adopt the clearer labels");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'view.pins-migrated' AND resource_id = :id")
                .param("id", form.id().toString()).query(Integer.class).single()).isEqualTo(1);

        // Approval pins: a compatible version is ready; one that drops a request field is blocked with the reason.
        assertThat(republish(approval.id(), fullApproval("Decision summary", true), 1)).isEqualTo(2);
        assertThat(republish(approval.id(), fullApproval("Short", false), 2)).isEqualTo(3);
        JsonNode ready = api("dana.designer").get(path + "?targetVersion=2", PURCHASING, approval.id()).body();
        assertThat(item(ready, "workflow:" + workflow).path("status").asString()).as(ready.toString()).isEqualTo("ready");
        assertThat(item(ready, "workflow:" + workflow).path("location").asString()).startsWith("Approval step");
        JsonNode blocked = api("dana.designer").get(path + "?targetVersion=3", PURCHASING, approval.id()).body();
        assertThat(item(blocked, "workflow:" + workflow).path("status").asString()).isEqualTo("blocked");
        assertThat(item(blocked, "workflow:" + workflow).path("problems").get(0).asString()).contains("exactly once");
        assertThat(api("dana.designer").post(Map.of("targetVersion", 3, "planHash", blocked.path("planHash").asString(),
                "items", List.of("workflow:" + workflow), "reason", "Try"), migrations, PURCHASING, approval.id()).status()).isEqualTo(422);
    }

    @Test
    void routesAndTheWorklistNeedTheirOwnPermissionAndEverySelectedPinMovesOrNone() throws Exception {
        Pin list = publishedView(worklistView("Request"));
        UUID page = createPage(page("Queue", "none", List.of(Map.of("id", "queue", "type", "view", "view", list.ref())), List.of()));
        publish(page, 1);
        String slug = unique("queue");
        route(slug, 0, page, 1);
        int worklistRevision = api("paul.publisher").get("/workspaces/{ws}/worklist-view", PURCHASING).body().path("revision").asInt();
        assertThat(api("paul.publisher").put(Map.of("expectedRevision", worklistRevision, "viewRef", list.ref()), null,
                "/workspaces/{ws}/worklist-view", PURCHASING).status()).isEqualTo(200);
        try {
            assertThat(republish(list.id(), worklistView("Request title"), 1)).isEqualTo(2);
            JsonNode plan = api("dana.designer").get(VIEWS + "/{id}/pins", PURCHASING, list.id()).body();
            List<String> keys = List.of("page:" + page, "worklist");
            assertThat(item(plan, "worklist").path("status").asString()).as(plan.toString()).isEqualTo("ready");
            Map<String, Object> body = Map.of("targetVersion", 2, "planHash", plan.path("planHash").asString(), "items", keys,
                    "reason", "New column label");
            // The designer may save the page draft but not assign the worklist: nothing changes.
            assertThat(api("dana.designer").post(body, VIEWS + "/{id}/pin-migrations", PURCHASING, list.id()).status()).isEqualTo(403);
            assertThat(api("dana.designer").get(VIEWS + "/{id}", PURCHASING, page).body().path("draftRevision").asInt()).isEqualTo(1);
            assertThat(api("paul.publisher").get("/workspaces/{ws}/worklist-view", PURCHASING).body().path("binding").path("version").asInt()).isEqualTo(1);
            assertThat(api("paul.publisher").post(body, VIEWS + "/{id}/pin-migrations", PURCHASING, list.id()).status()).isEqualTo(403);
            var applied = api("dana.designer", BOTH).post(body, VIEWS + "/{id}/pin-migrations", PURCHASING, list.id());
            assertThat(applied.status()).as(applied.toString()).isEqualTo(200);
            assertThat(api("paul.publisher").get("/workspaces/{ws}/worklist-view", PURCHASING).body().path("binding").path("version").asInt()).isEqualTo(2);
            assertThat(api("dana.designer").get(VIEWS + "/{id}", PURCHASING, page).body().path("draftRevision").asInt()).isEqualTo(2);

            // The page itself: its route moves to the new published page version after review.
            assertThat(publish(page, 2)).isEqualTo(2);
            JsonNode routes = api("dana.designer").get(VIEWS + "/{id}/pins", PURCHASING, page).body();
            assertThat(item(routes, "route:" + slug).path("status").asString()).as(routes.toString()).isEqualTo("ready");
            Map<String, Object> move = Map.of("targetVersion", 2, "planHash", routes.path("planHash").asString(),
                    "items", List.of("route:" + slug), "reason", "Use the new queue");
            assertThat(api("dana.designer").post(move, VIEWS + "/{id}/pin-migrations", PURCHASING, page).status()).isEqualTo(403);
            assertThat(api("dana.designer", BOTH).post(move, VIEWS + "/{id}/pin-migrations", PURCHASING, page).status()).isEqualTo(200);
            JsonNode routed = api("paul.publisher").get(PAGES, PURCHASING).body().path("items");
            JsonNode mine = null;
            for (JsonNode route : routed) if (slug.equals(route.path("slug").asString())) mine = route;
            assertThat(mine).isNotNull();
            assertThat(mine.path("revision").asInt()).isEqualTo(2);
            assertThat(mine.path("page").path("version").asInt()).isEqualTo(2);
        } finally {
            int current = api("paul.publisher").get("/workspaces/{ws}/worklist-view", PURCHASING).body().path("revision").asInt();
            Map<String, Object> reset = new LinkedHashMap<>();
            reset.put("expectedRevision", current);
            reset.put("viewRef", null);
            api("paul.publisher").put(reset, null, "/workspaces/{ws}/worklist-view", PURCHASING);
        }
    }

    @Test
    void caseDefinitionsGetANewVersionAndExistingCasesKeepTheirView() throws Exception {
        List<Map<String, Object>> fields = List.of(Map.of("key", "summary", "label", "Summary", "type", "text", "required", true, "maxLength", 80));
        UUID view = sandboxView(caseView("Details", true));
        String key = "pincase" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String xml;
        try (var input = getClass().getResourceAsStream("/cases/manual-required.cmmn")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("lifecycleCase", key);
        }
        var definition = api("dana.designer").post(Map.of("key", key, "name", "Pinned case", "sourceXml", xml, "dataFields", fields,
                "viewRef", Map.of("id", view.toString(), "version", 1)), "/workspaces/{ws}/cases/definitions", SANDBOX);
        assertThat(definition.status()).as(definition.toString()).isEqualTo(201);
        String definitionId = definition.body().path("id").asString();
        var started = api("rhea.requester").post(Map.of("data", Map.of("summary", "Keep me")), UUID.randomUUID().toString(),
                "/workspaces/{ws}/cases/definitions/{id}/instances", SANDBOX, definitionId);
        assertThat(started.status()).isEqualTo(201);

        assertThat(sandboxRepublish(view, caseView("Case details", true), 1)).isEqualTo(2);
        assertThat(sandboxRepublish(view, caseView("Optional", false), 2)).isEqualTo(3);
        JsonNode blocked = api("dana.designer").get(VIEWS + "/{id}/pins?targetVersion=3", SANDBOX, view).body();
        assertThat(item(blocked, "case:" + definitionId).path("status").asString()).as(blocked.toString()).isEqualTo("blocked");
        JsonNode plan = api("dana.designer").get(VIEWS + "/{id}/pins?targetVersion=2", SANDBOX, view).body();
        assertThat(item(plan, "case:" + definitionId).path("status").asString()).as(plan.toString()).isEqualTo("ready");
        var applied = api("dana.designer").post(Map.of("targetVersion", 2, "planHash", plan.path("planHash").asString(),
                "items", List.of("case:" + definitionId), "reason", "New case layout"), VIEWS + "/{id}/pin-migrations", SANDBOX, view);
        assertThat(applied.status()).as(applied.toString()).isEqualTo(200);
        assertThat(applied.body().path("items").get(0).path("outcome").asString()).contains("version 2");

        JsonNode definitions = api("dana.designer").get("/workspaces/{ws}/cases/definitions", SANDBOX).body();
        List<JsonNode> versions = new ArrayList<>();
        for (JsonNode item : definitions.isArray() ? definitions : definitions.path("items")) {
            if (key.equals(item.path("key").asString())) versions.add(item);
        }
        assertThat(versions).hasSize(2);
        assertThat(versions.stream().filter(v -> v.path("version").asInt() == 2).findFirst().orElseThrow()
                .path("viewBinding").path("version").asInt()).isEqualTo(2);
        var data = api("mani.manager").get("/workspaces/{ws}/cases/instances/{id}/data", SANDBOX, started.body().path("id").asString());
        assertThat(data.body().path("viewBinding").path("version").asInt()).isEqualTo(1);
        JsonNode after = api("dana.designer").get(VIEWS + "/{id}/pins?targetVersion=2", SANDBOX, view).body();
        assertThat(item(after, "case:" + versions.stream().filter(v -> v.path("version").asInt() == 2).findFirst().orElseThrow()
                .path("id").asString()).path("status").asString()).isEqualTo("current");
    }

    private static JsonNode item(JsonNode plan, String key) {
        for (JsonNode item : plan.path("items")) if (key.equals(item.path("key").asString())) return item;
        throw new AssertionError("Missing pin " + key + ": " + plan);
    }

    /** A request view a workflow form can pin: one submit action, every purchase field bound once. */
    static Map<String, Object> formView(String section) {
        return Map.of("schemaVersion", 1, "kind", "request",
                "sections", List.of(Map.of("id", "details", "label", section, "columns", 2, "fields", List.of(
                        field("title", "What are you buying?", "text", "request.title", true),
                        field("purpose", "Business purpose", "text", "request.purpose", true),
                        field("costCenter", "Cost center", "choice", "request.costCenter", true),
                        field("amount", "Amount (USD)", "number", "request.amount", true)))),
                "actions", List.of(Map.of("id", "submit", "label", "Submit", "type", "submit")));
    }

    static Map<String, Object> fullApproval(String section, boolean everyField) {
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
                field("title", "Item", "text", "request.title", true),
                field("amount", "Amount", "number", "request.amount", true)));
        if (everyField) {
            fields.add(field("purpose", "Purpose", "text", "request.purpose", true));
            fields.add(field("costCenter", "Cost center", "choice", "request.costCenter", true));
        }
        return Map.of("schemaVersion", 1, "kind", "approval",
                "sections", List.of(Map.of("id", "summary", "label", section, "columns", 1, "fields", fields)),
                "actions", List.of(Map.of("id", "approve", "label", "Approve", "type", "approve"),
                        Map.of("id", "reject", "label", "Reject", "type", "reject")));
    }

    static Map<String, Object> worklistView(String title) {
        return Map.of("schemaVersion", 1, "kind", "worklist",
                "sections", List.of(Map.of("id", "columns", "label", "Columns", "columns", 1, "fields", List.of(
                        field("title", title, "text", "task.requestTitle", false),
                        field("status", "Status", "text", "task.status", false)))),
                "actions", List.of(Map.of("id", "open", "label", "Open task", "type", "openTask")));
    }

    static Map<String, Object> caseView(String section, boolean required) {
        return Map.of("schemaVersion", 1, "kind", "case", "sections", List.of(Map.of("id", "details", "label", section,
                "columns", 1, "fields", List.of(field("summary", "Summary", "text", "case.summary", required)))),
                "actions", List.of(Map.of("id", "save", "label", "Save", "type", "saveData")));
    }

    private int republish(UUID view, Map<String, Object> definition, int revision) throws Exception {
        var saved = api("dana.designer").put(Map.of("expectedRevision", revision, "name", unique("view"), "definition", definition),
                null, VIEWS + "/{id}/draft", PURCHASING, view);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return publish(view, revision + 1);
    }

    private UUID sandboxView(Map<String, Object> definition) throws Exception {
        var created = api("dana.designer").post(Map.of("name", unique("case-view"), "definition", definition), VIEWS, SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        assertThat(api("dana.designer").post(Map.of("expectedRevision", 1), VIEWS + "/{id}/publish", SANDBOX, id).status()).isEqualTo(200);
        return id;
    }

    private int sandboxRepublish(UUID view, Map<String, Object> definition, int revision) throws Exception {
        var saved = api("dana.designer").put(Map.of("expectedRevision", revision, "name", unique("case-view"), "definition", definition),
                null, VIEWS + "/{id}/draft", SANDBOX, view);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        var published = api("dana.designer").post(Map.of("expectedRevision", revision + 1), VIEWS + "/{id}/publish", SANDBOX, view);
        assertThat(published.status()).as(published.toString()).isEqualTo(200);
        return published.body().path("publishedVersion").asInt();
    }
}
