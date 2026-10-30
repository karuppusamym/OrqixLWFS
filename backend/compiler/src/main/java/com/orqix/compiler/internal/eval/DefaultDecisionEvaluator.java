package com.orqix.compiler.internal.eval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.ConditionMatcher;
import com.orqix.compiler.DecisionEvaluator;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.ModelReader;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;

import tools.jackson.databind.JsonNode;

/**
 * Canonical decision evaluation with exactly the semantics of the compiled DMN. UNIQUE, FIRST and ANY: rows in order,
 * the first match wins (UNIQUE rows are proven disjoint and overlapping ANY rows proven to agree). PRIORITY: among the
 * matching rows the one whose priority result ranks highest, equal ranks in table order. COLLECT: every matching row,
 * its single number result combined by SUM, MIN, MAX or COUNT. When no row matches the default applies. Works on
 * drafts: only the requested table must be well formed.
 */
public final class DefaultDecisionEvaluator implements DecisionEvaluator {

    /** Reserved value key: the date the rows' effective windows are checked against (a {@code LocalDate} or ISO date text). */
    public static final String AS_OF = "$asOf";


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
        return evaluate(table, values);
    }

    /** Evaluates one typed table; shared with the decision table facade. */
    public static Result evaluate(DecisionTable table, Map<String, Object> values) {
        Map<String, Object> input = values == null ? Map.of() : values;
        java.time.LocalDate asOf = null;
        if (table.dated()) {
            Object given = input.get(AS_OF);
            if (given == null) {
                throw new IllegalArgumentException("No value for " + AS_OF);
            }
            asOf = given instanceof java.time.LocalDate d ? d : java.time.LocalDate.parse(given.toString());
        }
        // Single-result policies stop at the first match, as before; PRIORITY and COLLECT look at every row.
        boolean allRows = table.collect() || DecisionTable.PRIORITY.equals(table.hitPolicy());
        List<DecisionTable.Row> matching = new ArrayList<>();
        for (DecisionTable.Row row : table.rows()) {
            if (asOf != null && !row.appliesOn(asOf)) {
                continue;
            }
            if (matches(row.when(), input)) {
                matching.add(row);
                if (!allRows) {
                    break;
                }
            }
        }
        if (matching.isEmpty()) {
            return new Result(null, true, output(table.defaultOutput()), List.of());
        }
        if (table.collect()) {
            String name = table.defaultOutput().keySet().iterator().next();
            List<BigDecimal> numbers = matching.stream()
                    .map(r -> ((Literal.Num) r.then().get(name)).number()).toList();
            BigDecimal combined = aggregate(table.aggregation(), numbers);
            return new Result(matching.get(0).index(), false, Collections.unmodifiableMap(new LinkedHashMap<>(Map.of(name,
                    combined))), matching.stream().map(DecisionTable.Row::index).toList());
        }
        DecisionTable.Row chosen = matching.get(0);
        if (DecisionTable.PRIORITY.equals(table.hitPolicy()) && table.priority() != null) {
            for (DecisionTable.Row row : matching) {
                if (table.priority().rank(row.then().get(table.priority().output()))
                        < table.priority().rank(chosen.then().get(table.priority().output()))) {
                    chosen = row;
                }
            }
        }
        return new Result(chosen.index(), false, output(chosen.then()), List.of(chosen.index()));
    }

    /** Exact decimal combination; COUNT is the number of matching rows. */
    public static BigDecimal aggregate(String aggregation, List<BigDecimal> numbers) {
        if (aggregation == null) {
            throw new IllegalArgumentException("COLLECT needs an aggregation");
        }
        return switch (aggregation) {
            case "SUM" -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "MIN" -> numbers.stream().min(BigDecimal::compareTo).orElseThrow();
            case "MAX" -> numbers.stream().max(BigDecimal::compareTo).orElseThrow();
            case "COUNT" -> BigDecimal.valueOf(numbers.size());
            default -> throw new IllegalArgumentException("Unknown aggregation " + aggregation);
        };
    }

    /** Every referenced field must have a value, whether the row is a conjunction or a set of alternatives. */
    static boolean matches(Condition when, Map<String, Object> input) {
        boolean any = when.isAny();
        boolean matches = !any;
        for (Condition condition : when.compound() ? when.terms() : List.of(when)) {
            String fieldRef = condition.fieldRef();
            if (!input.containsKey(fieldRef) || input.get(fieldRef) == null) {
                throw new IllegalArgumentException("No value for " + fieldRef);
            }
            boolean term = ConditionMatcher.matches(condition.operator().json(), input.get(fieldRef), condition.operand());
            matches = any ? matches | term : matches & term;
        }
        return matches;
    }

    private static Map<String, Object> output(Map<String, Literal> literals) {
        Map<String, Object> out = new LinkedHashMap<>();
        literals.forEach((k, v) -> out.put(k, v.value()));
        return Collections.unmodifiableMap(out);
    }
}
