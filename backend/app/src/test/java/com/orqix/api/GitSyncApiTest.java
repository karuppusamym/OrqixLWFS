package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.GitStandIn;
import com.orqix.support.IntegrationTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-S01 Git sync (docs/build/S147) against the WireMock GitHub and GitLab stand-ins: connection settings and their
 * permissions, push to a review branch with a pull request, the deterministic layout without bindings or secrets,
 * import with differences, and publish only from an approved commit whose content hash equals the draft's.
 */
class GitSyncApiTest extends IntegrationTestBase {

    private static final String CONNECTION = "/workspaces/{ws}/git-connection";
    private static final String GIT = "/workspaces/{ws}/workflows/{id}/git";

    @Autowired OrqixProperties properties;
    @Autowired JdbcClient jdbc;

    private final GitStandIn git = GitStandIn.get();
    private String token;
    private String ref;
    private Path tokenFile;

    @BeforeEach
    void credential() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        token = "standin-token-" + UUID.randomUUID();
        ref = "git-sync-" + suffix;
        Files.createDirectories(properties.secrets().dir());
        tokenFile = properties.secrets().dir().resolve(ref);
        Files.writeString(tokenFile, token + "\n");
    }

    @AfterEach
    void cleanup() throws Exception {
        JsonNode state = api("wade.admin").get(CONNECTION, SANDBOX).body();
        if (state.path("configured").asBoolean()) {
            api("wade.admin").delete(CONNECTION + "?expectedVersion=" + state.path("connection").path("version").asInt(),
                    SANDBOX);
        }
        Files.deleteIfExists(tokenFile);
    }

    private Response connect(String provider, String apiUrl, String repository) throws Exception {
        JsonNode state = api("wade.admin").get(CONNECTION, SANDBOX).body();
        int version = state.path("configured").asBoolean() ? state.path("connection").path("version").asInt() : 0;
        Map<String, Object> body = new HashMap<>();
        body.put("provider", provider);
        body.put("apiUrl", apiUrl);
        body.put("repository", repository);
        body.put("credentialRef", ref);
        body.put("expectedVersion", version);
        return api("wade.admin").put(body, null, CONNECTION, SANDBOX);
    }

    private record Draft(UUID id, String key, int revision, ObjectNode definition) {
    }

    private Draft workflow() throws Exception {
        String key = "gs-" + UUID.randomUUID().toString().substring(0, 8);
        var created = api("dana.designer").post(Map.of("key", key, "name", "Git " + key, "templateId", "purchase-approval"),
                "/workspaces/{ws}/workflows", SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", SANDBOX, id)
                .body().path("definition");
        ((ObjectNode) definition.path("nodes").get(1)).put("label", "Exported by Git sync tests");
        return save(new Draft(id, key, 1, definition));
    }

    private Draft save(Draft draft) throws Exception {
        var saved = api("dana.designer").put(Map.of("definition", draft.definition()), "\"r" + draft.revision() + "\"",
                "/workspaces/{ws}/workflows/{id}/draft", SANDBOX, draft.id());
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return new Draft(draft.id(), draft.key(), draft.revision() + 1, draft.definition());
    }

    private Response push(String user, Draft draft) throws Exception {
        return api(user).post(Map.of("draftRevision", draft.revision()), GIT + "/push", SANDBOX, draft.id());
    }

    private Response publish(Draft draft, int revision, String commit, String pr) throws Exception {
        return api("dana.designer").post(Map.of("draftRevision", revision, "commit", commit, "pullRequest", pr,
                "acknowledgeWarnings", true), UUID.randomUUID().toString(), GIT + "/publish", SANDBOX, draft.id());
    }

    private static String text(Map<String, byte[]> files, String path) {
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    @Test
    void gitHubPushReviewImportAndHashCheckedPublish() throws Exception {
        GitStandIn.Repo repo = git.create("github", "acme/workflows-" + ref, token);
        String repository = "acme/workflows-" + ref;

        // Connection: managers only, a reference never a token, egress-checked URL, reserved references refused.
        Map<String, Object> bad = new HashMap<>(Map.of("provider", "github", "repository", repository,
                "credentialRef", ref, "expectedVersion", 0));
        assertThat(api("dana.designer").put(bad, null, CONNECTION, SANDBOX).status()).isEqualTo(403);
        bad.put("credentialRef", "webhook-signing");
        assertThat(api("wade.admin").put(bad, null, CONNECTION, SANDBOX).status()).isEqualTo(422);
        bad.put("credentialRef", ref);
        bad.put("apiUrl", "https://git.unlisted.example");
        assertThat(api("wade.admin").put(bad, null, CONNECTION, SANDBOX).code()).isEqualTo("EGRESS_NOT_ALLOWED");
        bad.put("apiUrl", git.baseUrl() + "/github");
        bad.put("repository", "only-one-segment");
        assertThat(api("wade.admin").put(bad, null, CONNECTION, SANDBOX).status()).isEqualTo(422);
        var saved = connect("github", git.baseUrl() + "/github", repository);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        assertThat(saved.body().path("connection").path("baseBranch").asString()).isEqualTo("main");
        assertThat(saved.body().path("connection").path("basePath").asString()).isEqualTo("orqix");
        assertThat(api("dana.designer").get(CONNECTION, SANDBOX).body().path("connection").path("credentialRef").isNull())
                .as("only connection managers see the reference").isTrue();
        assertThat(api("wade.admin").get(CONNECTION, SANDBOX).body().toString()).doesNotContain(token);
        var checked = api("wade.admin").post(Map.of(), CONNECTION + "/check", SANDBOX);
        assertThat(checked.body().path("reachable").asBoolean()).as(checked.toString()).isTrue();
        assertThat(checked.body().path("baseBranchExists").asBoolean()).isTrue();

        // Push: a review branch, a pull request, the deterministic layout, no bindings or secrets.
        Draft draft = workflow();
        assertThat(push("olga.operator", draft).status()).isEqualTo(403);
        var pushed = push("dana.designer", draft);
        assertThat(pushed.status()).as(pushed.toString()).isEqualTo(200);
        String branch = pushed.body().path("branch").asString();
        String first = pushed.body().path("commit").asString();
        assertThat(branch).isEqualTo("orqix/" + draft.key());
        assertThat(pushed.body().path("pullRequest").path("id").asString()).isEqualTo("1");
        assertThat(pushed.body().path("pullRequest").path("state").asString()).isEqualTo("open");
        assertThat(repo.head(branch)).isEqualTo(first);
        String folder = "orqix/workflows/" + draft.key() + "/";
        Map<String, byte[]> files = repo.files(branch);
        assertThat(files).containsKeys(folder + "workflow.json", folder + "orqix-sync.json",
                folder + "generated/process.bpmn20.xml", folder + "generated/references.json", "README.md");
        assertThat(files.keySet().stream().filter(p -> p.startsWith(folder + "generated/connections/"))).isNotEmpty();
        assertThat(files).doesNotContainKey(folder + "tests.json");
        JsonNode workflowFile = json.parse(files.get(folder + "workflow.json"));
        assertThat(workflowFile.path("workflowKey").asString()).isEqualTo(draft.key());
        assertThat(workflowFile.has("provenance")).isFalse();
        String everything = files.values().stream().map(b -> new String(b, StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertThat(everything).doesNotContain(token).doesNotContain(Stub.baseUrl()).doesNotContain(ref)
                .doesNotContain("secretRef");
        assertThat(json.parse(files.get(folder + "orqix-sync.json")).path("contentHash").asString())
                .isEqualTo(pushed.body().path("contentHash").asString());
        assertThat(git.requests.stream().filter(r -> r[0].contains(repository.split("/")[1]))
                .allMatch(r -> ("Bearer " + token).equals(r[1]))).isTrue();

        // The same draft again: nothing to commit, the same pull request.
        var again = push("dana.designer", draft);
        assertThat(again.body().path("unchanged").asBoolean()).as(again.toString()).isTrue();
        assertThat(again.body().path("commit").asString()).isEqualTo(first);
        assertThat(repo.prs).hasSize(1);

        // Not reviewed yet: refused.
        var early = publish(draft, draft.revision(), first, "1");
        assertThat(early.code()).as(early.toString()).isEqualTo("GIT_REVIEW_REQUIRED");

        // Approved on its head commit: published, with the commit and hash in the release and its evidence pack.
        repo.pr(1).approve("rita-reviewer");
        var published = publish(draft, draft.revision(), first, "1");
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        JsonNode source = published.body().path("release").path("manifest").path("gitSource");
        assertThat(source.path("commit").asString()).isEqualTo(first);
        assertThat(source.path("contentHash").asString()).isEqualTo(pushed.body().path("contentHash").asString());
        assertThat(source.path("approvedBy").toString()).contains("rita-reviewer");
        assertThat(source.path("branch").asString()).isEqualTo(branch);
        String releaseId = published.body().path("release").path("id").asString();
        var pack = api("dana.designer").get("/workspaces/{ws}/workflows/{wf}/releases/{rel}/evidence-pack?format=json",
                SANDBOX, draft.id(), releaseId);
        assertThat(pack.status()).as(pack.toString()).isEqualTo(200);
        assertThat(pack.body().path("gitSource").path("commit").asString()).isEqualTo(first);
        assertThat(pack.body().path("controls").path("publishedFromReviewedCommit").asBoolean()).isTrue();

        // A reviewer changes a step label in Git: the old approval no longer covers the new head.
        Map<String, byte[]> edited = new HashMap<>();
        ObjectNode changed = (ObjectNode) workflowFile.deepCopy();
        ((ObjectNode) changed.path("nodes").get(1)).put("label", "Reviewed in Git");
        edited.put(folder + "workflow.json", json.write(changed).getBytes(StandardCharsets.UTF_8));
        String second = repo.push(branch, edited, List.of());
        var notApproved = publish(draft, draft.revision(), second, "1");
        assertThat(notApproved.code()).as(notApproved.toString()).isEqualTo("GIT_REVIEW_REQUIRED");
        repo.pr(1).approve("rita-reviewer");
        var mismatch = publish(draft, draft.revision(), second, "1");
        assertThat(mismatch.code()).as(mismatch.toString()).isEqualTo("GIT_CONTENT_MISMATCH");

        // Preview and import: the change is listed; the import is a new draft with the commit in provenance.
        var preview = api("dana.designer").get(GIT + "/import-preview?branch=" + branch, SANDBOX, draft.id());
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);
        assertThat(preview.body().path("commit").asString()).isEqualTo(second);
        assertThat(preview.body().path("identical").asBoolean()).isFalse();
        assertThat(preview.body().path("changes").toString()).contains("Reviewed in Git").contains("changed");
        assertThat(api("dana.designer").post(Map.of("branch", branch, "commit", second, "expectedDraftRevision", 1),
                GIT + "/import", SANDBOX, draft.id()).status()).isEqualTo(412);
        var imported = api("dana.designer").post(Map.of("branch", branch, "commit", second, "expectedDraftRevision",
                draft.revision(), "pullRequest", "1"), GIT + "/import", SANDBOX, draft.id());
        assertThat(imported.status()).as(imported.toString()).isEqualTo(200);
        int importedRevision = imported.body().path("draftRevision").asInt();
        assertThat(importedRevision).isEqualTo(draft.revision() + 1);
        JsonNode importedDraft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", SANDBOX, draft.id())
                .body().path("definition");
        assertThat(importedDraft.path("provenance").path("gitCommit").asString()).isEqualTo(second);
        assertThat(importedDraft.path("nodes").get(1).path("label").asString()).isEqualTo("Reviewed in Git");

        // The imported draft matches the reviewed commit (provenance is outside the hash): published.
        var fromImport = publish(draft, importedRevision, second, "1");
        assertThat(fromImport.status()).as(fromImport.toString()).isEqualTo(201);
        assertThat(fromImport.body().path("release").path("manifest").path("provenance").path("gitCommit").asString())
                .isEqualTo(second);

        // History and audit: every push, pull and publish.
        JsonNode events = api("dana.designer").get(GIT + "/events", SANDBOX, draft.id()).body();
        List<String> directions = new java.util.ArrayList<>();
        events.forEach(e -> directions.add(e.path("direction").asString()));
        assertThat(directions).containsExactly("publish", "pull", "publish", "push", "push");
        for (String action : List.of("git.pushed", "git.pulled", "git.published", "git.connection.saved")) {
            assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND action = :a")
                    .param("ws", SANDBOX).param("a", action).query(Integer.class).single()).as(action).isPositive();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND metadata::text LIKE :t")
                .param("ws", SANDBOX).param("t", "%" + token + "%").query(Integer.class).single()).isZero();

        // A draft that carries something credential-like is never pushed.
        int commits = repo.commitsCount();
        ObjectNode leaking = draft.definition().deepCopy();
        ((ObjectNode) leaking.path("nodes").get(1)).put("label", "Token ghp_" + "A".repeat(36));
        Draft leak = save(new Draft(draft.id(), draft.key(), importedRevision, leaking));
        var refused = push("dana.designer", leak);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(repo.commitsCount()).isEqualTo(commits);
    }

    @Test
    void gitLabMergeRequestMergedPublishAndTestSuiteImport() throws Exception {
        String repository = "acme/platform/wf-" + ref;
        GitStandIn.Repo repo = git.create("gitlab", repository, token);
        var saved = connect("gitlab", git.baseUrl() + "/gitlab/api/v4", repository);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);

        Draft draft = workflow();
        var pushed = push("dana.designer", draft);
        assertThat(pushed.status()).as(pushed.toString()).isEqualTo(200);
        String branch = pushed.body().path("branch").asString();
        String commit = pushed.body().path("commit").asString();
        assertThat(pushed.body().path("pullRequest").path("url").asString()).contains("merge_requests/1");
        String folder = "orqix/workflows/" + draft.key() + "/";
        assertThat(repo.files(branch)).containsKey(folder + "workflow.json");

        // A second push after a draft change updates the files (GitLab update actions) on the same merge request.
        ObjectNode changed = draft.definition().deepCopy();
        ((ObjectNode) changed.path("nodes").get(1)).put("label", "Second revision");
        draft = save(new Draft(draft.id(), draft.key(), draft.revision(), changed));
        var second = push("dana.designer", draft);
        assertThat(second.status()).as(second.toString()).isEqualTo(200);
        assertThat(second.body().path("unchanged").asBoolean()).isFalse();
        commit = second.body().path("commit").asString();
        assertThat(json.parse(repo.files(branch).get(folder + "workflow.json")).path("nodes").get(1).path("label").asString())
                .isEqualTo("Second revision");
        assertThat(repo.prs).hasSize(1);

        // Merged without an approval: refused. Approved and merged: published from the merge commit.
        String unapprovedMerge = repo.pr(1).merge();
        assertThat(publish(draft, draft.revision(), unapprovedMerge, "1").code()).isEqualTo("GIT_REVIEW_REQUIRED");
        repo.pr(1).approve("mira-maintainer");
        var published = publish(draft, draft.revision(), unapprovedMerge, "1");
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        assertThat(published.body().path("gitSource").path("reviewState").asString()).isEqualTo("merged");
        assertThat(published.body().path("gitSource").path("approvedBy").toString()).contains("mira-maintainer");
        assertThat(commit).isNotEqualTo(unapprovedMerge);

        // A maintainer adds shared tests in Git: the preview lists them and the import saves a suite version.
        ObjectNode tests = json.object().put("formatVersion", "1");
        tests.putArray("cases").addObject().put("id", "small").put("name", "Small purchase").put("expect", "completed")
                .putObject("input").put("amount", 10);
        String withTests = repo.push("main", Map.of(folder + "tests.json", json.write(tests).getBytes(StandardCharsets.UTF_8)),
                List.of());
        var preview = api("dana.designer").get(GIT + "/import-preview?branch=main", SANDBOX, draft.id());
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);
        assertThat(preview.body().path("testsIncluded").asBoolean()).isTrue();
        assertThat(preview.body().path("changes").toString()).contains("\"area\":\"test\"").contains("Small purchase");
        var imported = api("dana.designer").post(Map.of("branch", "main", "commit", withTests, "expectedDraftRevision",
                draft.revision()), GIT + "/import", SANDBOX, draft.id());
        assertThat(imported.status()).as(imported.toString()).isEqualTo(200);
        assertThat(imported.body().path("testsChanged").asBoolean()).isTrue();
        assertThat(imported.body().path("testSuiteVersion").asInt()).isEqualTo(1);
        assertThat(imported.body().path("draftRevision").asInt()).as("only the tests changed").isEqualTo(draft.revision());

        // Removing tests.json through Git is refused; unknown members in a case are refused.
        String removed = repo.push("main", Map.of(), List.of(folder + "tests.json"));
        assertThat(api("dana.designer").post(Map.of("branch", "main", "commit", removed, "expectedDraftRevision",
                draft.revision()), GIT + "/import", SANDBOX, draft.id()).status()).isEqualTo(422);
        ((ObjectNode) tests.path("cases").get(0)).put("secretSauce", true);
        String unknown = repo.push("main", Map.of(folder + "tests.json", json.write(tests).getBytes(StandardCharsets.UTF_8)),
                List.of());
        assertThat(api("dana.designer").post(Map.of("branch", "main", "commit", unknown, "expectedDraftRevision",
                draft.revision()), GIT + "/import", SANDBOX, draft.id()).status()).isEqualTo(422);
        assertThat(git.requests.stream().filter(r -> r[0].contains("wf-" + ref)).allMatch(r -> token.equals(r[1]))).isTrue();
    }
}
