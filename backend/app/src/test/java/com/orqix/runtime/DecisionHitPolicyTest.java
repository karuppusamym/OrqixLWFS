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

/** RL04 on the real engine: FIRST with overlapping rows and an OR row, evaluated by the pinned DMN (docs/build/S40). */
class DecisionHitPolicyTest extends RuntimeTestBase {

    private UUID publishFirstWithOrRow() throws Exception {
        return publishPurchase(PURCHASING, d -> {
            ObjectNode table = (ObjectNode) d.path("decisions").get(0);
            ObjectNode template = (ObjectNode) table.path("rows").get(0).deepCopy();
            ArrayNode rows = table.putArray("rows");
            ObjectNode orRow = template.deepCopy();
            ObjectNode when = orRow.putObject("when");
            ArrayNode any = when.putArray("any");
            any.addObject().put("fieldRef", "/input/amount").put("operator", "gte").put("value", 10000);
            any.addObject().put("fieldRef", "/input/costCenter").put("operator", "eq").put("value", "HR");
            rows.add(orRow);
            ObjectNode overlapping = template.deepCopy(); // amount >= 5000, which overlaps the first row
            rows.add(overlapping);
            table.put("hitPolicy", "FIRST");
        });
    }

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

    /** UNIQUE table whose three rows share one condition and differ only in their effective window. */
    private UUID publishDated() throws Exception {
        return publishPurchase(PURCHASING, d -> {
            ObjectNode table = (ObjectNode) d.path("decisions").get(0);
            ObjectNode current = (ObjectNode) table.path("rows").get(0).deepCopy();
            ObjectNode expired = current.deepCopy();
            ObjectNode future = current.deepCopy();
            expired.putObject("effective").put("to", "2000-01-01");
            expired.putObject("then").put("route", "standard");
            current.putObject("effective").put("from", "2000-01-02").put("to", "2999-12-31");
            future.putObject("effective").put("from", "3000-01-01");
            future.putObject("then").put("route", "standard");
            table.putArray("rows").add(expired).add(current).add(future);
        });
    }

    @Test
    void datedRowsAreJudgedBySubmissionDateInThePinnedDmnAndNamedInTheEvidence() throws Exception {
        UUID workflow = publishDated();
        JsonNode decision = decisionOf(workflow, "IT", "6000");
        assertThat(decision.path("matched").asString()).isEqualTo("r2");
        assertThat(decision.path("outputs").path("route").asString()).isEqualTo("financeReview");
        JsonNode evidence = decision.path("evidence");
        assertThat(java.time.LocalDate.parse(evidence.path("asOf").asString())).isNotNull();
        assertThat(evidence.path("rows").get(0).path("inWindow").asBoolean()).isFalse();
        assertThat(evidence.path("rows").get(1).path("inWindow").asBoolean()).isTrue();
        assertThat(evidence.path("rows").get(2).path("inWindow").asBoolean()).isFalse();
        // Below the amount threshold no row matches, so the default applies even though one row is in force.
        assertThat(decisionOf(workflow, "IT", "10").path("matched").asString()).isEqualTo("default");
    }

    @Test
    void pinnedDmnPicksTheFirstMatchingRowAndNamesTheCanonicalRowForOrAlternatives() throws Exception {
        UUID workflow = publishFirstWithOrRow();

        JsonNode big = decisionOf(workflow, "IT", "20000");
        assertThat(big.path("matched").asString()).isEqualTo("r1");

        // The second alternative of the OR row is DMN rule 2, but the evidence names canonical row 1.
        JsonNode hr = decisionOf(workflow, "HR", "100");
        assertThat(hr.path("matched").asString()).isEqualTo("r1");
        assertThat(hr.path("evidence").path("rows").get(0).path("selected").asBoolean()).isTrue();

        // Rows 1 and 3 of the DMN both match 20000 and 6000 respectively; the overlapping row only applies when row 1 does not.
        JsonNode medium = decisionOf(workflow, "IT", "6000");
        assertThat(medium.path("matched").asString()).isEqualTo("r2");
        assertThat(medium.path("evidence").path("rows").get(0).path("matched").asBoolean()).isFalse();
        assertThat(medium.path("evidence").path("rows").get(1).path("matched").asBoolean()).isTrue();

        // Even with two matching rows, the first one wins (20000 also satisfies row 2).
        assertThat(big.path("evidence").path("rows").get(1).path("matched").asBoolean()).isTrue();
        assertThat(big.path("evidence").path("rows").get(1).path("selected").asBoolean()).isFalse();

        JsonNode small = decisionOf(workflow, "IT", "10");
        assertThat(small.path("matched").asString()).isEqualTo("default");
        assertThat(small.path("evidence").path("defaultSelected").asBoolean()).isTrue();
    }
}
