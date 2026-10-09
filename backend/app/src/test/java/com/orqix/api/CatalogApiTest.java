package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** B03.4 / B03.5 / B03.6: templates, drafts with ETag (T-API-02), validation, publish + replay (T-API-03), export. */
class CatalogApiTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    String newKey() {
        return "wf-" + UUID.randomUUID().toString().substring(0, 8);
    }

    UUID createFromTemplate(String key) throws Exception {
        Response created = api("dana.designer").post(Map.of("key", key, "name", "Purchase " + key,
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(created.header("ETag")).isEqualTo("\"r1\"");
        return UUID.fromString(created.body().path("id").asString());
    }

    @Test
    void templatesForDesignersOnly() throws Exception {
        Response templates = api("dana.designer").get("/workspaces/{ws}/templates", PURCHASING);
        assertThat(templates.status()).isEqualTo(200);
        assertThat(templates.body().path("items").get(0).path("id").asString()).isEqualTo("purchase-approval");
        assertThat(templates.body().path("items").get(0).path("requiredGroups").toString())
                .contains("managers", "finance-approvers");
        assertThat(api("rhea.requester").get("/workspaces/{ws}/templates", PURCHASING).status()).isEqualTo(403);
        assertThat(api("fred.facilities").get("/workspaces/{ws}/templates", PURCHASING).status()).isEqualTo(404);
    }

    @Test
    void draftIfMatchRace_T_API_02() throws Exception {
        String key = newKey();
        UUID id = createFromTemplate(key);
        Response draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        JsonNode definition = draft.body().path("definition");
        Map<String, Object> body = Map.of("definition", definition);
        Response first = api("dana.designer").put(body, "\"r1\"", "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(first.status()).as(first.toString()).isEqualTo(200);
        assertThat(first.header("ETag")).isEqualTo("\"r2\"");
        Response second = api("dana.designer").put(body, "\"r1\"", "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(second.status()).isEqualTo(412);
        assertThat(second.code()).isEqualTo("STALE_REVISION");
        assertThat(second.body().path("currentRevision").asInt()).isEqualTo(2);
        Response missing = api("dana.designer").put(body, null, "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(missing.status()).isEqualTo(428);
        assertThat(missing.code()).isEqualTo("PRECONDITION_REQUIRED");
    }

    @Test
    void incompleteDraftSavesAndValidationReportsIssues() throws Exception {
        String key = newKey();
        UUID id = createFromTemplate(key);
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id)
                .body().path("definition");
        // Approval without a group: structurally incomplete, still saved (BD-19).
        ((ObjectNode) definition.path("nodes").get(1).path("config")).remove("candidateGroupRef");
        Response saved = api("dana.designer").put(Map.of("definition", definition), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(saved.status()).isEqualTo(200);
        Response report = api("dana.designer").post(Map.of("revision", 2), "/workspaces/{ws}/workflows/{id}/validate",
                PURCHASING, id);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.body().path("publishable").asBoolean()).isFalse();
        assertThat(report.body().path("issues").findValues("code").toString()).contains("WF_SCHEMA_INVALID");

        Response unknownVersion = api("dana.designer").put(Map.of("definition", Map.of("schemaVersion", "9.9")), "\"r2\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(unknownVersion.status()).isEqualTo(400);
    }

    @Test
    void publishValidateReplayAndViewerDenied_T_API_03() throws Exception {
        String key = newKey();
        UUID id = createFromTemplate(key);
        Response report = api("dana.designer").post(Map.of("revision", 1), "/workspaces/{ws}/workflows/{id}/validate",
                PURCHASING, id);
        assertThat(report.body().path("publishable").asBoolean()).as(report.toString()).isTrue();

        String idem = UUID.randomUUID().toString();
        Map<String, Object> request = Map.of("draftRevision", 1, "acknowledgeWarnings", true);
        int deploymentsBefore = deployments();
        Response denied = api("vic.viewer").post(request, idem, "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, id);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(auditDenied("publishDraft")).isPositive();

        Response published = api("paul.publisher").post(request, idem, "/workspaces/{ws}/workflows/{id}/publish",
                PURCHASING, id);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        assertThat(published.body().path("releaseNumber").asInt()).isEqualTo(1);
        assertThat(published.body().path("isActive").asBoolean()).isTrue();
        assertThat(published.body().path("manifest").path("groups").toString()).contains("managers");
        assertThat(deployments()).isEqualTo(deploymentsBefore + 1);

        Response replay = api("paul.publisher").post(request, idem, "/workspaces/{ws}/workflows/{id}/publish",
                PURCHASING, id);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("id").asString()).isEqualTo(published.body().path("id").asString());
        assertThat(deployments()).isEqualTo(deploymentsBefore + 1);

        Response reused = api("paul.publisher").post(Map.of("draftRevision", 1), idem,
                "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, id);
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        Response missingKey = api("paul.publisher").post(request, null, "/workspaces/{ws}/workflows/{id}/publish",
                PURCHASING, id);
        assertThat(missingKey.status()).isEqualTo(400);
        assertThat(missingKey.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        Response form = api("rhea.requester").get("/workspaces/{ws}/workflows/{id}/start-form", PURCHASING, id);
        assertThat(form.status()).isEqualTo(200);
        assertThat(form.body().path("inputSchema").path("properties").has("amount")).isTrue();

        Response releases = api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/releases", PURCHASING, id);
        assertThat(releases.status()).isEqualTo(200);
        assertThat(releases.body().path("items").size()).isEqualTo(1);
    }

    @Test
    void blockerDraftReturns422AndCreatesNoDeployment() throws Exception {
        String key = newKey();
        UUID id = createFromTemplate(key);
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id)
                .body().path("definition");
        ((ObjectNode) definition.path("nodes").get(1).path("config")).put("candidateGroupRef", "no-such-group");
        api("dana.designer").put(Map.of("definition", definition), "\"r1\"", "/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, id);
        int before = deployments();
        Response response = api("paul.publisher").post(Map.of("draftRevision", 2), UUID.randomUUID().toString(),
                "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, id);
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("WORKFLOW_INVALID");
        assertThat(response.body().path("issues").findValues("code").toString()).contains("WF_GROUP_UNBOUND");
        assertThat(deployments()).isEqualTo(before);
    }

    @Test
    void evaluateDecisionUsesCanonicalEvaluator() throws Exception {
        UUID id = createFromTemplate(newKey());
        Response result = api("dana.designer").post(Map.of("revision", 1, "values", Map.of("/input/amount", 5000)),
                "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, id, "amount-policy");
        assertThat(result.status()).as(result.toString()).isEqualTo(200);
        assertThat(result.body().path("output").path("route").asString()).isEqualTo("financeReview");
        assertThat(result.body().path("matchedRow").asInt()).isZero();
        Response standard = api("dana.designer").post(Map.of("revision", 1, "values", Map.of("/input/amount", 4999.99)),
                "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, id, "amount-policy");
        assertThat(standard.body().path("usedDefault").asBoolean()).isTrue();
    }

    @Test
    void exportIsDeterministicAndSecretFree() throws Exception {
        String key = newKey();
        UUID id = createFromTemplate(key);
        Response published = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, id);
        String releaseId = published.body().path("id").asString();
        Response first = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/releases/{r}/export", PURCHASING, id,
                releaseId);
        Response second = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/releases/{r}/export", PURCHASING, id,
                releaseId);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.header("Content-Disposition")).contains(key + "-r1.zip");
        assertThat(first.bytes()).isEqualTo(second.bytes());
        List<String> names = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first.bytes()))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                names.add(entry.getName());
                all.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(names).containsExactly("BINDINGS.md", "canonical.json", "connections/procurement-v1.json",
                "decisions/amount-policy.dmn", "manifest.json", "process.bpmn20.xml", "source-map.json");
        assertThat(all.toString()).doesNotContain("localhost:8095").doesNotContain("secretRef");
        assertThat(api("rhea.requester").get("/workspaces/{ws}/workflows/{id}/releases/{r}/export", PURCHASING, id,
                releaseId).status()).isEqualTo(403);
    }

    private int deployments() {
        return jdbc.sql("SELECT count(*) FROM act_re_deployment WHERE tenant_id_ = :t")
                .param("t", PURCHASING.toString()).query(Integer.class).single();
    }

    private int auditDenied(String operationId) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'auth.denied' AND resource_id = :op")
                .param("op", operationId).query(Integer.class).single();
    }
}
