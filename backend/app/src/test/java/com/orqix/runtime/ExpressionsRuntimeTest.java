package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-X01 and CG-F04 (docs/build/S137) on real PostgreSQL and Flowable: a Calculate step evaluated by the engine with
 * the pinned types, a path chosen by an expression, the same answer as the dry run, computed form fields recomputed by
 * the server, a cross-field rule refusing a request, an output without a value stopping the request, and the
 * expression preview endpoint's permission boundary.
 */
class ExpressionsRuntimeTest extends RuntimeTestBase {

    private UUID workflow(String taxExpression) throws Exception {
        return publishPurchase(PURCHASING, d -> {
            ObjectNode props = (ObjectNode) d.path("inputSchema").path("properties");
            props.set("startDate", json.parse("{\"type\":\"string\",\"format\":\"date\",\"title\":\"Start\"}"));
            props.set("endDate", json.parse("{\"type\":\"string\",\"format\":\"date\",\"title\":\"End\"}"));
            props.set("nights", json.parse("{\"type\":\"integer\",\"title\":\"Nights\",\"minimum\":0,\"maximum\":365}"));
            ObjectNode ui = (ObjectNode) d.path("inputUi");
            ui.set("computed", json.parse("{\"nights\":\"daysBetween(input.startDate, input.endDate)\"}"));
            ui.set("rules", json.parse("[{\"id\":\"endAfterStart\",\"check\":\"input.endDate > input.startDate\","
                    + "\"message\":\"The end date must be after the start date.\",\"fields\":[\"endDate\"]}]"));
            d.set("nodes", json.parse("""
                [{"id":"start","type":"start","label":"Submitted","config":{}},
                 {"id":"totals","type":"calculate","label":"Work out the totals","config":{"outputs":[
                   {"key":"tax","expression":"%s","type":"number","scale":2},
                   {"key":"total","expression":"input.amount + tax","type":"number","scale":2},
                   {"key":"perNight","expression":"if coalesce(input.nights, 0) > 0 then total / input.nights else total","type":"number","scale":2,"rounding":"halfEven"},
                   {"key":"payBy","expression":"addWorkingDays(input.startDate, 2)","type":"date"},
                   {"key":"band","expression":"if total >= 1000 then \\"large\\" else \\"small\\"","type":"string"}]}},
                 {"id":"route","type":"branch","label":"Large?","config":{}},
                 {"id":"large","type":"end","label":"Large","config":{"businessOutcome":"Large","outcomeKind":"success"}},
                 {"id":"small","type":"end","label":"Small","config":{"businessOutcome":"Small","outcomeKind":"success"}}]
                """.formatted(taxExpression)));
            d.set("transitions", json.parse("""
                [{"id":"begin","from":"start","to":"totals","on":"next"},
                 {"id":"calc","from":"totals","to":"route","on":"next"},
                 {"id":"big","from":"route","to":"large","on":"condition","condition":{"expression":"steps.totals.total >= 1000 and isWorkingDay(steps.totals.payBy)"}},
                 {"id":"other","from":"route","to":"small","on":"default"}]
                """));
            d.set("outputSchema", json.parse("""
                {"type":"object","additionalProperties":false,"required":["total","perNight","payBy","band"],"properties":{
                  "total":{"type":"number","title":"Total"},"perNight":{"type":"number","title":"Per night"},
                  "payBy":{"type":"string","format":"date","title":"Pay by"},"band":{"type":"string","title":"Band"}}}
                """));
            d.set("outputMapping", json.parse("""
                {"total":{"fieldRef":"/steps/totals/total","required":true},"perNight":{"fieldRef":"/steps/totals/perNight","required":true},
                 "payBy":{"fieldRef":"/steps/totals/payBy","required":true},"band":{"fieldRef":"/steps/totals/band","required":true}}
                """));
            d.set("dependencies", json.parse("{\"connections\":[]}"));
            d.remove("uiLayout");
        });
    }

    private Map<String, Object> input(String amount, String start, String end) {
        Map<String, Object> input = input(new BigDecimal(amount));
        input.put("startDate", start);
        input.put("endDate", end);
        input.put("nights", 99);
        return input;
    }

    private UUID start(UUID workflow, Map<String, Object> input) throws Exception {
        var started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    @Test
    void theEngineCalculatesLikeTheDryRunAndChoosesThePathByExpression() throws Exception {
        UUID workflow = workflow("round(input.amount * 0.2, 2)");
        // Friday 27 March 2026 to Monday 30 March: 3 nights; two working days after the Friday is Tuesday 31 March.
        Map<String, Object> small = input("100.05", "2026-03-27", "2026-03-30");
        var simulated = api("dana.designer").post(Map.of("revision", 2, "input", small), "/workspaces/{ws}/workflows/{id}/simulate",
                PURCHASING, workflow);
        assertThat(simulated.status()).as(simulated.toString()).isEqualTo(200);

        UUID run = start(workflow, small);
        awaitLifecycle(PURCHASING, run, "completed");
        JsonNode detail = api("rhea.requester").get("/workspaces/{ws}/runs/{run}", PURCHASING, run).body();
        JsonNode output = detail.path("output");
        assertThat(output.path("total").decimalValue()).isEqualByComparingTo("120.06");
        assertThat(output.path("perNight").decimalValue()).isEqualByComparingTo("40.02");
        assertThat(output.path("payBy").asString()).isEqualTo("2026-03-31");
        assertThat(output.path("band").asString()).isEqualTo("small");
        assertThat(detail.toString()).contains("Small");
        for (String key : new String[] {"total", "perNight", "payBy", "band"}) {
            assertThat(simulated.body().path("outputs").path(key).asString()).as(key).isEqualTo(output.path(key).asString());
        }
        // The server recomputed the computed field: 99 nights were sent, 3 are stored.
        assertThat(detail.path("input").path("nights").asInt()).isEqualTo(3);
        JsonNode events = api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body();
        assertThat(events.toString()).contains("STEP_CALCULATED");

        UUID large = start(workflow, input("900", "2026-03-27", "2026-03-30"));
        awaitLifecycle(PURCHASING, large, "completed");
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{run}", PURCHASING, large).body().toString())
                .contains("Large");
    }

    @Test
    void aCrossFieldRuleRefusesTheRequestWithTheMessage() throws Exception {
        UUID workflow = workflow("round(input.amount * 0.2, 2)");
        var refused = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input("100", "2026-03-27", "2026-03-20")),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(refused.body().toString()).contains("/input/endDate", "\"rule\"", "The end date must be after the start date.");
    }

    @Test
    void anOutputWithoutAValueStopsTheRequestForAttention() throws Exception {
        UUID run = start(workflow("input.amount / (input.amount - input.amount)"), input("100", "2026-03-27", "2026-03-30"));
        awaitLifecycle(PURCHASING, run, "needsAttention");
    }

    @Test
    void thePreviewEvaluatesWithTheServerEngineForDesignersOnly() throws Exception {
        Map<String, Object> body = Map.of("expression", "addWorkingDays(input.start, 1)",
                "types", Map.of("input", Map.of("start", "date")),
                "values", Map.of("input", Map.of("start", "2026-04-02")),
                "calendar", Map.of("zone", "Europe/London", "weekdays", java.util.List.of(1, 2, 3, 4, 5),
                        "holidays", java.util.List.of("2026-04-03", "2026-04-06")));
        var designer = api("dana.designer").post(body, "/workspaces/{ws}/expressions/evaluate", PURCHASING);
        assertThat(designer.status()).as(designer.toString()).isEqualTo(200);
        assertThat(designer.body().path("valid").asBoolean()).isTrue();
        assertThat(designer.body().path("type").asString()).isEqualTo("date");
        assertThat(designer.body().path("value").asString()).isEqualTo("2026-04-07");
        assertThat(designer.body().path("fieldRefs").toString()).isEqualTo("[\"/input/start\"]");

        var invalid = api("dana.designer").post(Map.of("expression", "input.start + 1", "types", Map.of("input", Map.of("start", "date"))),
                "/workspaces/{ws}/expressions/evaluate", PURCHASING);
        assertThat(invalid.body().path("valid").asBoolean()).isFalse();
        assertThat(invalid.body().path("problem").path("code").asString()).isEqualTo("EXPR_TYPE");
        assertThat(invalid.body().path("problem").path("position").asInt()).isEqualTo(12);

        assertThat(api("rhea.requester").post(body, "/workspaces/{ws}/expressions/evaluate", PURCHASING).status()).isEqualTo(403);
    }
}
