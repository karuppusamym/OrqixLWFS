package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

/** Standard DMN download and upload of decision tables (docs/build/S121) on PostgreSQL with the real compiler. */
class DecisionDmnApiTest extends com.orqix.support.RuntimeTestBase {

    private static final String DMN = "/workspaces/{ws}/workflows/{id}/decisions/{key}/dmn";
    private static final String DRAFT = "/workspaces/{ws}/workflows/{id}/draft";
    private static final String NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";

    /** A Camunda-style file: PRIORITY on route, a list, a range, two inputs, default output entries. */
    static final String ROUTING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="%s" id="defs" name="Routing" namespace="http://example.com/dmn">
              <decision id="routing" name="Purchase routing">
                <decisionTable id="t" hitPolicy="PRIORITY">
                  <input id="i1" label="Amount"><inputExpression id="e1" typeRef="number"><text>amount</text></inputExpression></input>
                  <input id="i2" label="Cost center"><inputExpression id="e2" typeRef="string"><text>costCenter</text></inputExpression></input>
                  <output id="o1" name="route" typeRef="string">
                    <outputValues><text>"financeReview","standard"</text></outputValues>
                    <defaultOutputEntry><text>"standard"</text></defaultOutputEntry>
                  </output>
                  <rule id="r1"><inputEntry><text>[1000..5000)</text></inputEntry><inputEntry><text>-</text></inputEntry><outputEntry><text>"standard"</text></outputEntry></rule>
                  <rule id="r2"><inputEntry><text>&gt;= 2000</text></inputEntry><inputEntry><text>"IT","HR"</text></inputEntry><outputEntry><text>"financeReview"</text></outputEntry></rule>
                  <rule id="r3"><inputEntry><text>-</text></inputEntry><inputEntry><text>not("OPS")</text></inputEntry><outputEntry><text>"standard"</text></outputEntry><annotationEntry><text>note</text></annotationEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """.formatted(NS);

    private UUID workflow() throws Exception {
        var r = api("dana.designer").post(Map.of("key", "dmn-" + UUID.randomUUID().toString().substring(0, 8),
                "name", "DMN " + UUID.randomUUID().toString().substring(0, 6), "templateId", "purchase-approval"),
                "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return UUID.fromString(r.body().path("id").asString());
    }

    private Response importDmn(String user, UUID wf, String ifMatch, String query, String type, String xml) throws Exception {
        var b = MockMvcRequestBuilders.put("/api/v1" + DMN + query, PURCHASING, wf, "amount-policy")
                .with(IntegrationTestBase::xsrf).content(xml.getBytes(StandardCharsets.UTF_8));
        if (type != null) b.contentType(type);
        if (ifMatch != null) b.header("If-Match", ifMatch);
        return api(user).send(b);
    }

    private Response importDmn(UUID wf, String ifMatch, String xml) throws Exception {
        return importDmn("dana.designer", wf, ifMatch, "", "application/xml", xml);
    }

    private JsonNode table(UUID wf) throws Exception {
        return api("dana.designer").get(DRAFT, PURCHASING, wf).body().path("definition").path("decisions").get(0);
    }

    @Test
    void importReadsStandardDmnAndTheExportReadsBackToTheSameTable() throws Exception {
        UUID wf = workflow();
        var ok = importDmn(wf, "\"r1\"", ROUTING);
        assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
        assertThat(ok.header("ETag")).isEqualTo("\"r2\"");
        JsonNode table = table(wf);
        assertThat(table.path("key").asString()).isEqualTo("amount-policy");
        assertThat(table.path("hitPolicy").asString()).isEqualTo("PRIORITY");
        assertThat(table.path("priority").path("output").asString()).isEqualTo("route");
        assertThat(table.path("rows")).hasSize(3);
        assertThat(table.path("rows").get(0).path("when").path("operator").asString()).isEqualTo("between");
        assertThat(table.path("rows").get(0).path("when").path("value").path("includeTo").asBoolean(true)).isFalse();
        assertThat(table.path("rows").get(1).path("when").path("all").get(1).path("operator").asString()).isEqualTo("in");
        assertThat(table.path("rows").get(2).path("when").path("operator").asString()).isEqualTo("ne");
        assertThat(table.path("default").path("route").asString()).isEqualTo("standard");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND resource_id = :wf "
                + "AND action = 'workflow.draft.saved' AND metadata @> '{\"source\": \"decisionDmn\"}'::jsonb")
                .param("ws", PURCHASING).param("wf", wf.toString()).query(Integer.class).single()).isEqualTo(1);

        // The evaluator uses the imported table: 3000 from IT matches rows 1 and 2, and finance ranks first.
        var eval = api("dana.designer").post(Map.of("revision", 2, "values", Map.of("/input/amount", 3000, "/input/costCenter", "IT")),
                "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, wf, "amount-policy");
        assertThat(eval.body().path("matchedRow").asInt()).isEqualTo(1);
        assertThat(eval.body().path("matchedRows").toString()).isEqualTo("[1]");

        var exported = api("vic.viewer").get(DMN, PURCHASING, wf, "amount-policy");
        assertThat(exported.status()).as(exported.toString()).isEqualTo(200);
        assertThat(exported.header("Content-Type")).startsWith("application/xml");
        assertThat(exported.header("Content-Disposition")).contains("-amount-policy-draft-2.dmn");
        String xml = new String(exported.bytes(), StandardCharsets.UTF_8);
        assertThat(xml).contains("hitPolicy=\"PRIORITY\"", "<defaultOutputEntry>", "[1000..5000)");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND resource_id = :wf "
                + "AND action = 'workflow.exported' AND metadata @> '{\"format\": \"dmn\", \"revision\": 2}'::jsonb")
                .param("ws", PURCHASING).param("wf", wf.toString()).query(Integer.class).single()).isEqualTo(1);

        assertThat(importDmn(wf, "\"r2\"", xml).status()).isEqualTo(200);
        assertThat(table(wf)).isEqualTo(table);
    }

    @Test
    void severalDecisionsNeedAChoiceAndUnsupportedEntriesAreNamed() throws Exception {
        UUID wf = workflow();
        String two = ROUTING.replace("</definitions>", """
                  <decision id="other" name="Other"><literalExpression><text>1</text></literalExpression></decision>
                </definitions>""");
        var choose = importDmn(wf, "\"r1\"", two);
        assertThat(choose.status()).as(choose.toString()).isEqualTo(422);
        assertThat(choose.body().path("decisions").toString()).contains("\"routing\"", "\"other\"");
        assertThat(importDmn("dana.designer", wf, "\"r1\"", "?decisionId=routing", "application/xml", two).status()).isEqualTo(200);

        var expression = importDmn(wf, "\"r2\"", ROUTING.replace("&gt;= 2000", "amount * 2 &gt; 4000"));
        assertThat(expression.status()).isEqualTo(422);
        assertThat(expression.body().toString()).contains("rule 2, input 1");

        // Compiler validation of the table in the workflow: an output the branch cannot use is still checked.
        var unknownField = importDmn(wf, "\"r2\"", ROUTING.replace("<text>costCenter</text>", "<text>department</text>"));
        assertThat(unknownField.status()).isEqualTo(422);
        assertThat(unknownField.body().toString()).contains("WF_FIELDREF_UNKNOWN");
        assertThat(api("dana.designer").get(DRAFT, PURCHASING, wf).body().path("revision").asInt()).isEqualTo(2);
    }

    @Test
    void permissionsPreconditionsAndContentType() throws Exception {
        UUID wf = workflow();
        assertThat(importDmn("vic.viewer", wf, "\"r1\"", "", "application/xml", ROUTING).status()).isEqualTo(403);
        assertThat(importDmn("dana.designer", wf, null, "", "application/xml", ROUTING).status()).isEqualTo(428);
        assertThat(importDmn("dana.designer", wf, "\"r9\"", "", "application/xml", ROUTING).status()).isEqualTo(412);
        assertThat(importDmn("dana.designer", wf, "\"r1\"", "", "text/csv", ROUTING).status()).isEqualTo(400);
        assertThat(importDmn("dana.designer", wf, "\"r1\"", "", "application/xml",
                "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><definitions/>").status())
                .isEqualTo(422);
        assertThat(api("otto.outsider").get(DMN, PURCHASING, wf, "amount-policy").status()).isEqualTo(404);
        assertThat(api("vic.viewer").get(DMN, PURCHASING, wf, "no-such-table").status()).isEqualTo(404);
    }

    @Test
    void releaseAndSharedDecisionsDownloadAsDmnAndSharedTablesConvertFromDmn() throws Exception {
        UUID wf = workflow();
        var published = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, wf);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        String releaseId = published.body().path("id").asString();
        String release = "/workspaces/{ws}/workflows/{id}/releases/{r}/decisions/{key}/dmn";
        assertThat(api("vic.viewer").get(release, PURCHASING, wf, releaseId, "amount-policy").status()).isEqualTo(403);
        var file = api("dana.designer").get(release, PURCHASING, wf, releaseId, "amount-policy");
        assertThat(file.status()).as(file.toString()).isEqualTo(200);
        assertThat(file.header("Content-Disposition")).contains("-r1.dmn");
        assertThat(new String(file.bytes(), StandardCharsets.UTF_8)).contains("hitPolicy=\"UNIQUE\"", "<text>amount</text>");

        var converted = api("dana.designer").send(MockMvcRequestBuilders
                .post("/api/v1/workspaces/{ws}/shared-assets/decision/dmn", PURCHASING).with(IntegrationTestBase::xsrf)
                .contentType("application/xml").content(ROUTING.getBytes(StandardCharsets.UTF_8)));
        assertThat(converted.status()).as(converted.toString()).isEqualTo(200);
        assertThat(converted.body().path("decisionId").asString()).isEqualTo("routing");
        assertThat(converted.body().path("name").asString()).isEqualTo("Purchase routing");
        assertThat(converted.body().path("notes").toString()).contains("annotations");
        JsonNode content = converted.body().path("content");
        assertThat(content.path("hitPolicy").asString()).isEqualTo("PRIORITY");
        assertThat(api("vic.viewer").send(MockMvcRequestBuilders
                .post("/api/v1/workspaces/{ws}/shared-assets/decision/dmn", PURCHASING).with(IntegrationTestBase::xsrf)
                .contentType("application/xml").content(ROUTING.getBytes(StandardCharsets.UTF_8))).status()).isEqualTo(403);

        String key = "routing-" + UUID.randomUUID().toString().substring(0, 6);
        var created = api("dana.designer").post(Map.of("key", key, "name", "Routing " + key, "content", content),
                "/workspaces/{ws}/shared-assets/decision", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        var shared = api("vic.viewer").get("/workspaces/{ws}/shared-assets/decision/{key}/versions/{v}/dmn", PURCHASING, key, 1);
        assertThat(shared.status()).as(shared.toString()).isEqualTo(200);
        assertThat(shared.header("Content-Disposition")).contains(key + "-v1.dmn");
        assertThat(new String(shared.bytes(), StandardCharsets.UTF_8)).contains("name=\"Routing " + key + "\"",
                "hitPolicy=\"PRIORITY\"");
        assertThat(api("vic.viewer").get("/workspaces/{ws}/shared-assets/decision/{key}/versions/{v}/dmn", PURCHASING, key, 2)
                .status()).isEqualTo(404);
    }
}
