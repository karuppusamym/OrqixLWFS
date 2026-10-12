package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ConjunctiveDecisionTest {
    static ObjectNode definition() {
        var d=TestSupport.purchase();
        var row=(ObjectNode)d.path("decisions").get(0).path("rows").get(0);
        var old=row.path("when").deepCopy();
        var all=row.putObject("when").putArray("all");
        all.add(old);
        all.addObject().put("fieldRef","/input/costCenter").put("operator","eq").put("value","IT");
        return d;
    }
    @Test void compilesTwoInputsAndEvaluatorAndSimulationAgree() throws Exception {
        String text=definition().toString();
        var compiled=Compilers.workflowCompiler().compile(text,SimulatorFixtures.context().workspace());
        assertThat(compiled.dmnResources().get("decisions/amount-policy.dmn")).contains("in1","in2","/input/amount","/input/costCenter");
        var eval=Compilers.decisionEvaluator();
        assertThat(eval.evaluate(text,"amount-policy",Map.of("/input/amount",5000,"/input/costCenter","IT")).usedDefault()).isFalse();
        assertThat(eval.evaluate(text,"amount-policy",Map.of("/input/amount",5000,"/input/costCenter","HR")).usedDefault()).isTrue();
        var trace=SimulatorFixtures.run(text,"{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":5000}","{}");
        assertThat(trace.path("outcome").path("status").asString()).isEqualTo("finished");
        assertThat(trace.path("trace").toString()).contains("all of these", "costCenter");
    }
    @Test void rejectsOverlapAndDuplicateFieldTerms() {
        var d=definition();
        var rows=(tools.jackson.databind.node.ArrayNode)d.path("decisions").get(0).path("rows");
        rows.add(rows.get(0).deepCopy());
        assertThat(Compilers.workflowCompiler().validate(d.toString(),SimulatorFixtures.context().workspace()).issues()).anyMatch(i->i.code().equals("WF_DECISION_OVERLAP"));
        d=definition();
        ((ObjectNode)d.path("decisions").get(0).path("rows").get(0).path("when").path("all").get(1)).put("fieldRef","/input/amount").put("value",6000).put("operator","gte");
        assertThat(Compilers.workflowCompiler().validate(d.toString(),SimulatorFixtures.context().workspace()).issues()).anyMatch(i->i.code().equals("WF_DECISION_DUPLICATE_INPUT"));
    }
}
