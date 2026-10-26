package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

/** G3 (docs/build/S89): decision-table CSV export/import on PostgreSQL with the real compiler validation. */
class DecisionCsvApiTest extends com.orqix.support.RuntimeTestBase {

    private static final String CSV = "/workspaces/{ws}/workflows/{id}/decisions/{key}/csv";
    private static final String DRAFT = "/workspaces/{ws}/workflows/{id}/draft";
    private static final String HEAD = "hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/amount,in:text:/input/costCenter,out:text:route\n";

    private UUID workflow() throws Exception {
        var r = api("dana.designer").post(Map.of("key", "csv-" + UUID.randomUUID().toString().substring(0, 8),
                "name", "CSV", "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return UUID.fromString(r.body().path("id").asString());
    }

    private Response importCsv(String user, UUID wf, String key, String ifMatch, String type, byte[] body) throws Exception {
        var b = MockMvcRequestBuilders.put("/api/v1" + CSV, PURCHASING, wf, key).with(IntegrationTestBase::xsrf)
                .content(body);
        if (type != null) {
            b.contentType(type);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return api(user).send(b);
    }

    private Response importCsv(String user, UUID wf, String ifMatch, String text) throws Exception {
        return importCsv(user, wf, "amount-policy", ifMatch, "text/csv; charset=UTF-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private int revision(UUID wf) throws Exception {
        return api("dana.designer").get(DRAFT, PURCHASING, wf).body().path("revision").asInt();
    }

    @Test
    void importReplacesTheTableAsADraftChangeOnlyAndExportRoundTrips() throws Exception {
        UUID wf = workflow();
        String example = Files.readString(Path.of("..", "..", "examples", "decision-csv", "purchase-routing.csv"))
                .replace("\r\n", "\n");
        var ok = importCsv("dana.designer", wf, "\"r1\"", example);
        assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
        assertThat(ok.header("ETag")).isEqualTo("\"r2\"");
        JsonNode table = api("dana.designer").get(DRAFT, PURCHASING, wf).body().path("definition").path("decisions").get(0);
        assertThat(table.path("key").asString()).isEqualTo("amount-policy");
        assertThat(table.path("version").asInt()).isEqualTo(1);
        assertThat(table.path("hitPolicy").asString()).isEqualTo("FIRST");
        assertThat(table.path("rows")).hasSize(3);
        assertThat(table.path("default").path("route").asString()).isEqualTo("standard");
        // A draft change only: no release exists, one audit event with the source.
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, wf).body()
                .path("activeRelease").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND resource_id = :wf "
                + "AND action = 'workflow.draft.saved' AND metadata @> '{\"source\": \"decisionCsv\", \"revision\": 2}'::jsonb")
                .param("ws", PURCHASING).param("wf", wf.toString()).query(Integer.class).single()).isEqualTo(1);

        // Export, import again: identical decision (and the file is byte-stable).
        var exported = api("vic.viewer").get(CSV, PURCHASING, wf, "amount-policy");
        assertThat(exported.status()).isEqualTo(200);
        assertThat(exported.header("Content-Type")).startsWith("text/csv");
        assertThat(exported.header("Content-Disposition")).contains(".csv");
        String text = new String(exported.bytes(), StandardCharsets.UTF_8);
        assertThat(text).isEqualTo(example);
        assertThat(importCsv("dana.designer", wf, "\"r2\"", text).status()).isEqualTo(200);
        JsonNode again = api("dana.designer").get(DRAFT, PURCHASING, wf).body().path("definition").path("decisions").get(0);
        assertThat(again).isEqualTo(table);
        // The evaluator uses the imported table: row order is priority.
        var eval = api("dana.designer").post(Map.of("revision", 3, "values", Map.of("/input/amount", 6000, "/input/costCenter", "IT")),
                "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, wf, "amount-policy");
        assertThat(eval.body().path("output").path("route").asString()).isEqualTo("itFinanceReview");

        var template = api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/decisions/{key}/csv-template", PURCHASING, wf, "amount-policy");
        assertThat(template.status()).isEqualTo(200);
        assertThat(new String(template.bytes(), StandardCharsets.UTF_8)).startsWith("hitPolicy:FIRST,");
    }

    @Test
    void permissionsPreconditionsAndLimits() throws Exception {
        UUID wf = workflow();
        String ok = HEAD + "rule,,,gte 10,,a\ndefault,,,,,b\n";
        assertThat(importCsv("vic.viewer", wf, "\"r1\"", ok).status()).isEqualTo(403);
        assertThat(importCsv("dana.designer", wf, null, ok).status()).isEqualTo(428);
        assertThat(importCsv("dana.designer", wf, "\"r9\"", ok).status()).isEqualTo(412);
        assertThat(importCsv("dana.designer", wf, "amount-policy", "\"r1\"", "application/json", ok.getBytes(StandardCharsets.UTF_8)).status())
                .isEqualTo(400);
        assertThat(importCsv("dana.designer", wf, "no-such", "\"r1\"", "text/csv", ok.getBytes(StandardCharsets.UTF_8)).status())
                .isEqualTo(404);
        assertThat(importCsv("otto.outsider", wf, "\"r1\"", ok).status()).isEqualTo(404);
        assertThat(api("otto.outsider").get(CSV, PURCHASING, wf, "amount-policy").status()).isEqualTo(404);
        var big = importCsv("dana.designer", wf, "amount-policy", "\"r1\"", "text/csv", new byte[200 * 1024]);
        assertThat(big.status()).isEqualTo(413);
        assertThat(revision(wf)).isEqualTo(1);
    }

    @Test
    void parseAndCompilerFindingsCarryLineAndColumnAndSaveNothing() throws Exception {
        UUID wf = workflow();
        var bad = importCsv("dana.designer", wf, "\"r1\"", HEAD + "rule,,,gte 1,000,,a\ndefault,,,,,b\n");
        assertThat(bad.status()).as(bad.toString()).isEqualTo(422);
        assertThat(bad.body().path("fieldErrors").get(0).path("pointer").asString()).startsWith("/csv/lines/2/columns/");
        // UNIQUE with overlapping rows: the compiler's own WF_DECISION_OVERLAP, at the second rule's cell.
        var overlap = importCsv("dana.designer", wf, "\"r1\"", HEAD.replace("FIRST", "UNIQUE")
                + "rule,,,gte 100,,a\nrule,,,gte 200,,b\ndefault,,,,,c\n");
        assertThat(overlap.status()).as(overlap.toString()).isEqualTo(422);
        var e = overlap.body().path("fieldErrors").get(0);
        assertThat(e.path("code").asString()).isEqualTo("WF_DECISION_OVERLAP");
        assertThat(e.path("pointer").asString()).isEqualTo("/csv/lines/3/columns/4");
        var unknown = importCsv("dana.designer", wf, "\"r1\"", "hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/nope,out:text:route\n"
                + "rule,,,gte 1,a\ndefault,,,,b\n");
        assertThat(unknown.status()).isEqualTo(422);
        assertThat(unknown.body().path("fieldErrors").get(0).path("code").asString()).isEqualTo("WF_FIELDREF_UNKNOWN");
        var mismatch = importCsv("dana.designer", wf, "\"r1\"", "hitPolicy:FIRST,effectiveFrom,effectiveTo,in:text:/input/amount,out:text:route\n"
                + "rule,,,eq five,a\ndefault,,,,b\n");
        assertThat(mismatch.status()).as(mismatch.toString()).isEqualTo(422);
        var unsafe = importCsv("dana.designer", wf, "\"r1\"", HEAD + "rule,,,gte 1,,=cmd()\ndefault,,,,,b\n");
        assertThat(unsafe.status()).isEqualTo(422);
        assertThat(unsafe.body().path("fieldErrors").get(0).path("code").asString()).isEqualTo("WF_LITERAL_UNSAFE");
        assertThat(unsafe.body().path("fieldErrors").get(0).path("pointer").asString()).isEqualTo("/csv/lines/2/columns/6");
        assertThat(revision(wf)).isEqualTo(1);
        // The same overlapping rows are valid under FIRST.
        assertThat(importCsv("dana.designer", wf, "\"r1\"", HEAD + "rule,,,gte 100,,a\nrule,,,gte 200,,b\ndefault,,,,,c\n").status())
                .isEqualTo(200);
        assertThat(revision(wf)).isEqualTo(2);
    }

    @Test
    void tablesCsvCannotCarryAreRefusedOnExport() throws Exception {
        UUID wf = workflow();
        JsonNode draft = api("dana.designer").get(DRAFT, PURCHASING, wf).body();
        var def = (tools.jackson.databind.node.ObjectNode) draft.path("definition").deepCopy();
        var row = (tools.jackson.databind.node.ObjectNode) def.path("decisions").get(0).path("rows").get(0);
        var any = row.putObject("when").putArray("any");
        any.addObject().put("fieldRef", "/input/amount").put("operator", "lt").put("value", 10);
        any.addObject().put("fieldRef", "/input/amount").put("operator", "gt").put("value", 99);
        assertThat(api("dana.designer").put(Map.of("definition", def), "\"r1\"", DRAFT, PURCHASING, wf).status()).isEqualTo(200);
        var refused = api("dana.designer").get(CSV, PURCHASING, wf, "amount-policy");
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body().path("fieldErrors").get(0).path("message").asString()).contains("OR row");
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}/decisions/{key}/csv-template", PURCHASING, wf,
                "amount-policy").status()).isEqualTo(200);
    }
}
