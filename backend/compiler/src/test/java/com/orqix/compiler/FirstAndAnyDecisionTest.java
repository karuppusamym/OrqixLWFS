package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** RL04: the FIRST hit policy (ordered, overlapping rows) and OR rows (docs/build/S40). */
class FirstAndAnyDecisionTest {

    private static ObjectNode table(ObjectNode d) {
        return (ObjectNode) d.path("decisions").get(0);
    }

    private static final tools.jackson.databind.json.JsonMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();

    /** Replaces the rows with the given conditions; each row sets the same output keys as the original first row. */
    private static ObjectNode withRows(String hitPolicy, String... conditions) {
        ObjectNode d = TestSupport.purchase();
        ObjectNode t = table(d);
        ObjectNode template = (ObjectNode) t.path("rows").get(0).deepCopy();
        ArrayNode rows = t.putArray("rows");
        for (String condition : conditions) {
            ObjectNode r = template.deepCopy();
            r.set("when", MAPPER.readTree(condition));
            rows.add(r);
        }
        t.put("hitPolicy", hitPolicy);
        return d;
    }

    private static CompiledWorkflow compile(String text) throws Exception {
        var issues = Compilers.workflowCompiler().validate(text, SimulatorFixtures.context().workspace()).issues();
        assertThat(issues.stream().filter(i -> i.severity() == Severity.BLOCKER).toList()).as("blocking issues").isEmpty();
        return Compilers.workflowCompiler().compile(text, SimulatorFixtures.context().workspace());
    }

    private static boolean overlap(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues()
                .stream().anyMatch(i -> i.code().equals("WF_DECISION_OVERLAP"));
    }

    private static final String BIG = "{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":10000}";
    private static final String MEDIUM = "{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":5000}";

    @Test
    void firstAllowsOverlappingRowsAndTheFirstMatchWins() throws Exception {
        ObjectNode first = withRows("FIRST", BIG, MEDIUM);
        assertThat(overlap(first)).isFalse();
        assertThat(overlap(withRows("UNIQUE", BIG, MEDIUM))).as("the same rows are rejected under UNIQUE").isTrue();

        String text = first.toString();
        var compiled = compile(text);
        assertThat(compiled.dmnResources().get("decisions/amount-policy.dmn")).contains("hitPolicy=\"FIRST\"");
        var eval = Compilers.decisionEvaluator();
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 12000)).matchedRow()).isEqualTo(0);
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 6000)).matchedRow()).isEqualTo(1);
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 100)).usedDefault()).isTrue();
    }

    @Test
    void anyRowsMatchWhenOneAlternativeHoldsAndCompileToOneDmnRulePerAlternative() throws Exception {
        String either = "{\"any\":[{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":100},"
                + "{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"HR\"}]}";
        ObjectNode d = withRows("FIRST", either, MEDIUM);
        String text = d.toString();
        var compiled = compile(text);
        String dmn = compiled.dmnResources().get("decisions/amount-policy.dmn");
        assertThat(dmn).contains("amount-policy__r1_1", "amount-policy__r1_2", "amount-policy__r2", "amount-policy__default");
        assertThat(dmn).doesNotContain("amount-policy__r2_1");

        var eval = Compilers.decisionEvaluator();
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 50, "/input/costCenter", "IT")).matchedRow()).isEqualTo(0);
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 9000, "/input/costCenter", "HR")).matchedRow()).isEqualTo(0);
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 9000, "/input/costCenter", "IT")).matchedRow()).isEqualTo(1);
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 500, "/input/costCenter", "IT")).usedDefault()).isTrue();
        // The simulator agrees with the evaluator and explains the alternatives in words.
        var trace = SimulatorFixtures.run(text,
                "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"HR\",\"amount\":9000}", "{}");
        assertThat(trace.path("outcome").path("status").asString()).isEqualTo("finished");
        assertThat(trace.path("trace").toString()).contains("at least one of these is true");
    }

    @Test
    void anyRowsMayRepeatAFieldButUniqueTablesStayProvablyDisjoint() {
        String outsideTheMiddle = "{\"any\":[{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":100},"
                + "{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":10000}]}";
        assertThat(Compilers.workflowCompiler().validate(withRows("UNIQUE", outsideTheMiddle).toString(),
                SimulatorFixtures.context().workspace()).issues()).noneMatch(i -> i.code().equals("WF_DECISION_DUPLICATE_INPUT")
                        || i.code().equals("WF_DECISION_OVERLAP"));
        // A second row that intersects one alternative is an overlap under UNIQUE.
        assertThat(overlap(withRows("UNIQUE", outsideTheMiddle, MEDIUM))).isTrue();
        assertThat(overlap(withRows("FIRST", outsideTheMiddle, MEDIUM))).isFalse();
    }
}
