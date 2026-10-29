package com.orqix.apps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S111 page assets: immutable versions, exact route pins, read-only roles and authoritative reference checks. */
class ApplicationPagesApiTest extends PageTestBase {

    @Test
    void routesPinExactPublishedPageVersionsAndDraftEditsNeverChangeThem() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        Pin request = publishedView(requestView());
        UUID page = createPage(requestPage("Buy equipment", request, workflow));
        assertThat(api("dana.designer").post(Map.of("expectedRevision", 1), VIEWS + "/{id}/publish", PURCHASING, page)
                .status()).isEqualTo(403);
        int v1 = publish(page, 1);
        assertThat(v1).isEqualTo(1);
        String slug = unique("buy");

        var denied = api("dana.designer").put(Map.of("expectedRevision", 0, "viewRef", Map.of("id", page.toString(),
                "version", 1)), null, PAGES + "/{slug}", PURCHASING, slug);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(route(slug, 0, page, 1)).isEqualTo(1);

        var runtime = api("rhea.requester").get(PAGES + "/{slug}", PURCHASING, slug);
        assertThat(runtime.status()).as(runtime.toString()).isEqualTo(200);
        assertThat(runtime.body().path("page").path("version").asInt()).isEqualTo(1);
        assertThat(runtime.body().path("page").path("definition").path("title").asString()).isEqualTo("Buy equipment");
        assertThat(runtime.body().path("views").get(0).path("id").asString()).isEqualTo(request.id().toString());
        assertThat(runtime.body().path("grants").path("startWorkflows").toString()).contains(workflow.toString());
        assertThat(api("vic.viewer").get(PAGES + "/{slug}", PURCHASING, slug).body().path("grants")
                .path("startWorkflows").size()).isZero();
        assertThat(api("otto.outsider").get(PAGES + "/{slug}", PURCHASING, slug).status()).isEqualTo(404);
        assertThat(api("rhea.requester").get(PAGES, PURCHASING).body().path("items").toString()).contains(slug);

        var saved = api("dana.designer").put(Map.of("expectedRevision", 1, "name", unique("page"),
                "definition", requestPage("Buy equipment v2", request, workflow)), null, VIEWS + "/{id}/draft", PURCHASING, page);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        assertThat(publish(page, 2)).isEqualTo(2);
        assertThat(api("rhea.requester").get(PAGES + "/{slug}", PURCHASING, slug).body().path("page")
                .path("definition").path("title").asString()).isEqualTo("Buy equipment");

        var stale = api("paul.publisher").put(Map.of("expectedRevision", 0, "viewRef", Map.of("id", page.toString(),
                "version", 2)), null, PAGES + "/{slug}", PURCHASING, slug);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(route(slug, 1, page, 2)).isEqualTo(2);
        assertThat(api("rhea.requester").get(PAGES + "/{slug}", PURCHASING, slug).body().path("page")
                .path("definition").path("title").asString()).isEqualTo("Buy equipment v2");
        assertThat(api("vic.viewer").get(VIEWS + "/{id}/versions/{v}", PURCHASING, page, 1).body().path("definition")
                .path("title").asString()).isEqualTo("Buy equipment");

        var history = api("vic.viewer").get(PAGES + "/{slug}/history", PURCHASING, slug);
        assertThat(history.status()).as(history.toString()).isEqualTo(200);
        assertThat(history.body().path("items").size()).isEqualTo(2);
        assertThat(history.body().path("items").get(0).path("page").path("version").asInt()).isEqualTo(2);
        assertThat(api("rhea.requester").get(PAGES + "/{slug}/history", PURCHASING, slug).status()).isEqualTo(403);

        Map<String, Object> remove = new HashMap<>();
        remove.put("expectedRevision", 2);
        remove.put("viewRef", null);
        assertThat(api("paul.publisher").put(remove, null, PAGES + "/{slug}", PURCHASING, slug).status()).isEqualTo(200);
        assertThat(api("rhea.requester").get(PAGES + "/{slug}", PURCHASING, slug).status()).isEqualTo(404);
        assertThat(api("rhea.requester").get(PAGES, PURCHASING).body().path("items").toString()).doesNotContain(slug);
    }

    @Test
    void incompatibleTargetsAreRejectedWhenSavingAndPublishing() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        Pin request = publishedView(requestView());
        Pin approval = publishedView(approvalView());
        var form = Map.<String, Object>of("id", "form", "type", "view", "view", request.ref(), "workflowId", workflow.toString());
        var decision = Map.<String, Object>of("id", "decision", "type", "view", "view", approval.ref());

        expectInvalid(page("Approval without task", "none", List.of(decision), List.of()), "needs a task page");
        expectInvalid(page("Request without workflow", "none",
                List.of(Map.of("id", "form", "type", "view", "view", request.ref())), List.of()), "needs a workflow");
        expectInvalid(page("Approve a request form", "none", List.of(form, bar("go")),
                List.of(chain("go", List.of(step("approve", "form"))))), "needs a approval view");
        expectInvalid(page("Missing version", "none", List.of(Map.of("id", "form", "type", "view",
                "view", Map.of("id", request.id().toString(), "version", 9), "workflowId", workflow.toString())), List.of()),
                "does not exist");
        expectInvalid(page("No stop", "none", List.of(form, bar("go")), List.of(Map.of("id", "go", "label", "Go",
                "stopOnFailure", false, "steps", List.of(step("submit", "form"))))), "stops at the first failed step");
        expectInvalid(page("Navigate first", "none", List.of(form, bar("go")),
                List.of(chain("go", List.of(navigate("/w/x/tasks"), step("submit", "form"))))), "must be the last step");
        expectInvalid(page("Unknown target", "none", List.of(form, bar("go")),
                List.of(chain("go", List.of(step("submit", "missing"))))), "must reference a view component");
        expectInvalid(page("Two decisions", "task", List.of(decision, bar("go")),
                List.of(chain("go", List.of(step("approve", "decision"), step("reject", "decision"))))), "only one approve");

        // The approval view declares no saveData action and is not a case view.
        expectInvalid(page("Wrong action", "task", List.of(decision, bar("go")),
                List.of(chain("go", List.of(step("saveCaseData", "decision"))))), "needs a case view");
        // A request view whose bindings do not match the workflow input schema.
        Pin mismatched = publishedView(Map.of("schemaVersion", 1, "kind", "request", "sections", List.of(Map.of("id", "s",
                "label", "S", "columns", 1, "fields", List.of(field("other", "Other", "text", "request.other", true)))),
                "actions", List.of(Map.of("id", "submit", "label", "Submit", "type", "submit"))));
        expectInvalid(page("Mismatched", "none", List.of(Map.of("id", "form", "type", "view", "view", mismatched.ref(),
                "workflowId", workflow.toString())), List.of()), "does not match the workflow input schema");
        // A request view without a saveDraft action cannot be the target of a saveDraft step.
        Map<String, Object> submitOnly = new HashMap<>(requestView());
        submitOnly.put("actions", List.of(Map.of("id", "submit", "label", "Submit", "type", "submit")));
        Pin noDraft = publishedView(submitOnly);
        expectInvalid(page("No draft action", "none", List.of(Map.of("id", "form", "type", "view", "view", noDraft.ref(),
                "workflowId", workflow.toString()), bar("go")), List.of(chain("go", List.of(step("saveDraft", "form"))))),
                "has no saveDraft action");
        // Pages do not nest, and only pages can be routed.
        UUID inner = createPage(requestPage("Inner", request, workflow));
        int innerVersion = publish(inner, 1);
        expectInvalid(page("Nested", "none", List.of(Map.of("id", "inner", "type", "view", "view",
                Map.of("id", inner.toString(), "version", innerVersion))), List.of()), "cannot embed a page view");
        var notPage = api("paul.publisher").put(Map.of("expectedRevision", 0, "viewRef", request.ref()), null,
                PAGES + "/{slug}", PURCHASING, unique("form"));
        assertThat(notPage.status()).isEqualTo(422);
        // Read-only roles cannot author pages.
        assertThat(api("vic.viewer").post(Map.of("name", unique("page"), "definition", requestPage("Viewer", request, workflow)),
                VIEWS, PURCHASING).status()).isEqualTo(403);
    }

    private static Map<String, Object> bar(String chain) {
        return Map.of("id", "bar", "type", "actions", "chains", List.of(chain));
    }

    private void expectInvalid(Map<String, Object> definition, String detail) throws Exception {
        var created = api("dana.designer").post(Map.of("name", unique("bad"), "definition", definition), VIEWS, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(422);
        assertThat(created.body().path("detail").asString()).as(created.toString()).contains(detail);
    }
}
