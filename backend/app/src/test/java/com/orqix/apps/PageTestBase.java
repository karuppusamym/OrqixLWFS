package com.orqix.apps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/** Fixtures for S111 application page tests: published views matching the purchase template, pages and routes. */
abstract class PageTestBase extends RuntimeTestBase {
    static final String VIEWS = "/workspaces/{ws}/views";
    static final String PAGES = "/workspaces/{ws}/app-pages";

    record Pin(UUID id, int version) {
        Map<String, Object> ref() {
            return Map.of("id", id.toString(), "version", version);
        }
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static Map<String, Object> field(String id, String label, String type, String binding, boolean required) {
        Map<String, Object> field = new LinkedHashMap<>(Map.of("id", id, "label", label, "type", type, "binding", binding,
                "required", required));
        if (type.equals("choice")) {
            field.put("options", List.of(Map.of("value", "OPS", "label", "OPS"), Map.of("value", "IT", "label", "IT"),
                    Map.of("value", "HR", "label", "HR")));
        }
        return field;
    }

    static Map<String, Object> requestView() {
        return Map.of("schemaVersion", 1, "kind", "request",
                "sections", List.of(Map.of("id", "details", "label", "Purchase", "columns", 2, "fields", List.of(
                        field("title", "What are you buying?", "text", "request.title", true),
                        field("purpose", "Business purpose", "text", "request.purpose", true),
                        field("costCenter", "Cost center", "choice", "request.costCenter", true),
                        field("amount", "Amount (USD)", "number", "request.amount", true)))),
                "actions", List.of(Map.of("id", "submit", "label", "Submit", "type", "submit"),
                        Map.of("id", "draft", "label", "Save draft", "type", "saveDraft")));
    }

    static Map<String, Object> approvalView() {
        return Map.of("schemaVersion", 1, "kind", "approval",
                "sections", List.of(Map.of("id", "summary", "label", "Summary", "columns", 1, "fields", List.of(
                        field("title", "Item", "text", "request.title", true),
                        field("amount", "Amount", "number", "request.amount", true)))),
                "actions", List.of(Map.of("id", "approve", "label", "Approve", "type", "approve"),
                        Map.of("id", "reject", "label", "Reject", "type", "reject")));
    }

    static Map<String, Object> chain(String id, List<Map<String, Object>> steps) {
        return Map.of("id", id, "label", "Run " + id, "stopOnFailure", true, "steps", steps);
    }

    static Map<String, Object> step(String action, String component) {
        return Map.of("action", action, "component", component);
    }

    static Map<String, Object> navigate(String target) {
        return Map.of("action", "navigate", "target", target);
    }

    static Map<String, Object> page(String title, String context, List<Map<String, Object>> components,
            List<Map<String, Object>> chains) {
        return Map.of("schemaVersion", 1, "kind", "page", "title", title, "context", context, "layout", "single",
                "breakpoint", "md", "regions", List.of(Map.of("id", "main", "label", "Main", "components", components)),
                "chains", chains);
    }

    /** A request page: the form, an action bar with draft and submit chains. */
    static Map<String, Object> requestPage(String title, Pin request, UUID workflow) {
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(Map.of("id", "intro", "type", "heading", "text", title));
        components.add(Map.of("id", "form", "type", "view", "view", request.ref(), "workflowId", workflow.toString()));
        components.add(Map.of("id", "bar", "type", "actions", "chains", List.of("draft", "send")));
        return page(title, "none", components, List.of(
                chain("draft", List.of(step("saveDraft", "form"))),
                chain("send", List.of(step("submit", "form"), navigate("/w/" + PURCHASING + "/requests")))));
    }

    Pin publishedView(Map<String, Object> definition) throws Exception {
        var created = api("dana.designer").post(Map.of("name", unique("view"), "definition", definition), VIEWS, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        var published = api("paul.publisher").post(Map.of("expectedRevision", 1), VIEWS + "/{id}/publish", PURCHASING, id);
        assertThat(published.status()).as(published.toString()).isEqualTo(200);
        return new Pin(id, published.body().path("publishedVersion").asInt());
    }

    UUID createPage(Map<String, Object> definition) throws Exception {
        var created = api("dana.designer").post(Map.of("name", unique("page"), "definition", definition), VIEWS, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return UUID.fromString(created.body().path("id").asString());
    }

    int publish(UUID page, int revision) throws Exception {
        var published = api("paul.publisher").post(Map.of("expectedRevision", revision), VIEWS + "/{id}/publish", PURCHASING, page);
        assertThat(published.status()).as(published.toString()).isEqualTo(200);
        return published.body().path("publishedVersion").asInt();
    }

    int route(String slug, int expectedRevision, UUID page, int version) throws Exception {
        var routed = api("paul.publisher").put(Map.of("expectedRevision", expectedRevision, "viewRef",
                Map.of("id", page.toString(), "version", version)), null, PAGES + "/{slug}", PURCHASING, slug);
        assertThat(routed.status()).as(routed.toString()).isEqualTo(200);
        return routed.body().path("revision").asInt();
    }

    JsonNode runChain(String user, String slug, String chain, String key, Map<String, Object> body) throws Exception {
        var run = api(user).post(body, key, PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, chain);
        assertThat(run.status()).as(run.toString()).isEqualTo(200);
        return run.body();
    }

    static Map<String, Object> purchase(String title) {
        return Map.of("title", title, "purpose", "Device failed", "costCenter", "OPS", "amount", 120.5);
    }
}
