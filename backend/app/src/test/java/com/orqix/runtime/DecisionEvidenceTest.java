package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.JsonNode;

class DecisionEvidenceTest extends RuntimeTestBase {
    @Test void pinnedDecisionEvidenceIsVisibleToRequesterParticipantAndDataReaderButNotMetadataViewer() throws Exception {
        UUID workflow=publishPurchase(PURCHASING, definition -> {
            var table=definition.withArray("decisions").get(0);
            var rejected=(tools.jackson.databind.node.ObjectNode)table.path("rows").get(0).deepCopy();
            ((tools.jackson.databind.node.ObjectNode)rejected.path("when")).put("operator","lt").put("value",1);
            ((tools.jackson.databind.node.ObjectNode)table).withArray("rows").add(rejected);
        });
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,new BigDecimal("5000")).path("id").asString());
        approve("mani.manager",PURCHASING,run,"manager");
        for (String user : new String[]{"rhea.requester","mani.manager","fin.finance","olga.operator"}) {
            JsonNode event=decisionEvent(user,run);
            assertThat(event.path("matched").asString()).isEqualTo("r1");
            assertThat(event.path("outputs").path("route").asString()).isEqualTo("financeReview");
            JsonNode evidence=event.path("evidence");
            assertThat(evidence.path("inputs").get(0).path("fieldRef").asString()).isEqualTo("/input/amount");
            assertThat(evidence.path("inputs").get(0).path("value").decimalValue()).isEqualByComparingTo("5000");
            assertThat(evidence.path("rows").size()).isEqualTo(2);
            assertThat(evidence.path("rows").get(0).path("matched").asBoolean()).isTrue();
            assertThat(evidence.path("rows").get(0).path("selected").asBoolean()).isTrue();
            assertThat(evidence.path("rows").get(1).path("matched").asBoolean()).isFalse();
            assertThat(evidence.path("rows").get(1).path("selected").asBoolean()).isFalse();
            assertThat(evidence.path("defaultSelected").asBoolean()).isFalse();
        }
        JsonNode metadata=decisionEvent("vic.viewer",run);
        assertThat(metadata.has("outputs")).isFalse();
        assertThat(metadata.has("matched")).isFalse();
        assertThat(metadata.has("evidence")).isFalse();
        assertThat(metadata.path("evidenceRedacted").asBoolean()).isTrue();
        assertThat(metadata.path("decisionKey").asString()).isEqualTo("amount-policy");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id=:run AND (jsonb_exists(metadata,'evidence') OR jsonb_exists(metadata,'inputs') OR jsonb_exists(metadata,'outputs'))").param("run",run.toString()).query(Long.class).single()).isZero();
    }

    @Test void defaultEvidenceRetainsRejectedCandidatesAndLegacyNullDataIsSafelyRedacted() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,null);
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,new BigDecimal("12.50")).path("id").asString());
        approve("mani.manager",PURCHASING,run,"manager");
        JsonNode event=decisionEvent("rhea.requester",run);
        assertThat(event.path("matched").asString()).isEqualTo("default");
        assertThat(event.path("evidence").path("rows").get(0).path("matched").asBoolean()).isFalse();
        assertThat(event.path("evidence").path("rows").get(0).path("selected").asBoolean()).isFalse();
        assertThat(event.path("evidence").path("defaultSelected").asBoolean()).isTrue();
        com.orqix.support.TestDatabase.legacyFixtureJdbc().sql("UPDATE orqix.run_event SET data='null'::jsonb WHERE run_id=:run AND type='DECISION_EVALUATED'").param("run",run).update();
        JsonNode metadata=decisionEvent("vic.viewer",run);
        assertThat(metadata.isObject()).isTrue();
        assertThat(metadata.path("evidenceRedacted").asBoolean()).isTrue();
        assertThat(metadata.has("outputs")).isFalse();
    }

    private JsonNode decisionEvent(String user,UUID run) throws Exception {
        var response=api(user).get("/workspaces/{ws}/runs/{run}/events?limit=100",PURCHASING,run);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        for(JsonNode event:response.body().path("items")) if("DECISION_EVALUATED".equals(event.path("type").asString())) return event.path("data");
        throw new AssertionError("Decision event missing");
    }
}
