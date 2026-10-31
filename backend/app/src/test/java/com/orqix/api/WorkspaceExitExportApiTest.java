package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.flowable.bpmn.model.BpmnModel;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Workflow exit export (docs/admin-guide.md "Exit export") on PostgreSQL: the archive's content and manifest, history
 * as CSV and JSON Lines, no credentials, run data only on request, admin-only access with audited denials, both audit
 * records with the archive hash, and the round trip - the exported workflow JSON imported into another workspace
 * through the ordinary create / save draft / publish path compiles and publishes.
 */
class WorkspaceExitExportApiTest extends com.orqix.support.RuntimeTestBase {

    private static final String EXPORT = "/workspaces/{ws}/exit-export";

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String text(Map<String, byte[]> files, String path) {
        assertThat(files).as("archive has " + path).containsKey(path);
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    private String workflowKey(UUID workflowId) {
        return jdbc.sql("SELECT key FROM workflow WHERE id = :id").param("id", workflowId).query(String.class).single();
    }

    private UUID rejectedRun(UUID workflowId) throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, BigDecimal.TEN).path("id").asString());
        JsonNode task = openTask(PURCHASING, runId, "manager");
        assertThat(claim("mani.manager", PURCHASING, task.path("id").asString()).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, task.path("id").asString(), "reject", "Not needed, use spare")
                .status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, runId, "completed");
        return runId;
    }

    @Test
    void exportsSelectedWorkflowsWithHistoryManifestAndNoCredentials() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        UUID other = publishPurchase(PURCHASING, null);
        String key = workflowKey(workflowId);
        UUID rejected = rejectedRun(workflowId);
        UUID open = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, BigDecimal.ONE).path("id").asString());
        UUID otherRun = UUID.fromString(start("rhea.requester", PURCHASING, other, BigDecimal.ONE).path("id").asString());
        // A credential reference on the binding must never reach the archive.
        jdbc.sql("""
                UPDATE connection_binding SET auth_type = 'bearerToken', secret_ref = 'exit-test-credential'
                WHERE workspace_id = :ws AND connection_key = 'procurement'""").param("ws", PURCHASING).update();

        Response response = api("wade.admin").post(Map.of("workflowIds", List.of(workflowId)), EXPORT, PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("application/zip");
        assertThat(response.header("Content-Disposition")).contains("orqix-exit-purchasing-").contains(".zip");
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        byte[] archive = response.bytes();
        Map<String, byte[]> files = unzip(archive);

        String release = "workflows/" + key + "/releases/1/";
        assertThat(files.keySet()).contains("README.md", "workspace.json", "manifest.json", "SHA256SUMS",
                "schemas/workflow-v0.schema.json", "schemas/connection-definition-v0.schema.json",
                "workflows/" + key + "/workflow.json", "workflows/" + key + "/draft.json",
                release + "workflow.json", release + "process.bpmn20.xml", release + "decisions/amount-policy.dmn",
                release + "source-map.json", release + "compile-manifest.json", release + "release.json",
                "connections/procurement/connection.json", "connections/procurement/v1.json",
                "history/runs.csv", "history/runs.jsonl", "history/tasks.csv", "history/tasks.jsonl",
                "history/decisions.csv", "history/decisions.jsonl", "history/run-events.csv",
                "history/run-events.jsonl", "audit/audit-events.csv", "audit/audit-events.jsonl", "people.csv",
                "people.jsonl");
        assertThat(files.keySet()).as("only the selected workflow")
                .noneMatch(p -> p.startsWith("workflows/" + workflowKey(other) + "/"));
        assertThat(files.keySet().iterator().next()).isEqualTo("README.md");

        // Manifest: every other file with its SHA-256 and size, and the same hashes in SHA256SUMS.
        JsonNode manifest = json.parse(text(files, "manifest.json"));
        assertThat(manifest.path("format").asString()).isEqualTo("orqix-exit-archive");
        assertThat(manifest.path("scope").asString()).isEqualTo("selection");
        assertThat(manifest.path("includeRunData").asBoolean()).isFalse();
        assertThat(manifest.path("counts").path("runs").asInt()).isEqualTo(2);
        assertThat(manifest.path("files").size()).isEqualTo(files.size() - 2);
        for (JsonNode file : manifest.path("files")) {
            byte[] content = files.get(file.path("path").asString());
            assertThat(content).as(file.path("path").asString()).isNotNull();
            assertThat(file.path("sha256").asString()).isEqualTo(sha256(content));
            assertThat(file.path("bytes").asLong()).isEqualTo(content.length);
        }
        assertThat(text(files, "SHA256SUMS")).contains(sha256(files.get(release + "process.bpmn20.xml")) + "  "
                + release + "process.bpmn20.xml\n");

        // Open formats: the BPMN is valid BPMN 2.0 for Flowable's own converter, the workflow JSON is the release.
        BpmnModel model = BpmnExportApiTest.flowableModel(text(files, release + "process.bpmn20.xml"));
        assertThat(model.getProcesses()).hasSize(1);
        assertThat(text(files, release + "decisions/amount-policy.dmn")).contains("<definitions");
        JsonNode definition = json.parse(text(files, release + "workflow.json"));
        assertThat(definition.path("workflowKey").asString()).isEqualTo(key);
        JsonNode releaseInfo = json.parse(text(files, release + "release.json"));
        assertThat(releaseInfo.path("isActive").asBoolean()).isTrue();
        assertThat(releaseInfo.path("semanticHash").asString()).hasSize(64);

        // History: runs of the selected workflow only, decisions with their reason, no run data by default.
        String runsJsonl = text(files, "history/runs.jsonl");
        List<JsonNode> runs = runsJsonl.lines().map(json::parse).toList();
        assertThat(runs).extracting(r -> r.path("runId").asString())
                .containsExactlyInAnyOrder(rejected.toString(), open.toString());
        assertThat(runs).allSatisfy(r -> {
            assertThat(r.has("input")).isFalse();
            assertThat(r.path("workflowKey").asString()).isEqualTo(key);
            assertThat(r.path("initiatorName").asString()).isEqualTo("Rhea Requester");
        });
        assertThat(runsJsonl).doesNotContain(otherRun.toString()).doesNotContain("Replacement laptop");
        String runsCsv = text(files, "history/runs.csv");
        assertThat(runsCsv.lines().findFirst().orElseThrow()).startsWith("runId,reference,workflowId,workflowKey");
        assertThat(runsCsv.lines().count()).isEqualTo(3);
        assertThat(text(files, "history/decisions.csv")).contains("reject").contains("Not needed, use spare")
                .contains("Mani Manager");
        assertThat(text(files, "history/tasks.jsonl")).contains("\"nodeId\":\"manager\"");
        assertThat(text(files, "history/run-events.jsonl")).contains(rejected.toString());
        assertThat(text(files, "audit/audit-events.jsonl")).contains("run.started").contains("workflow.published");
        assertThat(text(files, "people.csv")).contains("Rhea Requester").contains("Mani Manager");

        // Credentials never leave: no credential reference, no secret field names in definitions or bindings.
        files.forEach((path, content) -> assertThat(new String(content, StandardCharsets.UTF_8)).as(path)
                .doesNotContain("exit-test-credential").doesNotContain("\"secretRef\""));
        JsonNode connection = json.parse(text(files, "connections/procurement/connection.json"));
        assertThat(connection.path("binding").path("authType").asString()).isEqualTo("bearerToken");
        assertThat(connection.path("binding").has("secretRef")).isFalse();

        // Audit: started before the stream, then the outcome with the archive's own SHA-256.
        JsonNode started = auditMetadata("workspace.exit-export.started");
        assertThat(started.path("scope").asString()).isEqualTo("selection");
        assertThat(started.path("workflowCount").asInt()).isEqualTo(1);
        JsonNode exported = auditMetadata("workspace.exit-exported");
        assertThat(exported.path("sha256").asString()).isEqualTo(sha256(archive));
        assertThat(exported.path("bytes").asLong()).isEqualTo(archive.length);
        assertThat(exported.path("runs").asInt()).isEqualTo(2);
    }

    @Test
    void runDataOnlyWhenRequested() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        rejectedRun(workflowId);
        Response response = api("wade.admin").post(Map.of("workflowIds", List.of(workflowId), "includeRunData", true),
                EXPORT, PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        Map<String, byte[]> files = unzip(response.bytes());
        JsonNode run = json.parse(text(files, "history/runs.jsonl").lines().findFirst().orElseThrow());
        assertThat(run.path("input").path("title").asString()).isEqualTo("Replacement laptop");
        assertThat(text(files, "history/runs.csv").lines().findFirst().orElseThrow()).endsWith(",input,output");
        assertThat(text(files, "history/run-events.csv").lines().findFirst().orElseThrow()).endsWith(",data");
        assertThat(text(files, "README.md")).contains("ARE included");
    }

    @Test
    void wholeWorkspaceWithEmptyBody() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        Response response = api("wade.admin").send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1" + EXPORT, PURCHASING).with(com.orqix.support.IntegrationTestBase::xsrf));
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        Map<String, byte[]> files = unzip(response.bytes());
        assertThat(json.parse(text(files, "manifest.json")).path("scope").asString()).isEqualTo("workspace");
        assertThat(files).containsKey("workflows/" + workflowKey(workflowId) + "/releases/1/process.bpmn20.xml");
        assertThat(files.keySet()).noneMatch(p -> p.contains("..") || p.startsWith("/"));
    }

    @Test
    void roundTripExportedWorkflowJsonImportsAndCompiles() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        String key = workflowKey(workflowId);
        Response response = api("wade.admin").post(Map.of("workflowIds", List.of(workflowId)), EXPORT, PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        Map<String, byte[]> files = unzip(response.bytes());
        JsonNode exported = json.parse(text(files, "workflows/" + key + "/releases/1/workflow.json"));
        JsonNode exportedRelease = json.parse(text(files, "workflows/" + key + "/releases/1/release.json"));

        // Re-import into another workspace through the ordinary path: create, save the file as the draft, publish.
        Response created = api("dana.designer").post(Map.of("key", key, "name", exported.path("name").asString()),
                "/workspaces/{ws}/workflows", SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID imported = UUID.fromString(created.body().path("id").asString());
        Response saved = api("dana.designer").put(Map.of("definition", exported), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", SANDBOX, imported);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        int revision = saved.body().path("revision").asInt();
        Response validated = api("dana.designer").post(Map.of("revision", revision),
                "/workspaces/{ws}/workflows/{id}/validate", SANDBOX, imported);
        assertThat(validated.status()).as(validated.toString()).isEqualTo(200);
        assertThat(validated.body().path("publishable").asBoolean()).as(validated.toString()).isTrue();
        Response published = api("dana.designer").post(Map.of("draftRevision", revision, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", SANDBOX, imported);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        // Same workflow semantics as the exported release.
        assertThat(published.body().path("semanticHash").asString())
                .isEqualTo(exportedRelease.path("semanticHash").asString());
    }

    @Test
    void onlyWorkspaceAdministratorsMayExport() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        int deniedBefore = count("auth.denied", "exportWorkspaceArchive");
        Response designer = api("dana.designer").post(Map.of(), EXPORT, PURCHASING);
        assertThat(designer.status()).isEqualTo(403);
        assertThat(count("auth.denied", "exportWorkspaceArchive")).isEqualTo(deniedBefore + 1);
        assertThat(api("otto.outsider").post(Map.of(), EXPORT, PURCHASING).status()).isEqualTo(404);
        assertThat(api("wade.admin").post(Map.of(), EXPORT, FACILITIES).status()).isEqualTo(404);

        Response unknown = api("wade.admin").post(Map.of("workflowIds", List.of(UUID.randomUUID())), EXPORT, PURCHASING);
        assertThat(unknown.status()).isEqualTo(422);
        UUID sandboxWorkflow = publishPurchase(SANDBOX, null);
        Response foreign = api("wade.admin").post(Map.of("workflowIds", List.of(workflowId, sandboxWorkflow)), EXPORT,
                PURCHASING);
        assertThat(foreign.status()).as("a workflow of another workspace is not exportable here").isEqualTo(422);
    }

    private int count(String action, String resourceId) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = :a AND resource_id = :r")
                .param("a", action).param("r", resourceId).query(Integer.class).single();
    }

    private JsonNode auditMetadata(String action) {
        String metadata = jdbc.sql("""
                SELECT metadata::text FROM audit_event WHERE workspace_id = :ws AND action = :a
                ORDER BY occurred_at DESC, id DESC LIMIT 1""")
                .param("ws", PURCHASING).param("a", action).query(String.class).single();
        return json.parse(metadata);
    }
}
