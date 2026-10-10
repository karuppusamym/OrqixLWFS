package com.orqix.migration;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Multi-file migration projects: dependency order, blocked plans, ordered conversion, isolation and permissions. */
class MigrationProjectApiTest extends RuntimeTestBase {
    private static final String PROJECTS = "/workspaces/{ws}/migrations/projects";

    /** A process whose call activities {@code call1..n} invoke the given processes in sequence. */
    private static String bpmn(String processId, String... called) {
        StringBuilder xml = new StringBuilder("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"urn:test\"><process id=\"")
                .append(processId).append("\" isExecutable=\"true\"><startEvent id=\"start\"/>");
        String previous = "start"; StringBuilder flows = new StringBuilder();
        for (int i = 0; i < called.length; i++) {
            String id = "call" + (i + 1);
            xml.append("<callActivity id=\"").append(id).append("\" calledElement=\"").append(called[i]).append("\"/>");
            flows.append("<sequenceFlow id=\"f").append(i).append("\" sourceRef=\"").append(previous).append("\" targetRef=\"").append(id).append("\"/>");
            previous = id;
        }
        return xml.append("<endEvent id=\"end\"/>").append(flows).append("<sequenceFlow id=\"last\" sourceRef=\"").append(previous)
                .append("\" targetRef=\"end\"/></process></definitions>").toString();
    }
    private static Map<String, String> file(String name, String content) { return Map.of("fileName", name, "content", content); }
    private Response upload(String user, UUID ws, String name, List<Map<String, String>> files) throws Exception {
        return api(user).post(Map.of("name", name, "files", files), PROJECTS, ws);
    }
    private JsonNode uploaded(String user, UUID ws, List<Map<String, String>> files) throws Exception {
        var response = upload(user, ws, "Purchase project", files);
        assertThat(response.status()).as(response.toString()).isEqualTo(201); return response.body();
    }
    private static String migrationOf(JsonNode project, String fileName) {
        for (var file : project.path("files")) if (fileName.equals(file.path("fileName").asString())) return file.path("migrationId").asString();
        throw new AssertionError("No file " + fileName + " in " + project);
    }
    private static List<String> codes(JsonNode project) { List<String> codes = new ArrayList<>(); project.path("issues").forEach(i -> codes.add(i.path("code").asString())); return codes; }
    private ObjectNode mappings(String key, String... calls) {
        var request = json.object(); request.put("workflowKey", key); request.put("name", "Imported " + key);
        request.put("acknowledgeInfo", true); request.put("acknowledgeReview", true);
        var children = json.object();
        for (int i = 0; i < calls.length; i += 2) children.set(calls[i], json.toTree(Map.of("workflowRef", calls[i + 1], "inputMapping", Map.of(), "resultMapping", Map.of())));
        if (calls.length > 0) request.set("childWorkflowMappings", children);
        return request;
    }
    private Response convertNext(String user, UUID ws, JsonNode project, String expected, ObjectNode mappings) throws Exception {
        return api(user).post(Map.of("expectedMigrationId", expected, "mappings", mappings), PROJECTS + "/{id}/convert-next", ws, project.path("id").asString());
    }
    private JsonNode project(UUID ws, String id) throws Exception {
        var response = api("dana.designer").get(PROJECTS + "/{id}", ws, id);
        assertThat(response.status()).as(response.toString()).isEqualTo(200); return response.body();
    }
    private void publish(UUID workflow) throws Exception {
        var published = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true), UUID.randomUUID().toString(),
                "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
    }
    private int count(String sql, Object... params) {
        var statement = jdbc.sql(sql); for (int i = 0; i < params.length; i++) statement = statement.param("p" + (i + 1), params[i]);
        return statement.query(Integer.class).single();
    }
    private int auditCount(String action, String resource) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action=:action AND resource_id=:id").param("action", action).param("id", resource).query(Integer.class).single();
    }

    @Test void plansChildrenBeforeParentsAndConvertsInThatOrderWithPerFileStatus() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String leafKey = "prj-leaf-" + suffix, midKey = "prj-mid-" + suffix, rootKey = "prj-root-" + suffix;
        // Uploaded parent-first on purpose: the order must come from the call-activity dependencies, not the upload order.
        var project = uploaded("dana.designer", PURCHASING, List.of(file("root.bpmn", bpmn("root-" + suffix, "mid-" + suffix)),
                file("mid.bpmn", bpmn("mid-" + suffix, "leaf-" + suffix)), file("leaf.bpmn", bpmn("leaf-" + suffix))));
        String leaf = migrationOf(project, "leaf.bpmn"), mid = migrationOf(project, "mid.bpmn"), root = migrationOf(project, "root.bpmn");
        assertThat(project.path("status").asString()).isEqualTo("analyzed"); assertThat(project.path("issues").isEmpty()).isTrue();
        List<String> order = new ArrayList<>(); project.path("conversionOrder").forEach(id -> order.add(id.asString()));
        assertThat(order).containsExactly(leaf, mid, root);
        assertThat(project.path("files").get(0).path("dependencies").get(0).asString()).isEqualTo("mid-" + suffix);
        for (var file : project.path("files")) { assertThat(file.path("status").asString()).isEqualTo("analyzed"); assertThat(file.path("convertedWorkflowId").isNull()).isTrue(); }
        assertThat(auditCount("migration.project.created", project.path("id").asString())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata->>'fileCount' FROM audit_event WHERE action='migration.project.created' AND resource_id=:id").param("id", project.path("id").asString()).query(String.class).single()).isEqualTo("3");
        assertThat(jdbc.sql("SELECT count(*) FROM migration_job WHERE workspace_id=:ws AND id IN (:ids)").param("ws", PURCHASING)
                .param("ids", List.of(UUID.fromString(leaf), UUID.fromString(mid), UUID.fromString(root))).query(Integer.class).single()).isEqualTo(3);

        // Out of order: the parent is not the next file, and nothing is created.
        var conflict = convertNext("dana.designer", PURCHASING, project, root, mappings(rootKey, "call1", midKey));
        assertThat(conflict.status()).as(conflict.toString()).isEqualTo(409); assertThat(conflict.code()).isEqualTo("WORKFLOW_VERSION_CONFLICT");
        assertThat(count("SELECT count(*) FROM workflow WHERE workspace_id=:p1 AND key=:p2", PURCHASING, rootKey)).isZero();

        // Child first. Repeating the very same request cannot convert a second file or create a second draft.
        var first = convertNext("dana.designer", PURCHASING, project, leaf, mappings(leafKey));
        assertThat(first.status()).as(first.toString()).isEqualTo(201);
        UUID leafWorkflow = UUID.fromString(first.body().path("converted").path("workflow").path("id").asString());
        assertThat(first.body().path("project").path("status").asString()).isEqualTo("converting");
        var files = first.body().path("project").path("files");
        assertThat(files.get(2).path("status").asString()).isEqualTo("converted"); assertThat(files.get(2).path("convertedWorkflowId").asString()).isEqualTo(leafWorkflow.toString());
        assertThat(files.get(1).path("status").asString()).isEqualTo("analyzed");
        var replay = convertNext("dana.designer", PURCHASING, project, leaf, mappings(leafKey));
        assertThat(replay.status()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM workflow WHERE workspace_id=:p1 AND key=:p2", PURCHASING, leafKey)).isEqualTo(1);
        assertThat(auditCount("migration.converted", leaf)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata->>'workflowKey' FROM audit_event WHERE action='migration.converted' AND resource_id=:id").param("id", leaf).query(String.class).single()).isEqualTo(leafKey);
        assertThat(jdbc.sql("SELECT metadata->>'sourceSha256' FROM audit_event WHERE action='migration.uploaded' AND resource_id=:id").param("id", leaf).query(String.class).single())
                .isEqualTo(jdbc.sql("SELECT source_sha256 FROM migration_job WHERE id=:id").param("id", UUID.fromString(leaf)).query(String.class).single());
        // The same conversion through the single-file endpoint stays idempotent; different mappings are rejected.
        var again = api("dana.designer").post(mappings(leafKey), "/workspaces/{ws}/migrations/{id}/convert", PURCHASING, leaf);
        assertThat(again.status()).isEqualTo(201); assertThat(again.body().path("workflow").path("id").asString()).isEqualTo(leafWorkflow.toString());
        assertThat(api("dana.designer").post(mappings(leafKey).put("name", "Other"), "/workspaces/{ws}/migrations/{id}/convert", PURCHASING, leaf).status()).isEqualTo(409);

        // The parent cannot be mapped to a child that is not published yet; the failed attempt leaves the project unchanged.
        var unpublished = convertNext("dana.designer", PURCHASING, project, mid, mappings(midKey, "call1", leafKey));
        assertThat(unpublished.status()).as(unpublished.toString()).isEqualTo(422);
        assertThat(project(PURCHASING, project.path("id").asString()).path("files").get(1).path("status").asString()).isEqualTo("analyzed");
        publish(leafWorkflow);
        var second = convertNext("dana.designer", PURCHASING, project, mid, mappings(midKey, "call1", leafKey));
        assertThat(second.status()).as(second.toString()).isEqualTo(201);
        publish(UUID.fromString(second.body().path("converted").path("workflow").path("id").asString()));
        var third = convertNext("dana.designer", PURCHASING, project, root, mappings(rootKey, "call1", midKey));
        assertThat(third.status()).as(third.toString()).isEqualTo(201);
        assertThat(third.body().path("project").path("status").asString()).isEqualTo("converted");
        for (var converted : third.body().path("project").path("files")) assertThat(converted.path("status").asString()).isEqualTo("converted");
        assertThat(project(PURCHASING, project.path("id").asString()).path("status").asString()).isEqualTo("converted");
        // Every file is converted: nothing is left to convert next.
        assertThat(convertNext("dana.designer", PURCHASING, project, root, mappings(rootKey, "call1", midKey)).status()).isEqualTo(409);
        assertThat(auditCount("migration.converted", root)).isEqualTo(1);
    }
    @Test void concurrentConvertNextCreatesExactlyOneDraft() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8), key = "prj-race-" + suffix;
        var project = uploaded("dana.designer", PURCHASING, List.of(file("a.bpmn", bpmn("race-a-" + suffix)), file("b.bpmn", bpmn("race-b-" + suffix))));
        String first = project.path("conversionOrder").get(0).asString();
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> convertNext("dana.designer", PURCHASING, project, first, mappings(key)).status()));
            List<Integer> statuses = new ArrayList<>(); for (var result : results) statuses.add(result.get(60, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally { pool.shutdownNow(); }
        assertThat(count("SELECT count(*) FROM workflow WHERE workspace_id=:p1 AND key=:p2", PURCHASING, key)).isEqualTo(1);
    }
    @Test void missingAmbiguousCyclicDynamicAndUnsupportedDependenciesBlockTheProjectBeforeAnyConversion() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        var missing = uploaded("dana.designer", PURCHASING, List.of(file("parent.bpmn", bpmn("parent-" + s, "absent-" + s))));
        assertThat(codes(missing)).containsExactly("PROJECT_MISSING_DEPENDENCY"); assertThat(missing.path("issues").get(0).path("fileName").asString()).isEqualTo("parent.bpmn");
        assertThat(missing.path("status").asString()).isEqualTo("blocked"); assertThat(missing.path("conversionOrder").isEmpty()).isTrue();
        // Same process ID in two files: a call to it would be ambiguous.
        var ambiguous = uploaded("dana.designer", PURCHASING, List.of(file("one.bpmn", bpmn("twin-" + s)), file("two.bpmn", bpmn("twin-" + s)), file("caller.bpmn", bpmn("caller-" + s, "twin-" + s))));
        assertThat(codes(ambiguous)).containsExactly("PROJECT_DUPLICATE_PROCESS"); assertThat(ambiguous.path("conversionOrder").isEmpty()).isTrue();
        var cycle = uploaded("dana.designer", PURCHASING, List.of(file("a.bpmn", bpmn("cycle-a-" + s, "cycle-b-" + s)), file("b.bpmn", bpmn("cycle-b-" + s, "cycle-a-" + s))));
        assertThat(codes(cycle)).contains("PROJECT_DEPENDENCY_CYCLE"); assertThat(cycle.path("conversionOrder").isEmpty()).isTrue();
        var selfCall = uploaded("dana.designer", PURCHASING, List.of(file("self.bpmn", bpmn("self-" + s, "self-" + s))));
        assertThat(codes(selfCall)).contains("PROJECT_DEPENDENCY_CYCLE");
        var dynamic = uploaded("dana.designer", PURCHASING, List.of(file("dynamic.bpmn", bpmn("dynamic-" + s, "${child}"))));
        assertThat(codes(dynamic)).contains("PROJECT_DYNAMIC_CHILD_REFERENCE");
        var unsupported = uploaded("dana.designer", PURCHASING, List.of(file("ok.bpmn", bpmn("ok-" + s)), Map.of("fileName", "rules.dmn", "content", "<definitions/>", "sourceFormat", "dmn")));
        assertThat(codes(unsupported)).containsExactly("PROJECT_FORMAT_NOT_SUPPORTED");
        assertThat(unsupported.path("files").get(1).path("migrationId").isNull()).isTrue(); assertThat(unsupported.path("files").get(1).path("status").asString()).isEqualTo("blocked");
        for (var blocked : List.of(missing, ambiguous, cycle, selfCall, dynamic, unsupported)) {
            assertThat(blocked.path("status").asString()).isEqualTo("blocked");
            var attempt = convertNext("dana.designer", PURCHASING, blocked, UUID.randomUUID().toString(), mappings("prj-blocked-" + s));
            assertThat(attempt.status()).as(attempt.toString()).isEqualTo(422); assertThat(attempt.code()).isEqualTo("MIGRATION_BLOCKED");
            assertThat(attempt.body().path("projectIssues").isEmpty()).isFalse();
        }
        assertThat(count("SELECT count(*) FROM workflow WHERE workspace_id=:p1 AND key=:p2", PURCHASING, "prj-blocked-" + s)).isZero();
        // A blocked project is still reported consistently when read later.
        assertThat(project(PURCHASING, missing.path("id").asString()).path("status").asString()).isEqualTo("blocked");
    }
    @Test void invalidUploadsAreRejectedAtomically() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        int jobs = count("SELECT count(*) FROM migration_job"), projects = count("SELECT count(*) FROM migration_project");
        List<Map<String, String>> eleven = new ArrayList<>(); for (int i = 0; i < 11; i++) eleven.add(file("f" + i + ".bpmn", bpmn("p" + i + "-" + s)));
        assertThat(upload("dana.designer", PURCHASING, "Too many", eleven).status()).isEqualTo(400);
        assertThat(upload("dana.designer", PURCHASING, "No files", List.of()).status()).isEqualTo(400);
        assertThat(upload("dana.designer", PURCHASING, " ", List.of(file("a.bpmn", bpmn("a-" + s)))).status()).isEqualTo(400);
        assertThat(upload("dana.designer", PURCHASING, "Duplicate names", List.of(file("a.bpmn", bpmn("a-" + s)), file("a.bpmn", bpmn("b-" + s)))).status()).isEqualTo(422);
        // One bad file rejects the project and rolls back the jobs already stored for the good one.
        assertThat(upload("dana.designer", PURCHASING, "XXE", List.of(file("good.bpmn", bpmn("good-" + s)),
                file("bad.bpmn", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>"))).status()).isEqualTo(422);
        assertThat(upload("dana.designer", PURCHASING, "Not XML", List.of(file("good.bpmn", bpmn("good-" + s)), file("bad.bpmn", "not xml"))).status()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM migration_job")).isEqualTo(jobs); assertThat(count("SELECT count(*) FROM migration_project")).isEqualTo(projects);
        assertThat(api("dana.designer").post(Map.of("name", "Typo", "files", List.of(file("a.bpmn", bpmn("a-" + s))), "extra", true), PROJECTS, PURCHASING).status()).isEqualTo(400);
        assertThat(api("dana.designer").post(Map.of("name", "Bad convert"), PROJECTS + "/{id}/convert-next", PURCHASING, UUID.randomUUID()).status()).isEqualTo(400);
    }
    @Test void projectsRespectPermissionsAndWorkspaceIsolation() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        var files = List.of(file("only.bpmn", bpmn("only-" + s)));
        assertThat(upload("vic.viewer", PURCHASING, "Viewer", files).status()).isEqualTo(403);
        assertThat(upload("rhea.requester", PURCHASING, "Requester", files).status()).isEqualTo(403);
        assertThat(upload("paul.publisher", PURCHASING, "Publisher", files).status()).isEqualTo(403);
        assertThat(upload("otto.outsider", PURCHASING, "Outsider", files).status()).isEqualTo(404);
        var project = uploaded("dana.designer", PURCHASING, files); String id = project.path("id").asString(), job = project.path("conversionOrder").get(0).asString();
        assertThat(api("vic.viewer").get(PROJECTS + "/{id}", PURCHASING, id).status()).isEqualTo(200);
        assertThat(api("paul.publisher").get(PROJECTS + "/{id}", PURCHASING, id).status()).isEqualTo(200);
        assertThat(api("otto.outsider").get(PROJECTS + "/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(api("fred.facilities").get(PROJECTS + "/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(convertNext("vic.viewer", PURCHASING, project, job, mappings("prj-viewer-" + s)).status()).isEqualTo(403);
        assertThat(convertNext("rhea.requester", PURCHASING, project, job, mappings("prj-requester-" + s)).status()).isEqualTo(403);
        assertThat(convertNext("otto.outsider", PURCHASING, project, job, mappings("prj-outsider-" + s)).status()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM workflow WHERE workspace_id=:p1 AND key LIKE :p2", PURCHASING, "prj-%-" + s)).isZero();
        // dana designs in the sandbox too, but a project belongs to one workspace only, in both directions.
        assertThat(api("dana.designer").get(PROJECTS + "/{id}", SANDBOX, id).status()).isEqualTo(404);
        assertThat(convertNext("dana.designer", SANDBOX, project, job, mappings("prj-sandbox-" + s)).status()).isEqualTo(404);
        var sandbox = uploaded("dana.designer", SANDBOX, files);
        assertThat(api("dana.designer").get(PROJECTS + "/{id}", PURCHASING, sandbox.path("id").asString()).status()).isEqualTo(404);
        assertThat(api("dana.designer").get(PROJECTS + "/{id}", PURCHASING, UUID.randomUUID()).status()).isEqualTo(404);
        // The project's own source jobs are not readable through the other workspace either.
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}", PURCHASING, sandbox.path("conversionOrder").get(0).asString()).status()).isEqualTo(404);
    }
}
