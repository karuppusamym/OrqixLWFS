package com.orqix.migration.dmn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.orqix.migration.camunda.Expressions.Op;
import com.orqix.shared.json.Json;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One DMN decision table whose single tested input and literal outputs were read successfully, with the proof that it
 * converts to canonical rows. The canonical table (workflow-v0 {@code decisionTable}) has exactly one comparison per
 * row, rows that exclude each other, and a default result for values no row accepts; {@link #plan()} derives those from
 * the source rules by set algebra and reports every case where that would change which rule fires.
 */
public final class DmnTable {

    /** One output column. {@code type} is number | integer | string | boolean. */
    public record Output(String key, String type, String title) {
    }

    /** One source rule: its input region, literal outputs and the original cell texts (kept for the report). */
    public record Rule(String id, int index, String inputText, List<String> outputTexts, Map<String, Object> outputs,
            Region region) {
    }

    /** One canonical row. */
    public record Row(String ruleId, Op op, Object value, Map<String, Object> then) {
    }

    /** Result of {@link #plan()}: the rows, the default and notes, or the reasons the table cannot be converted. */
    public record Plan(List<Row> rows, Map<String, Object> defaultOutput, String defaultRuleId, boolean defaultUnreachable,
            List<String> shadowedRules, List<Problem> problems) {

        public boolean convertible() {
            return problems.isEmpty();
        }
    }

    /** A reason a table or rule cannot be converted. {@code ruleId} may be null for table-level problems. */
    public record Problem(String ruleId, String code, String message) {
    }

    private final String decisionId;
    private final String name;
    private final String hitPolicy;
    private final String inputKey;
    private final String inputType;
    private final String inputTitle;
    private final List<String> inputEnum;
    private final List<Output> outputs;
    private final List<Rule> rules;
    private Plan plan;

    DmnTable(String decisionId, String name, String hitPolicy, String inputKey, String inputType, String inputTitle,
            List<String> inputEnum, List<Output> outputs, List<Rule> rules) {
        this.decisionId = decisionId;
        this.name = name;
        this.hitPolicy = hitPolicy;
        this.inputKey = inputKey;
        this.inputType = inputType;
        this.inputTitle = inputTitle;
        this.inputEnum = inputEnum == null ? null : List.copyOf(inputEnum);
        this.outputs = List.copyOf(outputs);
        this.rules = List.copyOf(rules);
    }

    public String decisionId() {
        return decisionId;
    }

    public String name() {
        return name;
    }

    public String hitPolicy() {
        return hitPolicy;
    }

    /** Canonical request field key of the tested input, for example {@code amount}. */
    public String inputKey() {
        return inputKey;
    }

    /** number | integer | string | boolean. */
    public String inputType() {
        return inputType;
    }

    public String inputTitle() {
        return inputTitle;
    }

    public List<String> inputEnum() {
        return inputEnum;
    }

    public List<Output> outputs() {
        return outputs;
    }

    public List<Rule> rules() {
        return rules;
    }

    /** Request-form field schema for the tested input. */
    public ObjectNode inputField(Json json) {
        ObjectNode field = json.object();
        field.put("type", inputType);
        field.put("title", limit(inputTitle, 100));
        if (inputEnum != null && !inputEnum.isEmpty()) {
            ArrayNode values = field.putArray("enum");
            inputEnum.forEach(values::add);
        }
        return field;
    }

    public Plan plan() {
        if (plan == null) {
            plan = computePlan();
        }
        return plan;
    }

    /** The canonical decision table testing {@code fieldRef}, or null when the table cannot be converted. */
    public ObjectNode table(String tableKey, String fieldRef, Json json) {
        Plan p = plan();
        if (!p.convertible()) {
            return null;
        }
        ObjectNode table = json.object();
        table.put("key", tableKey);
        table.put("version", 1);
        table.put("hitPolicy", "UNIQUE");
        ArrayNode rows = table.putArray("rows");
        for (Row row : p.rows()) {
            ObjectNode entry = rows.addObject();
            ObjectNode when = entry.putObject("when");
            when.put("fieldRef", fieldRef);
            when.put("operator", row.op().canonical());
            put(when, "value", row.value());
            ObjectNode then = entry.putObject("then");
            row.then().forEach((key, value) -> put(then, key, value));
        }
        ObjectNode fallback = table.putObject("default");
        p.defaultOutput().forEach((key, value) -> put(fallback, key, value));
        return table;
    }

    private static void put(ObjectNode node, String key, Object value) {
        if (value instanceof BigDecimal n) {
            node.put(key, n);
        } else if (value instanceof Boolean b) {
            node.put(key, b);
        } else {
            node.put(key, (String) value);
        }
    }

    static String limit(String text, int max) {
        String value = text == null || text.isBlank() ? "Value" : text.strip();
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ------------------------------------------------------------------------------------------------ derivation

    private Plan computePlan() {
        List<Problem> problems = new ArrayList<>();
        if (rules.isEmpty()) {
            problems.add(new Problem(null, "DMN_NO_CONDITIONAL_RULE", "The table has no rules."));
            return new Plan(List.of(), Map.of(), null, false, List.of(), problems);
        }
        Region seen = rules.get(0).region().none();
        boolean unique = "UNIQUE".equals(hitPolicy);
        List<Region> effective = new ArrayList<>();
        List<String> shadowed = new ArrayList<>();
        for (Rule rule : rules) {
            Region eff = rule.region().minus(seen);
            if (unique) {
                if (!rule.region().intersect(seen).isEmpty()) {
                    problems.add(new Problem(rule.id(), "DMN_UNIQUE_OVERLAP", "Rule " + (rule.index() + 1) + " accepts values that "
                            + "an earlier rule also accepts, but the hit policy UNIQUE allows only one match. The source "
                            + "table would fail at run time for those values."));
                }
                eff = rule.region();
            } else if (eff.isEmpty() && !rule.region().isEmpty()) {
                shadowed.add(rule.id());
            }
            if (rule.region().isEmpty()) {
                problems.add(new Problem(rule.id(), "DMN_ENTRY_NOT_SUPPORTED", "Rule " + (rule.index() + 1) + " accepts no value "
                        + "(an empty range or list), so it can never fire."));
            }
            effective.add(eff);
            seen = seen.union(rule.region());
        }
        if (!problems.isEmpty()) {
            return new Plan(List.of(), Map.of(), null, false, shadowed, problems);
        }
        List<List<Piece>> pieces = new ArrayList<>();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            List<Piece> ps = effective.get(i).isEmpty() ? List.of() : pieces(effective.get(i));
            pieces.add(ps);
            if (ps == null) {
                candidates.add(i);
            }
        }
        Integer defaultIndex = null;
        if (candidates.size() > 1) {
            String named = candidates.stream().map(i -> String.valueOf(rules.get(i).index() + 1)).reduce((a, b) -> a + ", " + b)
                    .orElse("");
            problems.add(new Problem(null, "DMN_RULE_NOT_REPRESENTABLE", "Rules " + named + " each accept a bounded range, a list "
                    + "of exclusions or every value. A canonical row holds one comparison, so only one such rule per table can "
                    + "be carried (as the default result). Split the table or rewrite the ranges as open comparisons."));
        } else if (candidates.size() == 1) {
            defaultIndex = candidates.get(0);
        } else {
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).region().isEverything() && !effective.get(i).isEmpty()) {
                    defaultIndex = i;
                    break;
                }
            }
        }
        if (problems.isEmpty() && !seen.isEverything()) {
            problems.add(new Problem(null, "DMN_NO_MATCH_NOT_REPRESENTABLE", "Some " + (seen.numeric() ? "numbers" : "values")
                    + " match no rule. The source decision returns no result for them, but a canonical table always returns its "
                    + "default. Add a catch-all rule (entry -) with the intended result."));
        }
        if (!problems.isEmpty()) {
            return new Plan(List.of(), Map.of(), null, false, shadowed, problems);
        }
        boolean unreachable = defaultIndex == null;
        if (unreachable) {
            for (int i = rules.size() - 1; i >= 0; i--) {
                if (!effective.get(i).isEmpty()) {
                    defaultIndex = i;
                    break;
                }
            }
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            if (i == defaultIndex && !unreachable || pieces.get(i) == null) {
                continue;
            }
            for (Piece piece : pieces.get(i)) {
                rows.add(new Row(rules.get(i).id(), piece.op(), piece.value(), rules.get(i).outputs()));
            }
        }
        if (rows.isEmpty()) {
            problems.add(new Problem(null, "DMN_NO_CONDITIONAL_RULE", "No rule tests the input, so the table is one fixed "
                    + "result. A canonical table needs at least one conditional row."));
        } else if (rows.size() > 50) {
            problems.add(new Problem(null, "DMN_TOO_MANY_ROWS", "The table expands to " + rows.size() + " rows; a canonical "
                    + "table holds at most 50."));
        }
        if (!problems.isEmpty()) {
            return new Plan(List.of(), Map.of(), null, false, shadowed, problems);
        }
        Rule fallback = rules.get(defaultIndex);
        return new Plan(List.copyOf(rows), fallback.outputs(), fallback.id(), unreachable, shadowed, List.of());
    }

    private record Piece(Op op, Object value) {
    }

    /** Canonical comparisons that together equal {@code region}, or null when one comparison per row cannot express it. */
    private static List<Piece> pieces(Region region) {
        List<Piece> out = new ArrayList<>();
        if (region.numeric()) {
            List<Region.Interval> intervals = region.intervals();
            if (intervals.size() == 2 && intervals.get(0).lo() == null && intervals.get(1).hi() == null) {
                Region.Interval a = intervals.get(0);
                Region.Interval b = intervals.get(1);
                boolean singleGap = region.isInteger() ? b.lo().subtract(a.hi()).compareTo(new BigDecimal(2)) == 0
                        : a.hi().compareTo(b.lo()) == 0 && !a.hiIncl() && !b.loIncl();
                if (singleGap) {
                    BigDecimal gap = region.isInteger() ? a.hi().add(BigDecimal.ONE) : a.hi();
                    return List.of(new Piece(Op.NE, gap));
                }
            }
            for (Region.Interval iv : intervals) {
                if (iv.isPoint()) {
                    out.add(new Piece(Op.EQ, iv.lo()));
                } else if (iv.lo() == null && iv.hi() == null) {
                    return null;
                } else if (iv.lo() == null) {
                    out.add(new Piece(iv.hiIncl() ? Op.LTE : Op.LT, iv.hi()));
                } else if (iv.hi() == null) {
                    out.add(new Piece(iv.loIncl() ? Op.GTE : Op.GT, iv.lo()));
                } else {
                    return null;
                }
            }
            return out;
        }
        if (region.isComplement()) {
            List<Object> excluded = region.excluded();
            return excluded.size() == 1 ? List.of(new Piece(Op.NE, excluded.get(0))) : null;
        }
        for (Object member : region.members()) {
            out.add(new Piece(Op.EQ, member));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ source semantics

    /**
     * The source engine's answer for one input value, by the rules of the hit policy (UNIQUE: the one matching rule;
     * FIRST: the first matching rule). Independent of {@link #plan()}: it reads the parsed source regions only.
     *
     * @return the matching rule, or null when none matches
     */
    public Rule sourceMatch(Object value) {
        Rule match = null;
        for (Rule rule : rules) {
            if (rule.region().contains(value)) {
                if (match != null) {
                    throw new IllegalStateException("More than one rule matches under hit policy " + hitPolicy);
                }
                match = rule;
                if ("FIRST".equals(hitPolicy)) {
                    return match;
                }
            }
        }
        return match;
    }

    /** Values worth testing: for every finite bound one step below, at and one step above, plus the extremes. */
    public List<Object> boundaryInputs() {
        List<Object> inputs = new ArrayList<>();
        switch (inputType) {
            case "boolean" -> inputs.addAll(List.of(Boolean.TRUE, Boolean.FALSE));
            case "string" -> {
                Set<Object> literals = new java.util.LinkedHashSet<>();
                for (Rule rule : rules) {
                    literals.addAll(rule.region().isComplement() ? rule.region().excluded() : rule.region().members());
                }
                inputs.addAll(literals);
                if (inputEnum == null) {
                    inputs.add("unlisted value");
                }
            }
            default -> {
                BigDecimal step = "integer".equals(inputType) ? BigDecimal.ONE : new BigDecimal("0.01");
                java.util.TreeSet<BigDecimal> values = new java.util.TreeSet<>();
                for (Rule rule : rules) {
                    for (Region.Interval iv : rule.region().intervals()) {
                        for (BigDecimal bound : new BigDecimal[] {iv.lo(), iv.hi()}) {
                            if (bound != null) {
                                values.add(bound.subtract(step));
                                values.add(bound);
                                values.add(bound.add(step));
                            }
                        }
                    }
                }
                if (values.isEmpty()) {
                    values.add(BigDecimal.ZERO);
                }
                values.add(values.first().subtract(new BigDecimal(1000)));
                values.add(values.last().add(new BigDecimal(1000)));
                inputs.addAll(values);
            }
        }
        return inputs.size() > 200 ? List.copyOf(inputs.subList(0, 200)) : inputs;
    }

    /** True when {@code a} and {@code b} are the same literal (numbers compare as decimals). */
    static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    static Map<String, Object> copy(Map<String, Object> map) {
        return new LinkedHashMap<>(map);
    }
}
