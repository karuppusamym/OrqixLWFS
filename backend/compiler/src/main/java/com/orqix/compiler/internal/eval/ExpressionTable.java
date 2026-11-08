package com.orqix.compiler.internal.eval;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.compiler.ConditionMatcher;
import com.orqix.compiler.DecisionEvaluator.Entry;
import com.orqix.compiler.DecisionEvaluator.Result;
import com.orqix.compiler.expr.ExprType;
import com.orqix.compiler.expr.Expression;
import com.orqix.compiler.expr.ExpressionException;
import com.orqix.compiler.expr.PinnedTypes;
import com.orqix.compiler.expr.Scope;
import com.orqix.compiler.expr.Values;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;

import tools.jackson.databind.JsonNode;

/**
 * Canonical evaluation of a decision table whose rows use Orqix expressions as input or output entries (S146, doc 29
 * CG-D04). The TypeScript double is {@code frontend/src/lib/expr/decision.ts}; both pass
 * {@code examples/fixtures/decision-expression-cases.json}.
 * <ul>
 * <li>Every input entry of every row is evaluated, so the evidence can list all of them. An expression entry matches
 * when it gives true; false or no value (null) does not match. Literal comparisons keep their rule: the field must have
 * a value.</li>
 * <li>Output entries are evaluated for every matching row. A selected row whose expression gives no value stops the
 * evaluation (the result would be missing).</li>
 * <li>UNIQUE: two matching rows stop the evaluation (with expressions the compiler cannot prove the rows exclusive, so
 * the check happens here); ANY: matching rows with different results stop it. FIRST, PRIORITY and COLLECT as
 * before.</li>
 * </ul>
 * Every problem is an {@link IllegalArgumentException} with a business message.
 */
public final class ExpressionTable {

    /** Reserved value key: the instant {@code today()} uses (an {@link Instant} or ISO-8601 text). */
    public static final String NOW = "$now";

    private ExpressionTable() {
    }

    /** @return every field the table reads: the tested columns and the fields named inside expressions, sorted */
    public static Set<String> fields(DecisionTable table) {
        Set<String> out = new java.util.TreeSet<>();
        for (DecisionTable.Row row : table.rows()) {
            for (Condition term : row.terms()) {
                out.add(term.fieldRef());
                if (term.isExpression()) out.addAll(references(term.expression()));
            }
            row.thenExpressions().values().forEach(e -> out.addAll(references(e)));
        }
        return out;
    }

    private static Set<String> references(String source) {
        try {
            return Expression.references(source);
        } catch (ExpressionException e) {
            throw new IllegalArgumentException("The expression " + source + " is not valid: " + e.getMessage(), e);
        }
    }

    /**
     * @param types pinned types or null to infer them from {@code values} (a number, text, true/false or date value; a
     *              field without a value may be anything)
     */
    public static Result evaluate(DecisionTable table, Map<String, Object> values, JsonNode types) {
        Map<String, Object> input = values == null ? Map.of() : values;
        Scope scope = types != null ? PinnedTypes.scope(types, Map.of()) : inferred(table, input);
        Values reader = reader(input);
        LocalDate asOf = DefaultDecisionEvaluator.asOf(table, input);
        List<Entry> entries = new ArrayList<>();
        List<DecisionTable.Row> matching = new ArrayList<>();
        for (DecisionTable.Row row : table.rows()) {
            boolean any = row.when().isAny();
            boolean matched = !any;
            List<Condition> terms = row.terms();
            for (int i = 0; i < terms.size(); i++) {
                Condition term = terms.get(i);
                boolean hit;
                if (term.isExpression()) {
                    Object result = run(term.expression(), scope, reader, ExprType.BOOLEAN, row, null);
                    entries.add(new Entry(row.index(), i, "input", term.fieldRef(), term.expression(), result));
                    hit = Boolean.TRUE.equals(result);
                } else {
                    Object actual = input.get(term.fieldRef());
                    if (actual == null) {
                        throw new IllegalArgumentException("No value for " + term.fieldRef());
                    }
                    hit = ConditionMatcher.matches(term.operator().json(), plain(actual), term.operand());
                }
                matched = any ? matched | hit : matched & hit;
            }
            if (matched && (asOf == null || row.appliesOn(asOf))) {
                matching.add(row);
            }
        }
        if (matching.isEmpty()) {
            return new Result(null, true, literals(table.defaultOutput()), List.of(), List.copyOf(entries));
        }
        Map<Integer, Map<String, Object>> outputs = new LinkedHashMap<>();
        for (DecisionTable.Row row : matching) {
            Map<String, Object> out = new java.util.TreeMap<>();
            row.then().forEach((k, v) -> out.put(k, v.value()));
            for (Map.Entry<String, String> e : row.thenExpressions().entrySet()) {
                Literal reference = table.defaultOutput().get(e.getKey());
                ExprType wanted = reference == null ? null : type(reference);
                Object value = run(e.getValue(), scope, reader, wanted, row, e.getKey());
                entries.add(new Entry(row.index(), -1, "output", e.getKey(), e.getValue(), value));
                out.put(e.getKey(), value);
            }
            outputs.put(row.index(), out);
        }
        // Canonical order: by row, the row's tests by term, then its results by name.
        entries.sort(java.util.Comparator.comparingInt(Entry::row).thenComparing(e -> e.term() < 0)
                .thenComparingInt(Entry::term).thenComparing(Entry::target));
        List<Integer> indexes = matching.stream().map(DecisionTable.Row::index).toList();
        String policy = table.hitPolicy();
        if (DecisionTable.UNIQUE.equals(policy) && matching.size() > 1) {
            throw new IllegalArgumentException("Rules " + rules(indexes) + " all apply, but with \"Only one row\" at most"
                    + " one rule may apply. Make the conditions exclusive or choose \"First matching row\".");
        }
        if (DecisionTable.ANY.equals(policy)) {
            Map<String, Object> first = outputs.get(indexes.get(0));
            for (Integer i : indexes) {
                if (!same(first, outputs.get(i))) {
                    throw new IllegalArgumentException("Rules " + (indexes.get(0) + 1) + " and " + (i + 1)
                            + " both apply but give different results, which \"Any matching row\" does not allow.");
                }
            }
        }
        if (table.collect()) {
            String name = table.defaultOutput().keySet().iterator().next();
            List<BigDecimal> numbers = new ArrayList<>();
            for (DecisionTable.Row row : matching) {
                Object value = complete(outputs.get(row.index()), row, name);
                if (!(value instanceof BigDecimal n)) {
                    throw new IllegalArgumentException("Rule " + (row.index() + 1) + " must give a number for " + name + ".");
                }
                numbers.add(n);
            }
            Map<String, Object> combined = new LinkedHashMap<>();
            combined.put(name, DefaultDecisionEvaluator.aggregate(table.aggregation(), numbers));
            return new Result(indexes.get(0), false, Collections.unmodifiableMap(combined), indexes, List.copyOf(entries));
        }
        DecisionTable.Row chosen = matching.get(0);
        if (DecisionTable.PRIORITY.equals(policy) && table.priority() != null) {
            String p = table.priority().output();
            for (DecisionTable.Row row : matching) {
                if (row.thenExpressions().containsKey(p)) {
                    throw new IllegalArgumentException("The priority result " + p + " of rule " + (row.index() + 1)
                            + " cannot be an expression.");
                }
                if (table.priority().rank(row.then().get(p)) < table.priority().rank(chosen.then().get(p))) {
                    chosen = row;
                }
            }
        }
        Map<String, Object> out = outputs.get(chosen.index());
        for (String key : out.keySet()) {
            complete(out, chosen, key);
        }
        return new Result(chosen.index(), false, Collections.unmodifiableMap(new LinkedHashMap<>(out)),
                List.of(chosen.index()), List.copyOf(entries));
    }

    private static Object complete(Map<String, Object> out, DecisionTable.Row row, String key) {
        Object value = out.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Rule " + (row.index() + 1) + " gives no value for " + key
                    + " (a missing field or a division by zero in its expression).");
        }
        return value;
    }

    private static Object run(String source, Scope scope, Values values, ExprType wanted, DecisionTable.Row row,
            String output) {
        String where = output == null ? "a test of rule " + (row.index() + 1) : "the result " + output + " of rule "
                + (row.index() + 1);
        try {
            Expression e = Expression.compile(source, scope);
            if (wanted != null && !(e.type() == ExprType.NULL || e.type() == ExprType.ANY || e.type().equals(wanted))) {
                throw new IllegalArgumentException("The expression of " + where + " must give " + wanted.describe()
                        + ", but it gives " + e.type().describe() + ".");
            }
            Object value = e.evaluate(values);
            if (value != null && wanted != null && e.type() == ExprType.ANY && !fits(value, wanted)) {
                throw new IllegalArgumentException("The expression of " + where + " must give " + wanted.describe() + ".");
            }
            return value;
        } catch (ExpressionException problem) {
            throw new IllegalArgumentException("The expression of " + where + " cannot be evaluated: "
                    + problem.getMessage(), problem);
        }
    }

    private static boolean fits(Object value, ExprType wanted) {
        return wanted == ExprType.NUMBER ? value instanceof BigDecimal
                : wanted == ExprType.BOOLEAN ? value instanceof Boolean
                : wanted != ExprType.STRING || value instanceof String;
    }

    static ExprType type(Literal literal) {
        return switch (literal) {
            case Literal.Num n -> ExprType.NUMBER;
            case Literal.Str s -> ExprType.STRING;
            case Literal.Bool b -> ExprType.BOOLEAN;
        };
    }

    /** Results agree when every value is equal; numbers by value (5 equals 5.00). */
    static boolean same(Map<String, Object> a, Map<String, Object> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<String, Object> e : a.entrySet()) {
            Object x = e.getValue(), y = b.get(e.getKey());
            if (x instanceof BigDecimal m && y instanceof BigDecimal n) {
                if (m.compareTo(n) != 0) return false;
            } else if (!java.util.Objects.equals(x, y)) {
                return false;
            }
        }
        return true;
    }

    private static String rules(List<Integer> indexes) {
        List<String> numbers = indexes.stream().map(i -> String.valueOf(i + 1)).toList();
        return String.join(", ", numbers.subList(0, numbers.size() - 1)) + " and " + numbers.get(numbers.size() - 1);
    }

    private static Map<String, Object> literals(Map<String, Literal> literals) {
        Map<String, Object> out = new LinkedHashMap<>();
        literals.forEach((k, v) -> out.put(k, v.value()));
        return Collections.unmodifiableMap(out);
    }

    /** A JSON value of a literal comparison as the matcher takes it. */
    private static Object plain(Object value) {
        if (value instanceof JsonNode node) {
            if (node.isNumber()) return node.decimalValue();
            if (node.isBoolean()) return node.booleanValue();
            return node.asString();
        }
        if (value instanceof LocalDate d) return d.toString();
        return value;
    }

    /** Types from the given values: numbers, texts, true/false and dates; a field without a value may be anything. */
    private static Scope inferred(DecisionTable table, Map<String, Object> values) {
        Map<String, ExprType> input = new LinkedHashMap<>();
        Map<String, Map<String, ExprType>> steps = new LinkedHashMap<>();
        Set<String> refs = new LinkedHashSet<>(fields(table));
        for (String ref : refs) {
            ExprType t = typeOf(values.get(ref));
            String[] parts = ref.split("/");
            if (ref.startsWith("/input/") && parts.length == 3) {
                input.put(parts[2], t);
            } else if (ref.startsWith("/steps/") && parts.length == 4) {
                steps.computeIfAbsent(parts[2], k -> new LinkedHashMap<>()).put(parts[3], t);
            }
        }
        return Scope.of(input, steps, Map.of());
    }

    private static ExprType typeOf(Object value) {
        if (value instanceof JsonNode node) {
            if (node.isNumber()) return ExprType.NUMBER;
            if (node.isBoolean()) return ExprType.BOOLEAN;
            if (node.isString()) return ExprType.STRING;
            return ExprType.ANY;
        }
        if (value instanceof Number) return ExprType.NUMBER;
        if (value instanceof Boolean) return ExprType.BOOLEAN;
        if (value instanceof LocalDate) return ExprType.DATE;
        if (value instanceof String) return ExprType.STRING;
        return ExprType.ANY;
    }

    private static Values reader(Map<String, Object> values) {
        return new Values() {
            @Override
            public Object input(String key) {
                return value(values.get("/input/" + key));
            }

            @Override
            public Object step(String stepId, String key) {
                return value(values.get("/steps/" + stepId + "/" + key));
            }

            @Override
            public Instant now() {
                Object now = values.get(NOW);
                if (now instanceof Instant i) return i;
                if (now != null) {
                    try {
                        return Instant.parse(now.toString());
                    } catch (java.time.format.DateTimeParseException e) {
                        throw new IllegalArgumentException(NOW + " must be an ISO-8601 instant", e);
                    }
                }
                Object asOf = values.get(DefaultDecisionEvaluator.AS_OF);
                if (asOf != null) {
                    LocalDate day = asOf instanceof LocalDate d ? d : LocalDate.parse(asOf.toString());
                    return day.atStartOfDay(ZoneOffset.UTC).toInstant();
                }
                throw new IllegalArgumentException("No value for " + NOW);
            }
        };
    }

    private static Object value(Object v) {
        if (v instanceof Number n && !(v instanceof BigDecimal)) return new BigDecimal(n.toString());
        return v;
    }
}
