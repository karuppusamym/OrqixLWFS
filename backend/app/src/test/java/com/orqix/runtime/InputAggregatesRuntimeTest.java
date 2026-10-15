package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real pinned DMN and process output mapping must agree with the shared dry-run aggregate resolver. */
class InputAggregatesRuntimeTest extends RuntimeTestBase {
    private UUID workflow() throws Exception {
        return publishPurchase(PURCHASING,d -> {
            ((ObjectNode)d.path("inputSchema").path("properties")).set("lines",json.parse("""
                {"type":"array","title":"Line items","minItems":1,"maxItems":3,"items":{"type":"object","additionalProperties":false,
                 "required":["amount"],"properties":{"amount":{"type":"number","title":"Line amount","minimum":0,"maximum":100,"multipleOf":0.01}}}}
                """));
            d.set("nodes",json.parse("""
                [{"id":"start","type":"start","label":"Submitted","config":{}},
                 {"id":"policy","type":"decision","label":"Check line totals","config":{"decisionRef":"line-policy"}},
                 {"id":"end","type":"end","label":"Finished","config":{"businessOutcome":"Completed","outcomeKind":"success"}}]
                """));
            d.set("transitions",json.parse("""
                [{"id":"begin","from":"start","to":"policy","on":"next"},{"id":"finish","from":"policy","to":"end","on":"next"}]
                """));
            d.set("decisions",json.parse("""
                [{"key":"line-policy","version":1,"hitPolicy":"UNIQUE","rows":[{"when":{"all":[
                  {"fieldRef":"/input/lines/count","operator":"gte","value":2},
                  {"fieldRef":"/input/lines/sum/amount","operator":"gte","value":0.3}]},"then":{"approved":true}}],"default":{"approved":false}}]
                """));
            d.set("outputSchema",json.parse("""
                {"type":"object","additionalProperties":false,"required":["count","total","approved"],"properties":{
                  "count":{"type":"integer","title":"Line count"},"total":{"type":"number","title":"Line total"},"approved":{"type":"boolean","title":"Approved"}}}
                """));
            d.set("outputMapping",json.parse("""
                {"count":{"fieldRef":"/input/lines/count","required":true},"total":{"fieldRef":"/input/lines/sum/amount","required":true},"approved":{"fieldRef":"/steps/policy/approved","required":true}}
                """));
            d.set("dependencies",json.parse("{\"connections\":[]}"));d.remove("uiLayout");
        });
    }
    private JsonNode execute(UUID workflow,Map<String,Object> input) throws Exception {
        var start=api("rhea.requester").post(Map.of("workflowId",workflow,"input",input),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(start.status()).as(start.toString()).isEqualTo(201);
        UUID run=UUID.fromString(start.body().path("id").asString());awaitLifecycle(PURCHASING,run,"completed");
        return api("rhea.requester").get("/workspaces/{ws}/runs/{run}",PURCHASING,run).body().path("output");
    }
    @Test void decimalRowTotalsAndCountsFeedPinnedDecisionAndMatchSimulation() throws Exception {
        UUID workflow=workflow();var input=input(BigDecimal.TEN);
        input.put("lines",List.of(Map.of("amount",new BigDecimal("0.1")),Map.of("amount",new BigDecimal("0.2"))));
        var simulated=api("dana.designer").post(Map.of("revision",2,"input",input),"/workspaces/{ws}/workflows/{id}/simulate",PURCHASING,workflow);
        assertThat(simulated.status()).as(simulated.toString()).isEqualTo(200);
        JsonNode actual=execute(workflow,input);
        assertThat(actual.path("count").asInt()).isEqualTo(2);
        assertThat(actual.path("total").decimalValue()).isEqualByComparingTo("0.3");
        assertThat(actual.path("approved").asBoolean()).isTrue();
        assertThat(simulated.body().path("outputs").path("total").decimalValue()).isEqualByComparingTo(actual.path("total").decimalValue());
        assertThat(simulated.body().path("outputs").path("approved").asBoolean()).isTrue();
    }
    @Test void optionalMissingListsProduceZeroInPinnedDecisionAndOutputMapping() throws Exception {
        JsonNode actual=execute(workflow(),input(BigDecimal.TEN));
        assertThat(actual.path("count").asInt()).isZero();
        assertThat(actual.path("total").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(actual.path("approved").asBoolean()).isFalse();
    }
}
