package com.orqix.runtime;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.node.ObjectNode;

class ConditionalFormsTest extends RuntimeTestBase {
    @Test void hiddenQuestionIsOmittedAndMaliciousRetainedValueIsRejectedAgainstPinnedUi() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,d->((ObjectNode)d.path("inputUi")).putObject("visibleWhen")
                .set("purpose",json.parse("{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"OPS\"}")));
        var input=input(BigDecimal.TEN);input.put("costCenter","IT");
        var hidden=api("rhea.requester").post(Map.of("workflowId",workflow,"input",input),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(hidden.status()).as(hidden.toString()).isEqualTo(422);
        assertThat(hidden.body().path("fieldErrors").toString()).contains("/input/purpose","hidden");
        input.remove("purpose");
        var accepted=api("rhea.requester").post(Map.of("workflowId",workflow,"input",input),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(201);
        UUID run=UUID.fromString(accepted.body().path("id").asString());
        var detail=api("rhea.requester").get("/workspaces/{ws}/runs/{run}",PURCHASING,run);
        assertThat(detail.body().path("input").has("purpose")).isFalse();
        assertThat(openTask(PURCHASING,run,"manager")).isNotNull();
    }
    @Test void boundedRowsValidateStrictColumnsAndPersistAsTypedRequestData() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,d->{
            ((ObjectNode)d.path("inputSchema").path("properties")).set("lines",json.parse("""
              {"type":"array","title":"Line items","minItems":1,"maxItems":2,"items":{"type":"object","additionalProperties":false,"required":["label","quantity"],"properties":{
                "label":{"type":"string","title":"Item","minLength":1,"maxLength":40},
                "quantity":{"type":"integer","title":"Quantity","minimum":1,"maximum":10}}}}
              """));
            ((tools.jackson.databind.node.ArrayNode)d.path("inputSchema").path("required")).add("lines");
        });
        var input=input(BigDecimal.TEN);input.put("lines",List.of(Map.of("label","Laptop","quantity","2","unknown",true)));
        var invalid=api("rhea.requester").post(Map.of("workflowId",workflow,"input",input),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(invalid.status()).as(invalid.toString()).isEqualTo(422);
        assertThat(invalid.body().path("fieldErrors").toString()).contains("/input/lines/0/quantity","/input/lines/0/unknown");
        input.put("lines",List.of(Map.of("label","Laptop","quantity",2)));
        var accepted=api("rhea.requester").post(Map.of("workflowId",workflow,"input",input),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(201);
        var detail=api("rhea.requester").get("/workspaces/{ws}/runs/{run}",PURCHASING,accepted.body().path("id").asString());
        assertThat(detail.body().path("input").path("lines").get(0).path("quantity").isIntegralNumber()).isTrue();
        assertThat(detail.body().path("input").path("lines").get(0).path("quantity").asInt()).isEqualTo(2);
    }
}
