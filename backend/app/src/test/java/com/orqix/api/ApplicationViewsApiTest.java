package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

/** UI02 registry API and immutable published snapshots on migrated PostgreSQL. */
class ApplicationViewsApiTest extends RuntimeTestBase {
    private static final String BASE = "/workspaces/{ws}/views";

    private static Map<String, Object> definition(String label) {
        return Map.of("schemaVersion", 1, "kind", "request",
                "sections", List.of(Map.of("id", "details", "label", label, "columns", 2,
                        "fields", List.of(Map.of("id", "amount", "label", "Amount", "type", "number",
                                "binding", "request.amount", "required", true)))),
                "actions", List.of(Map.of("id", "save", "label", "Save", "type", "saveDraft"),
                        Map.of("id", "submit", "label", "Submit", "type", "submit")));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void draftCopyPublishAndPinnedVersionSurviveFurtherEdits() throws Exception {
        var created = api("dana.designer").post(Map.of("name", unique("expense"), "definition", definition("Original")),
                BASE, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        assertThat(created.body().path("draftRevision").asInt()).isEqualTo(1);
        assertThat(created.body().path("publishedVersion").isNull()).isTrue();
        assertThat(api("vic.viewer").get(BASE + "/{id}", PURCHASING, id).status()).isEqualTo(200);
        assertThat(api("dana.designer").get(BASE, PURCHASING).body().path("items").toString()).contains(id.toString());

        var copied = api("dana.designer").post(Map.of("name", unique("expense-copy")), BASE + "/{id}/copy", PURCHASING, id);
        assertThat(copied.status()).as(copied.toString()).isEqualTo(201);
        assertThat(copied.body().path("definition").path("sections").get(0).path("label").asString()).isEqualTo("Original");
        assertThat(copied.body().path("publishedVersion").isNull()).isTrue();

        assertThat(api("dana.designer").post(Map.of("expectedRevision", 1), BASE + "/{id}/publish", PURCHASING, id)
                .status()).isEqualTo(403);
        var published = api("paul.publisher").post(Map.of("expectedRevision", 1), BASE + "/{id}/publish", PURCHASING, id);
        assertThat(published.status()).as(published.toString()).isEqualTo(200);
        assertThat(published.body().path("publishedVersion").asInt()).isEqualTo(1);
        assertThat(api("paul.publisher").post(Map.of("expectedRevision", 1), BASE + "/{id}/publish", PURCHASING, id)
                .status()).isEqualTo(422);

        var saved = api("dana.designer").put(Map.of("expectedRevision", 1, "name", unique("edited"),
                "definition", definition("Edited")), null, BASE + "/{id}/draft", PURCHASING, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        assertThat(saved.body().path("draftRevision").asInt()).isEqualTo(2);
        assertThat(saved.body().path("publishedVersion").asInt()).isEqualTo(1);
        assertThat(api("dana.designer").put(Map.of("expectedRevision", 1, "name", unique("stale"),
                "definition", definition("Stale")), null, BASE + "/{id}/draft", PURCHASING, id).status()).isEqualTo(409);
        assertThat(api("paul.publisher").put(Map.of("expectedRevision", 2, "name", unique("denied"),
                "definition", definition("Denied")), null, BASE + "/{id}/draft", PURCHASING, id).status()).isEqualTo(403);

        var v1 = api("vic.viewer").get(BASE + "/{id}/versions/{v}", PURCHASING, id, 1);
        assertThat(v1.status()).as(v1.toString()).isEqualTo(200);
        assertThat(v1.body().path("definition").path("sections").get(0).path("label").asString()).isEqualTo("Original");
        assertThat(api("paul.publisher").post(Map.of("expectedRevision", 1), BASE + "/{id}/publish", PURCHASING, id)
                .status()).isEqualTo(409);
        var v2 = api("paul.publisher").post(Map.of("expectedRevision", 2), BASE + "/{id}/publish", PURCHASING, id);
        assertThat(v2.status()).as(v2.toString()).isEqualTo(200);
        assertThat(v2.body().path("publishedVersion").asInt()).isEqualTo(2);
        assertThat(api("vic.viewer").get(BASE + "/{id}/versions/{v}", PURCHASING, id, 1).body()
                .path("definition").path("sections").get(0).path("label").asString()).isEqualTo("Original");
        assertThat(api("vic.viewer").get(BASE + "/{id}/versions/{v}", PURCHASING, id, 2).body()
                .path("definition").path("sections").get(0).path("label").asString()).isEqualTo("Edited");
    }

    @Test
    void workspaceIsolationAndStrictSchema() throws Exception {
        var created = api("dana.designer").post(Map.of("name", unique("private"), "definition", definition("Private")),
                BASE, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        assertThat(api("fred.facilities").get(BASE, PURCHASING).status()).isEqualTo(404);
        assertThat(api("fred.facilities").get(BASE + "/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(api("dana.designer").get(BASE + "/{id}", FACILITIES, id).status()).isIn(403, 404);
        assertThat(api("vic.viewer").post(Map.of("name", unique("viewer"), "definition", definition("No")),
                BASE, PURCHASING).status()).isEqualTo(403);
        assertThat(api("dana.designer").post(Map.of("name", unique("bad"), "definition",
                Map.of("schemaVersion", 1, "kind", "request", "sections", List.of(), "actions", List.of())),
                BASE, PURCHASING).status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("name", unique("script"), "definition",
                Map.of("schemaVersion", 1, "kind", "request", "sections", List.of(Map.of("id", "s", "label", "S",
                        "columns", 1, "fields", List.of(Map.of("id", "f", "label", "F", "type", "text",
                                "binding", "request.f", "onClick", "alert(1)")))), "actions", List.of())),
                BASE, PURCHASING).status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("name", unique("foreign")), BASE + "/{id}/copy", FACILITIES, id)
                .status()).isIn(403, 404);
        assertThat(api("dana.designer").get(BASE + "/{id}/versions/{v}", PURCHASING, id, 1).status())
                .isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM application_view WHERE workspace_id = :ws AND id = :id")
                .param("ws", PURCHASING).param("id", id).query(Integer.class).single()).isEqualTo(1);
    }
}
