package com.orqix.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.orqix.support.IntegrationTestBase;

/** S126: case type drafts, the check before publishing and publication. Responses are checked against OpenAPI. */
class CaseDraftApiTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    private static String source(String key, String group) throws Exception {
        try (var in = CaseDraftApiTest.class.getResourceAsStream("/cases/manual-required.cmmn")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("lifecycleCase", key)
                    .replace("candidateGroups=\"managers\"", "candidateGroups=\"" + group + "\"");
        }
    }

    @Test void aDraftIsSavedIncompleteCheckedWithEveryProblemAndPublishedAsTheNextVersion() throws Exception {
        String key = "draft-" + UUID.randomUUID().toString().substring(0, 8);
        String base = "/workspaces/" + SANDBOX + "/cases";
        var created = api("dana.designer").post(Map.of("key", key, "name", "Evidence review"), base + "/drafts");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String draft = base + "/drafts/" + created.body().path("id").asString();
        assertThat(created.body().path("version").asInt()).isEqualTo(1);
        assertThat(created.body().path("sourceXml").isNull()).isTrue();
        assertThat(created.body().path("publishedVersion").isNull()).isTrue();
        assertThat(api("dana.designer").post(Map.of("key", key, "name", "Again"), base + "/drafts").status())
                .as("one draft per case key").isEqualTo(409);

        var empty = api("dana.designer").post(null, draft + "/check");
        assertThat(empty.status()).as(empty.toString()).isEqualTo(200);
        assertThat(empty.body().path("valid").asBoolean()).isFalse();
        assertThat(empty.body().path("issues").get(0).path("message").asString()).contains("no CMMN model");

        // Two independent problems are both reported: an unknown group in the model and an unusable data field.
        var broken = api("dana.designer").put(Map.of("expectedVersion", 1, "name", "Evidence review",
                "sourceXml", source(key, "nobody-here"), "designerModel", Map.of("items", List.of()),
                "dataFields", List.of(Map.of("key", "1bad", "label", "", "type", "text"))), null, draft);
        assertThat(broken.status()).as(broken.toString()).isEqualTo(200);
        assertThat(broken.body().path("version").asInt()).isEqualTo(2);
        assertThat(broken.body().path("designerModel").path("items").isArray()).isTrue();
        var issues = api("dana.designer").post(null, draft + "/check").body();
        assertThat(issues.path("draftVersion").asInt()).isEqualTo(2);
        assertThat(issues.path("valid").asBoolean()).isFalse();
        assertThat(issues.path("issues")).extracting(issue -> issue.path("message").asString())
                .containsExactlyInAnyOrder("Case data fields need unique keys, labels and supported types.",
                        "Every candidate group must exist in this workspace.");
        var refused = api("dana.designer").post(Map.of("expectedVersion", 2), draft + "/publish");
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(refused.body().path("detail").asString()).contains("The check found 2 problems");
        assertThat(api("dana.designer").get(base + "/definitions").body()).noneSatisfy(definition ->
                assertThat(definition.path("key").asString()).isEqualTo(key));

        var fixed = api("dana.designer").put(Map.of("expectedVersion", 2, "name", "Evidence review",
                "sourceXml", source(key, "managers"),
                "dataFields", List.of(Map.of("key", "summary", "label", "Summary", "type", "text"))), null, draft);
        assertThat(fixed.status()).as(fixed.toString()).isEqualTo(200);
        assertThat(api("dana.designer").put(Map.of("expectedVersion", 2, "name", "Stale"), null, draft).status())
                .as("a save from an older version is refused").isEqualTo(409);
        var clean = api("dana.designer").post(null, draft + "/check").body();
        assertThat(clean.path("valid").asBoolean()).as(clean.toString()).isTrue();
        assertThat(clean.path("issues")).isEmpty();
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 2), draft + "/publish").status())
                .as("publishing an older draft version is refused").isEqualTo(409);

        var published = api("dana.designer").post(Map.of("expectedVersion", 3), draft + "/publish");
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        assertThat(published.body().path("key").asString()).isEqualTo(key);
        assertThat(published.body().path("version").asInt()).isEqualTo(1);
        assertThat(published.body().path("dataFields").get(0).path("key").asString()).isEqualTo("summary");
        var after = api("dana.designer").get(draft).body();
        assertThat(after.path("publishedVersion").asInt()).isEqualTo(1);
        assertThat(after.path("publishedDraftVersion").asInt()).isEqualTo(3);
        var started = api("rhea.requester").post(Map.of(), "start-" + key,
                base + "/definitions/" + published.body().path("id").asString() + "/instances");
        assertThat(started.status()).as(started.toString()).isEqualTo(201);

        // The draft stays as the working copy of the next version.
        assertThat(api("dana.designer").put(Map.of("expectedVersion", 3, "name", "Evidence review v2",
                "sourceXml", source(key, "managers")), null, draft).status()).isEqualTo(200);
        var second = api("dana.designer").post(Map.of("expectedVersion", 4), draft + "/publish");
        assertThat(second.status()).as(second.toString()).isEqualTo(201);
        assertThat(second.body().path("version").asInt()).isEqualTo(2);
        var listed = api("dana.designer").get(base + "/drafts").body();
        assertThat(listed).anySatisfy(row -> {
            assertThat(row.path("key").asString()).isEqualTo(key);
            assertThat(row.path("publishedVersion").asInt()).isEqualTo(2);
            assertThat(row.path("publishedDraftVersion").asInt()).isEqualTo(4);
        });

        var audit = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action='case.draft.saved' AND resource_id=:id ORDER BY occurred_at")
                .param("id", created.body().path("id").asString()).query(String.class).list();
        assertThat(audit).hasSize(4).allSatisfy(metadata -> assertThat(metadata).contains(key).doesNotContain("definitions"));

        assertThat(api("dana.designer").delete(draft).status()).isEqualTo(204);
        assertThat(api("dana.designer").get(draft).status()).isEqualTo(404);
        assertThat(api("dana.designer").get(base + "/definitions").body()).filteredOn(definition ->
                definition.path("key").asString().equals(key)).hasSize(2);
    }

    @Test void onlyAuthorsSeeDraftsAndOtherWorkspacesSeeNothing() throws Exception {
        String key = "draft-" + UUID.randomUUID().toString().substring(0, 8);
        String base = "/workspaces/" + SANDBOX + "/cases/drafts";
        var created = api("dana.designer").post(Map.of("key", key, "name", "Private draft"), base);
        assertThat(created.status()).isEqualTo(201);
        String draft = base + "/" + created.body().path("id").asString();
        assertThat(api("rhea.requester").get(base).status()).isEqualTo(403);
        assertThat(api("rhea.requester").get(draft).status()).isEqualTo(403);
        assertThat(api("rhea.requester").post(null, draft + "/check").status()).isEqualTo(403);
        assertThat(api("otto.outsider").get(draft).status()).isEqualTo(404);
        assertThat(api("dana.designer").get("/workspaces/" + PURCHASING + "/cases/drafts/" + created.body().path("id").asString()).status())
                .as("a draft belongs to its workspace").isEqualTo(404);
        assertThat(api("dana.designer").post(Map.of("key", "Bad Key", "name", "x"), base).status()).isEqualTo(422);
        assertThat(api("dana.designer").put(Map.of("expectedVersion", 1, "name", "x",
                "dataFields", List.of("not an object")), null, draft).status()).isEqualTo(422);
    }
}
