package com.orqix.compiler.internal.eval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.orqix.compiler.ConditionMatcher;
import com.orqix.compiler.DecisionEvaluator;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.ModelReader;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;

import tools.jackson.databind.JsonNode;

/**
 * Canonical decision evaluation with exactly the semantics of the compiled DMN: rows in order, first match wins
 * (rows are proven disjoint in valid definitions), otherwise the default. Works on drafts: only the requested table
 * must be well formed.
 */
public final class DefaultDecisionEvaluator implements DecisionEvaluator {

    @Override
    public Result evaluate(String definitionJson, String decisionKey, Map<String, Object> values) {
        JsonNode root = StrictJson.parse(definitionJson);
        JsonNode decisions = root.path("decisions");
        if (!decisions.isArray()) {
            throw new IllegalArgumentException("The definition has no decision tables");
        }
        DecisionTable table = null;
        for (int i = 0; i < decisions.size(); i++) {
            JsonNode candidate = decisions.get(i);
            if (candidate.path("key").isString() && candidate.get("key").stringValue().equals(decisionKey)) {
                table = ModelReader.readDecisionTable(i, candidate);
                break;
            }
        }
        if (table == null) {
            throw new IllegalArgumentException("Unknown decision table: " + decisionKey);
        }
        Map<String, Object> input = values == null ? Map.of() : values;
        for (DecisionTable.Row row : table.rows()) {
            boolean any = row.when().isAny();
            boolean matches = !any;
            // Every referenced field must have a value, whether the row is a conjunction or a set of alternatives.
            for(var condition:row.when().compound()?row.when().terms():java.util.List.of(row.when())) {
            String fieldRef = condition.fieldRef();
            if (!input.containsKey(fieldRef) || input.get(fieldRef) == null) {
                throw new IllegalArgumentException("No value for " + fieldRef);
            }
            boolean term = ConditionMatcher.matches(condition.operator().json(), input.get(fieldRef), condition.value().value());
            matches = any ? matches | term : matches & term;
            }
            if (matches) {
                return new Result(row.index(), false, output(row.then()));
            }
        }
        return new Result(null, true, output(table.defaultOutput()));
    }

    private static Map<String, Object> output(Map<String, Literal> literals) {
        Map<String, Object> out = new LinkedHashMap<>();
        literals.forEach((k, v) -> out.put(k, v.value()));
        return Collections.unmodifiableMap(out);
    }
}
