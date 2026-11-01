package com.orqix.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Converted but changed — verify" items (docs/17 "Severity model") and the SI migration ledger export, through the real
 * API and PostgreSQL: a rewritten FEEL condition converts, blocks publishing until a reviewer other than the mapper
 * acknowledges it (audited), and the ledger lists every finding with its disposition, checksum, mapping and verification.
 */
class MigrationVerifyLedgerApiTest extends RuntimeTestBase {
    private static final String VERIFY = "/workspaces/{ws}/migrations/{id}/verifications";
    private static final String LEDGER = "/workspaces/{ws}/migrations/{id}/ledger";

    private JsonNode upload(UUID ws) throws Exception {
        var response = api("dana.designer").post(Map.of("fileName", "feel-gateways.bpmn",
                "content", Files.readString(Path.of("../../examples/migration/camunda8-feel-gateways.bpmn")), "sourceFormat", "bpmn"),
                "/workspaces/{ws}/migrations", ws);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return response.body();
    }

    private JsonNode convert(UUID ws, JsonNode job) throws Exception {
        ObjectNode request = json.object();
        request.put("workflowKey", "verify-" + UUID.randomUUID().toString().substring(0, 8));
        request.put("name", "Verify ledger");
        request.put("acknowledgeInfo", true);
        request.put("acknowledgeReview", true);
        var converted = api("dana.designer").post(request, "/workspaces/{ws}/migrations/{id}/convert", ws, job.path("id").asString());
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        return converted.body();
    }

    private Response acknowledge(String user, UUID ws, String migration, String element, String code, String reason) throws Exception {
        return api(user).post(Map.of("items", List.of(Map.of("sourceElementId", element, "reasonCode", code)), "reason", reason), VERIFY, ws, migration);
    }

    private Response publish(String user, UUID ws, UUID workflow) throws Exception {
        return api(user).post(Map.of("draftRevision", 1, "acknowledgeWarnings", true), UUID.randomUUID().toString(),
                "/workspaces/{ws}/workflows/{id}/publish", ws, workflow);
    }

    private static JsonNode issue(JsonNode report, String element) {
        for (JsonNode i : report.path("issues")) if (element.equals(i.path("sourceElementId").asString())) return i;
        throw new AssertionError("No issue for " + element);
    }

    @Test
    void aRewrittenConditionConvertsButBlocksPublishingUntilAReviewerVerifiesIt() throws Exception {
        var job = upload(PURCHASING);
        String migration = job.path("id").asString();
        var report = job.path("report");
        assertThat(issue(report, "toLow").path("severity").asString()).isEqualTo("verify");
        assertThat(issue(report, "toLow").path("reasonCode").asString()).isEqualTo("CONDITION_REWRITTEN");
        assertThat(issue(report, "toNo").path("severity").asString()).as("not(<yes/no variable>) is transcribed, not rewritten").isEqualTo("review");
        assertThat(report.path("elementCounts").path("verify").asInt()).isEqualTo(1);
        assertThat(report.path("publishable").asBoolean()).as("verify never blocks conversion").isTrue();

        // Before conversion there is no draft to verify against.
        var early = acknowledge("paul.publisher", PURCHASING, migration, "toLow", "CONDITION_REWRITTEN", "Checked early");
        assertThat(early.status()).as(early.toString()).isEqualTo(422);

        var converted = convert(PURCHASING, job);
        UUID workflow = UUID.fromString(converted.path("workflow").path("id").asString());
        assertThat(issue(converted.path("report"), "toLow").path("resolutionStatus").asString()).as("acknowledgeReview does not close verify").isEqualTo("open");

        var blocked = publish("paul.publisher", PURCHASING, workflow);
        assertThat(blocked.status()).as(blocked.toString()).isEqualTo(422);
        assertThat(blocked.code()).isEqualTo("MIGRATION_BLOCKED");
        assertThat(blocked.body().path("detail").asString()).contains("verify");

        // Only someone who may publish, and not the mapper; only open verify items; a reason is required.
        assertThat(acknowledge("dana.designer", PURCHASING, migration, "toLow", "CONDITION_REWRITTEN", "Mine").status()).isEqualTo(403);
        assertThat(acknowledge("olga.operator", PURCHASING, migration, "toLow", "CONDITION_REWRITTEN", "Operator").status()).isEqualTo(403);
        assertThat(acknowledge("otto.outsider", PURCHASING, migration, "toLow", "CONDITION_REWRITTEN", "Outsider").status()).isEqualTo(404);
        assertThat(acknowledge("paul.publisher", PURCHASING, migration, "toNo", "CONDITION_CONVERTED", "Not a verify item").status()).isEqualTo(422);
        assertThat(acknowledge("paul.publisher", PURCHASING, migration, "toLow", "CONDITION_REWRITTEN", " ").status()).isEqualTo(400);

        var ok = acknowledge("paul.publisher", PURCHASING, migration, "toLow", "CONDITION_REWRITTEN", "Checked score 100 and 101 against the source");
        assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
        assertThat(issue(ok.body().path("report"), "toLow").path("resolutionStatus").asString()).isEqualTo("acknowledged");
        var verification = ok.body().path("report").path("verifications").get(0);
        assertThat(verification.path("reason").asString()).isEqualTo("Checked score 100 and 101 against the source");
        assertThat(verification.path("acknowledgedByName").asString()).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id=:ws AND action='migration.verification.acknowledged' AND resource_id=:id")
                .param("ws", PURCHASING).param("id", migration).query(Integer.class).single()).isEqualTo(1);
        assertThat(acknowledge("paul.publisher", PURCHASING, migration, "toLow", "CONDITION_REWRITTEN", "Again").status()).as("already acknowledged").isEqualTo(422);

        var published = publish("paul.publisher", PURCHASING, workflow);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);

        // The ledger: every finding, with disposition, checksum, adapter, the approved mapping and the verification.
        var ledger = api("paul.publisher").get(LEDGER + "?format=json", PURCHASING, migration);
        assertThat(ledger.status()).as(ledger.toString()).isEqualTo(200);
        assertThat(ledger.header("Content-Disposition")).contains("attachment").contains(".json");
        var body = ledger.body();
        assertThat(body.path("format").asString()).isEqualTo("orqix-migration-ledger-0.1");
        assertThat(body.path("elements").size()).isEqualTo(ok.body().path("report").path("issues").size());
        String hash = job.path("report").path("sourceHash").asString();
        assertThat(body.path("migration").path("sourceSha256").asString()).isEqualTo(hash);
        assertThat(body.path("migration").path("awaitingVerification").asBoolean()).isFalse();
        JsonNode low = null;
        for (JsonNode e : body.path("elements")) {
            assertThat(e.path("sourceSha256").asString()).isEqualTo(hash);
            assertThat(e.path("adapterVersion").asString()).isNotBlank();
            assertThat(e.path("disposition").asString()).isNotBlank();
            if ("toLow".equals(e.path("sourceElementId").asString())) low = e;
        }
        assertThat(low).isNotNull();
        assertThat(low.path("disposition").asString()).isEqualTo("convertedChangedVerified");
        assertThat(low.path("verification").path("reason").asString()).startsWith("Checked score");
        assertThat(body.path("approvedMappings").path("acknowledgeReview").asBoolean()).isTrue();
        assertThat(body.path("equivalence").path("goldenScenarios").path("status").asString()).isEqualTo("notRecorded");

        var csv = api("paul.publisher").get(LEDGER + "?format=csv", PURCHASING, migration);
        assertThat(csv.status()).isEqualTo(200);
        String text = new String(csv.bytes(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("\"sourceElementId\",\"sourceName\"").contains("\"CONDITION_REWRITTEN\"").contains("convertedChangedVerified");

        var zip = api("paul.publisher").get(LEDGER, PURCHASING, migration);
        assertThat(zip.status()).as(zip.toString()).isEqualTo(200);
        assertThat(zip.header("Content-Type")).startsWith("application/zip");
        Map<String, byte[]> entries = unzip(zip.bytes());
        assertThat(entries).containsKeys("ledger.json", "ledger.csv", "ledger.xlsx", "README.txt");
        Map<String, byte[]> workbook = unzip(entries.get("ledger.xlsx"));
        assertThat(workbook).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet5.xml");
        assertThat(new String(workbook.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8)).contains("CONDITION_REWRITTEN").contains(hash).doesNotContain("<f>");
        assertThat(new String(workbook.get("xl/workbook.xml"), StandardCharsets.UTF_8)).contains("name=\"Elements\"").contains("name=\"Verifications\"");
        // Same stored job, same bytes.
        assertThat(api("paul.publisher").get(LEDGER + "?format=xlsx", PURCHASING, migration).bytes()).isEqualTo(entries.get("ledger.xlsx"));

        assertThat(api("olga.operator").get(LEDGER, PURCHASING, migration).status()).as("needs workflow.export").isEqualTo(403);
        assertThat(api("otto.outsider").get(LEDGER, PURCHASING, migration).status()).isEqualTo(404);
        assertThat(api("paul.publisher").get(LEDGER + "?format=pdf", PURCHASING, migration).status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id=:ws AND action='migration.ledger.exported' AND resource_id=:id")
                .param("ws", PURCHASING).param("id", migration).query(Integer.class).single()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void theMapperCannotVerifyTheirOwnConversionEvenWithPublishRights() throws Exception {
        // In the sandbox dana.designer may publish; she still cannot verify what she converted.
        var job = upload(SANDBOX);
        var converted = convert(SANDBOX, job);
        var self = acknowledge("dana.designer", SANDBOX, job.path("id").asString(), "toLow", "CONDITION_REWRITTEN", "Looks fine to me");
        assertThat(self.status()).as(self.toString()).isEqualTo(403);
        assertThat(self.body().path("detail").asString()).contains("different reviewer");
        UUID workflow = UUID.fromString(converted.path("workflow").path("id").asString());
        assertThat(publish("dana.designer", SANDBOX, workflow).status()).isEqualTo(422);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) out.put(e.getName(), zip.readAllBytes());
        }
        return out;
    }
}
