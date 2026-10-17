package com.orqix.migration.dmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.Compilers;
import com.orqix.compiler.DecisionEvaluator;
import com.orqix.migration.camunda.Report;
import com.orqix.shared.json.Json;

import tools.jackson.databind.node.ObjectNode;

/**
 * Generated behaviour check of a converted table. For every threshold of the source rules the value one step below, at
 * and one step above it (plus the extremes, every listed text value and an unlisted one) is answered twice: by the
 * source semantics (hit policy applied to the parsed source regions) and by the compiler's table evaluator on the
 * canonical table. Any difference makes the conversion refuse the table; nothing is approximated.
 */
public final class DmnSelfCheck {

    private DmnSelfCheck() {
    }

    /** Runs the cases of one convertible table; a failed case has status {@code failed}. */
    public static List<Report.TestCase> run(DmnTable table, Json json) {
        String key = "check";
        String fieldRef = "/input/" + table.inputKey();
        ObjectNode canonical = table.table(key, fieldRef, json);
        ObjectNode definition = json.object();
        definition.putArray("decisions").add(canonical);
        String definitionJson = json.write(definition);
        DecisionEvaluator evaluator = Compilers.decisionEvaluator();
        List<Report.TestCase> cases = new ArrayList<>();
        for (Object value : table.boundaryInputs()) {
            DmnTable.Rule expectedRule = table.sourceMatch(value);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(table.inputKey(), value);
            Map<String, Object> expected = expectedRule == null ? Map.of() : new LinkedHashMap<>(expectedRule.outputs());
            Map<String, Object> actual;
            Integer row;
            String status;
            try {
                DecisionEvaluator.Result result = evaluator.evaluate(definitionJson, key, Map.of(fieldRef, value));
                actual = new LinkedHashMap<>(result.output());
                row = result.matchedRow();
                status = expectedRule != null && sameOutputs(expected, actual) ? "passed" : "failed";
            } catch (IllegalArgumentException e) {
                actual = Map.of("error", String.valueOf(e.getMessage()));
                row = null;
                status = "failed";
            }
            String shown = value instanceof java.math.BigDecimal n ? n.toPlainString() : String.valueOf(value);
            cases.add(new Report.TestCase(table.decisionId(), table.inputKey() + " = " + shown, input, expected, actual, row, status));
        }
        return cases;
    }

    /** True when every case passed. */
    public static boolean allPassed(List<Report.TestCase> cases) {
        return cases.stream().allMatch(c -> "passed".equals(c.status()));
    }

    private static boolean sameOutputs(Map<String, Object> expected, Map<String, Object> actual) {
        if (!expected.keySet().equals(actual.keySet())) {
            return false;
        }
        return expected.entrySet().stream().allMatch(e -> DmnTable.same(e.getValue(), actual.get(e.getKey())));
    }
}
