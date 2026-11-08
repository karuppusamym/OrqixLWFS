package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.support.IntegrationTestBase;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Orqix expressions as decision table entries on the real engine and PostgreSQL (S146 part A, doc 29 CG-D04): the
 * pinned DMN selects with the formula results, the step stores the computed results, the evidence names every formula
 * and its result, UNIQUE stops on two applying rows, and Test this rule, CSV and DMN keep the formulas.
 */
class DecisionExpressionsRuntimeTest extends RuntimeTestBase {

    private static final String DRAFT = "/workspaces/{ws}/workflows/{id}/draft";
    private static final String CSV = "/workspaces/{ws}/workflows/{id}/decisions/{key}/csv";
    private static final String DMN = "/workspaces/{ws}/workflows/{id}/decisions/{key}/dmn";

    private static final String FORMULA_TABLE = """
            {"key":"amount-policy","version":1,"hitPolicy":"FIRST","rows":[
              {"when":{"all":[{"fieldRef":"/input/amount","operator":"gte","value":1000},
                              {"fieldRef":"/input/due","expression":"input.due < date(\\"2026-06-30\\")"}]},
               "then":{"route":"financeReview","note":{"expression":"\\"due \\" + string(input.due)"}}},
              {"when":{"fieldRef":"/input/amount","expression":"input.amount > coalesce(input.limit, 500)"},
               "then":{"route":"financeReview","note":"over limit"}}],
             "default":{"route":"standard","note":"none"}}
            """;

    private Consumer<ObjectNode> withTable(String table) {
        return d -> {
            ObjectNode props = (ObjectNode) d.path("inputSchema").path("properties");
            props.set("limit", json.parse("{\"type\":\"number\",\"title\":\"Limit\",\"minimum\":0,\"maximum\":1000000}"));
            props.set("due", json.parse("{\"type\":\"string\",\"format\":\"date\",\"title\":\"Due\"}"));
            ((ArrayNode) d.path("inputSchema").path("required")).add("due");
            ((ArrayNode) d.path("decisions")).removeAll().add(json.parse(table));
            d.remove("uiLayout");
        };
    }

    private Map<String, Object> request(String amount, String due, String limit) {
        Map<String, Object> input = new LinkedHashMap<>(input(new BigDecimal(amount)));
        input.put("due", due);
        if (limit != null) input.put("limit", new BigDecimal(limit));
        return input;
    }

    private UUID started(UUID workflow, Map<String, Object> input) throws Exception {
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID run = UUID.fromString(started.body().path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        return run;
    }

    private JsonNode decision(UUID run) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> !events(run).isEmpty());
        return events(run).getFirst();
    }

    private java.util.List<JsonNode> events(UUID run) {
        return jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :id AND type = 'DECISION_EVALUATED' ORDER BY seq")
                .param("id", run).query(String.class).list().stream().map(json::parse).toList();
    }

    @Test
    void thePinnedDecisionUsesFormulaTestsAndResults() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, withTable(FORMULA_TABLE));

        JsonNode first = decision(started(workflow, request("2000", "2026-03-01", null)));
        assertThat(first.path("matched").asString()).isEqualTo("r1");
        assertThat(first.path("outputs").path("route").asString()).isEqualTo("financeReview");
        assertThat(first.path("outputs").path("note").asString()).isEqualTo("due 2026-03-01");
        JsonNode evidence = first.path("evidence");
        assertThat(evidence.path("rows").get(0).path("expressions").get(0).path("expression").asString())
                .isEqualTo("input.due < date(\"2026-06-30\")");
        assertThat(evidence.path("rows").get(0).path("expressions").get(0).path("result").asBoolean()).isTrue();
        assertThat(evidence.path("rows").get(0).path("outputExpressions").get(0).path("value").asString())
                .isEqualTo("due 2026-03-01");
        assertThat(evidence.path("rows").get(1).path("expressions").get(0).path("result").asBoolean()).isTrue();
        assertThat(evidence.path("inputs").toString()).contains("/input/due", "/input/amount").doesNotContain("/input/limit");

        // The second row's formula uses the limit when it is given.
        JsonNode second = decision(started(workflow, request("800", "2026-12-01", "700")));
        assertThat(second.path("matched").asString()).isEqualTo("r2");
        assertThat(second.path("outputs").path("note").asString()).isEqualTo("over limit");

        JsonNode none = decision(started(workflow, request("100", "2026-12-01", null)));
        assertThat(none.path("matched").asString()).isEqualTo("default");
        assertThat(none.path("outputs").path("route").asString()).isEqualTo("standard");
        assertThat(none.path("evidence").path("rows").get(1).path("expressions").get(0).path("result").asBoolean()).isFalse();
        assertThat(none.path("evidence").path("defaultSelected").asBoolean()).isTrue();

        // A metadata-only reader sees no formula results.
        UUID run = started(workflow, request("2000", "2026-03-01", null));
        decision(run);
        String events = api("vic.viewer").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body().toString();
        assertThat(events).doesNotContain("due 2026-03-01", "outputExpressions");
    }

    @Test
    void uniqueStopsForAttentionWhenTwoFormulaRowsApply() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, withTable("""
                {"key":"amount-policy","version":1,"hitPolicy":"UNIQUE","rows":[
                  {"when":{"fieldRef":"/input/amount","expression":"input.amount > 100"},"then":{"route":"financeReview"}},
                  {"when":{"fieldRef":"/input/amount","expression":"input.amount > 200"},"then":{"route":"standard"}}],
                 "default":{"route":"standard"}}
                """));
        UUID ok = started(workflow, request("150", "2026-03-01", null));
        assertThat(decision(ok).path("matched").asString()).isEqualTo("r1");
        UUID both = started(workflow, request("300", "2026-03-01", null));
        awaitLifecycle(PURCHASING, both, "needsAttention");
        assertThat(events(both)).isEmpty();
    }

    @Test
    void testThisRuleCsvAndDmnKeepTheFormulas() throws Exception {
        UUID wf = publishPurchase(PURCHASING, withTable(FORMULA_TABLE));
        int revision = api("dana.designer").get(DRAFT, PURCHASING, wf).body().path("revision").asInt();

        // Test this rule: dates as text, the formula results listed.
        var eval = api("dana.designer").post(Map.of("revision", revision, "values",
                Map.of("/input/amount", 2000, "/input/due", "2026-03-01")),
                "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, wf, "amount-policy");
        assertThat(eval.status()).as(eval.toString()).isEqualTo(200);
        assertThat(eval.body().path("matchedRow").asInt()).isZero();
        assertThat(eval.body().path("output").path("note").asString()).isEqualTo("due 2026-03-01");
        assertThat(eval.body().path("expressions").toString()).contains("\"kind\":\"input\"", "\"kind\":\"output\"");
        // Text that is not a date is no value: the formula test gives none and does not match.
        var notADate = api("dana.designer").post(Map.of("revision", revision, "values",
                Map.of("/input/amount", 2000, "/input/due", "soon")),
                "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, wf, "amount-policy");
        assertThat(notADate.status()).as(notADate.toString()).isEqualTo(200);
        assertThat(notADate.body().path("expressions").get(0).path("value").isNull()).isTrue();
        // A compared field still needs its value.
        var bad = api("dana.designer").post(Map.of("revision", revision, "values", Map.of("/input/due", "2026-03-01")),
                "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, wf, "amount-policy");
        assertThat(bad.status()).as(bad.toString()).isEqualTo(422);
        assertThat(bad.body().toString()).contains("No value for /input/amount");

        JsonNode table = api("dana.designer").get(DRAFT, PURCHASING, wf).body().path("definition").path("decisions").get(0);

        // CSV: formula cells are written with = (neutralised with an apostrophe) and read back to the same table.
        var exported = api("vic.viewer").get(CSV, PURCHASING, wf, "amount-policy");
        assertThat(exported.status()).as(exported.toString()).isEqualTo(200);
        String csv = new String(exported.bytes(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/amount,in:expression:/input/due,")
                .contains("'=input.due < date(\"\"2026-06-30\"\")", "'=input.amount > coalesce(input.limit, 500)");
        var imported = api("dana.designer").send(MockMvcRequestBuilders.put("/api/v1" + CSV, PURCHASING, wf, "amount-policy")
                .with(IntegrationTestBase::xsrf).contentType("text/csv; charset=UTF-8").header("If-Match", "\"r" + revision + "\"")
                .content(csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(imported.status()).as(imported.toString()).isEqualTo(200);
        JsonNode afterCsv = api("dana.designer").get(DRAFT, PURCHASING, wf).body().path("definition").path("decisions").get(0);
        assertThat(afterCsv).isEqualTo(table);

        // DMN: the formulas carry the Orqix expression language and read back to the same table.
        var dmn = api("vic.viewer").get(DMN, PURCHASING, wf, "amount-policy");
        assertThat(dmn.status()).as(dmn.toString()).isEqualTo(200);
        String xml = new String(dmn.bytes(), StandardCharsets.UTF_8);
        assertThat(xml).contains("expressionLanguage=\"https://orqix.example/expression\"");
        var back = api("dana.designer").send(MockMvcRequestBuilders.put("/api/v1" + DMN, PURCHASING, wf, "amount-policy")
                .with(IntegrationTestBase::xsrf).contentType("application/xml").header("If-Match", "\"r" + (revision + 1) + "\"")
                .content(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(back.status()).as(back.toString()).isEqualTo(200);
        JsonNode afterDmn = api("dana.designer").get(DRAFT, PURCHASING, wf).body().path("definition").path("decisions").get(0);
        assertThat(afterDmn).isEqualTo(table);

        // A foreign expression language is refused with a reason; nothing changes.
        String foreign = xml.replace("https://orqix.example/expression", "https://example.com/javascript");
        var refused = api("dana.designer").send(MockMvcRequestBuilders.put("/api/v1" + DMN, PURCHASING, wf, "amount-policy")
                .with(IntegrationTestBase::xsrf).contentType("application/xml").header("If-Match", "\"r" + (revision + 2) + "\"")
                .content(foreign.getBytes(StandardCharsets.UTF_8)));
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(refused.body().toString()).contains("expression language");
    }
}
