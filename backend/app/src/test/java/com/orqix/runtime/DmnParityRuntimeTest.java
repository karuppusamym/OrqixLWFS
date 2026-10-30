package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** DMN parity on the real engine (docs/build/S121): in lists, between ranges, PRIORITY and COLLECT with exact sums. */
class DmnParityRuntimeTest extends RuntimeTestBase {

    private JsonNode decisionOf(UUID workflow, String costCenter, String amount) throws Exception {
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow,
                "input", Map.of("title", "Equipment", "purpose", "Replacement", "costCenter", costCenter,
                        "amount", new BigDecimal(amount))), UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID run = UUID.fromString(started.body().path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> !events(run).isEmpty());
        return events(run).getFirst();
    }

    private java.util.List<JsonNode> events(UUID run) {
        return jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :id AND type = 'DECISION_EVALUATED' ORDER BY seq")
                .param("id", run).query(String.class).list().stream().map(json::parse).toList();
    }

    private ObjectNode row(ArrayNode rows, String when) {
        ObjectNode row = rows.addObject();
        row.set("when", json.parse(when));
        return row;
    }

    @Test
    void priorityWithListsAndRangesPicksTheHighestRankedRowInTheEngine() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, d -> {
            ObjectNode table = (ObjectNode) d.path("decisions").get(0);
            ArrayNode rows = table.putArray("rows");
            row(rows, "{\"fieldRef\":\"/input/amount\",\"operator\":\"between\",\"value\":{\"from\":1000,\"to\":5000,\"includeTo\":false}}")
                    .putObject("then").put("route", "standard");
            row(rows, "{\"all\":[{\"fieldRef\":\"/input/costCenter\",\"operator\":\"in\",\"value\":[\"IT\",\"HR\"]},"
                    + "{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":2000}]}").putObject("then").put("route", "financeReview");
            table.put("hitPolicy", "PRIORITY");
            table.putObject("priority").put("output", "route").putArray("order").add("financeReview").add("standard");
        });

        // 3000 from IT matches both rows; finance ranks higher although it is the second row.
        JsonNode both = decisionOf(workflow, "IT", "3000");
        assertThat(both.path("matched").asString()).isEqualTo("r2");
        assertThat(both.path("outputs").path("route").asString()).isEqualTo("financeReview");
        assertThat(both.path("evidence").path("hitPolicy").asString()).isEqualTo("PRIORITY");
        assertThat(both.path("evidence").path("rows").get(0).path("matched").asBoolean()).isTrue();
        assertThat(both.path("evidence").path("rows").get(0).path("selected").asBoolean()).isFalse();

        assertThat(decisionOf(workflow, "OPS", "3000").path("matched").asString()).isEqualTo("r1");
        // 5000 is outside [1000..5000) and OPS is not in the list.
        assertThat(decisionOf(workflow, "OPS", "5000").path("matched").asString()).isEqualTo("default");
    }

    @Test
    void collectSumsEveryMatchingRowExactlyAndAppliesTheDefaultWhenNoneMatches() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, d -> {
            ObjectNode table = (ObjectNode) d.path("decisions").get(0);
            ArrayNode rows = table.putArray("rows");
            row(rows, "{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":5000}").putObject("then")
                    .put("fee", new BigDecimal("0.1"));
            row(rows, "{\"fieldRef\":\"/input/costCenter\",\"operator\":\"in\",\"value\":[\"IT\",\"HR\"]}").putObject("then")
                    .put("fee", new BigDecimal("0.2"));
            row(rows, "{\"any\":[{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":100000},"
                    + "{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"HR\"}]}").putObject("then").put("fee", 5);
            table.put("hitPolicy", "COLLECT").put("aggregation", "SUM");
            table.putObject("default").put("fee", 0);
            ((ObjectNode) d.path("transitions").get(4)).set("condition", json
                    .parse("{\"fieldRef\":\"/steps/amountPolicy/fee\",\"operator\":\"gte\",\"value\":1}"));
        });

        // 0.1 + 0.2 is 0.30000000000000004 in the engine's doubles; the step stores the exact 0.3.
        JsonNode two = decisionOf(workflow, "IT", "6000");
        assertThat(two.path("matched").asString()).isEqualTo("r1,r2");
        assertThat(two.path("outputs").path("fee").decimalValue()).isEqualByComparingTo("0.3");
        assertThat(two.path("outputs").path("fee").toString()).isEqualTo("0.3");
        assertThat(two.path("evidence").path("aggregation").asString()).isEqualTo("SUM");
        assertThat(two.path("evidence").path("rows").get(0).path("selected").asBoolean()).isTrue();
        assertThat(two.path("evidence").path("rows").get(1).path("selected").asBoolean()).isTrue();

        // An OR row counts once even when both alternatives hold.
        JsonNode all = decisionOf(workflow, "HR", "200000");
        assertThat(all.path("matched").asString()).isEqualTo("r1,r2,r3");
        assertThat(all.path("outputs").path("fee").decimalValue()).isEqualByComparingTo("5.3");

        JsonNode none = decisionOf(workflow, "OPS", "10");
        assertThat(none.path("matched").asString()).isEqualTo("default");
        assertThat(none.path("outputs").path("fee").decimalValue()).isEqualByComparingTo("0");
        assertThat(none.path("evidence").path("defaultSelected").asBoolean()).isTrue();
    }
}
