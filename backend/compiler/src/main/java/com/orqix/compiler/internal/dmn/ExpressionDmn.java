package com.orqix.compiler.internal.dmn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.DecisionEvaluator;
import com.orqix.compiler.internal.bpmn.XmlOut;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;

/**
 * Engine DMN of a table with Orqix expression entries (S146). The engine cannot run Orqix expressions, so the
 * application evaluates them with the canonical evaluator first and hands the engine their results: each input entry
 * expression is a boolean input column {@code x<k>} tested with {@code == true}, and each output entry expression is
 * the output entry {@code #{y<k>}}. The literal comparisons keep their columns {@code in<n>} (one per compared
 * field, as {@link DmnGenerator} writes them), so the pinned DMN still selects the rows and the runtime still confirms
 * that selection against the canonical evaluation. The layout is the multi-column form: hit policy FIRST with a
 * catch-all default rule, or COLLECT without one; PRIORITY rows sorted by rank. Tables without expressions never come
 * here, so their DMN is unchanged.
 */
final class ExpressionDmn {

    private ExpressionDmn() {
    }

    /** A test written as an expression: row index and term index. */
    record Test(int row, int term) {
    }

    /** A result written as an expression: row index and result key. */
    record Computed(int row, String output) {
    }

    /** @return the fields compared with literals, sorted: the {@code in<n>} columns */
    static List<String> comparedFields(DecisionTable table) {
        return table.rows().stream().flatMap(r -> r.terms().stream()).filter(t -> !t.isExpression())
                .map(Condition::fieldRef).distinct().sorted().toList();
    }

    /** @return the expression tests in row order, then term order: column {@code x<k>} is element k-1 */
    static List<Test> tests(DecisionTable table) {
        List<Test> out = new ArrayList<>();
        for (DecisionTable.Row row : table.rows()) {
            List<Condition> terms = row.terms();
            for (int i = 0; i < terms.size(); i++) {
                if (terms.get(i).isExpression()) out.add(new Test(row.index(), i));
            }
        }
        return out;
    }

    /** @return the computed results in row order, then key order: variable {@code y<k>} is element k-1 */
    static List<Computed> computed(DecisionTable table) {
        List<Computed> out = new ArrayList<>();
        for (DecisionTable.Row row : table.rows()) {
            row.thenExpressions().keySet().forEach(k -> out.add(new Computed(row.index(), k)));
        }
        return out;
    }

    /**
     * @return the variables of the engine DMN: {@code in<n>} from {@code values}, {@code x<k>} true only when the
     *         canonical evaluation found the test true, {@code y<k>} the computed value or null
     */
    static Map<String, Object> engineVariables(DecisionTable table, Map<String, Object> values,
            DecisionEvaluator.Result result) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> fields = comparedFields(table);
        for (int i = 0; i < fields.size(); i++) {
            out.put("in" + (i + 1), values.get(fields.get(i)));
        }
        List<Test> tests = tests(table);
        for (int k = 0; k < tests.size(); k++) {
            Test t = tests.get(k);
            boolean value = result.expressions().stream().anyMatch(e -> e.row() == t.row() && e.term() == t.term()
                    && "input".equals(e.kind()) && Boolean.TRUE.equals(e.value()));
            out.put("x" + (k + 1), value);
        }
        List<Computed> computed = computed(table);
        for (int k = 0; k < computed.size(); k++) {
            Computed c = computed.get(k);
            Object value = result.expressions().stream().filter(e -> e.row() == c.row() && e.term() < 0
                    && c.output().equals(e.target())).map(DecisionEvaluator.Entry::value).findFirst().orElse(null);
            out.put("y" + (k + 1), value instanceof BigDecimal || value instanceof String || value instanceof Boolean
                    ? value : null);
        }
        return out;
    }

    static String generate(DecisionTable table, Map<String, FieldKind> kinds) {
        String ns = DmnGenerator.DMN_NS;
        List<String> fields = comparedFields(table);
        List<Test> tests = tests(table);
        List<Computed> computed = computed(table);
        String key = table.key();
        XmlOut x = new XmlOut();
        x.open("", "definitions", ns).defaultNamespace(ns).attr("id", "dmn_" + key + "_v" + table.version())
                .attr("name", key).attr("namespace", DmnGenerator.DECISIONS_NAMESPACE);
        x.open("", "decision", ns).attr("id", key).attr("name", key);
        x.open("", "decisionTable", ns).attr("id", key + "__table").attr("hitPolicy", table.collect() ? "COLLECT" : "FIRST");
        if (table.collect()) x.attr("aggregation", table.aggregation());
        for (int i = 0; i < fields.size(); i++) {
            x.open("", "input", ns).attr("id", key + "__in" + (i + 1)).attr("label", fields.get(i));
            x.inline("", "inputExpression", ns, "text", "in" + (i + 1), false, "id", key + "__inexpr" + (i + 1),
                    "typeRef", DmnGenerator.typeRef(kinds.get(fields.get(i))));
            x.close();
        }
        if (table.dated()) {
            for (String edge : new String[] {"From", "To"}) {
                x.open("", "input", ns).attr("id", key + "__in" + edge).attr("label", "effective" + edge);
                x.inline("", "inputExpression", ns, "text", "asOf", false, "id", key + "__inexpr" + edge, "typeRef", "number");
                x.close();
            }
        }
        for (int k = 0; k < tests.size(); k++) {
            Test t = tests.get(k);
            x.open("", "input", ns).attr("id", key + "__x" + (k + 1))
                    .attr("label", "rule " + (t.row() + 1) + " formula " + (t.term() + 1));
            x.inline("", "inputExpression", ns, "text", "x" + (k + 1), false, "id", key + "__xexpr" + (k + 1),
                    "typeRef", "boolean");
            x.close();
        }
        table.defaultOutput().forEach((name, literal) -> x.leaf("", "output", ns).attr("id", key + "__out_" + name)
                .attr("name", name).attr("typeRef", literal.typeName()));
        for (DecisionTable.Row row : table.evaluationOrder()) {
            List<Condition> terms = row.terms();
            int rules = DmnGenerator.ruleCount(table, row);
            boolean oneRuleAny = row.when().isAny() && rules == 1;
            for (int k = 0; k < rules; k++) {
                String rule = key + "__r" + (row.index() + 1) + (rules > 1 ? "_" + (k + 1) : "");
                x.open("", "rule", ns).attr("id", rule);
                // The terms this DMN rule tests: all of a conjunction, the k-th alternative of an OR row.
                List<Integer> active = new ArrayList<>();
                if (oneRuleAny || !row.when().isAny()) {
                    for (int i = 0; i < terms.size(); i++) active.add(i);
                } else {
                    active.add(k);
                }
                String combined = oneRuleAny ? "#{" + anyExpression(row, terms, fields, tests) + "}" : null;
                for (int i = 0; i < fields.size(); i++) {
                    String field = fields.get(i);
                    String entry = "-";
                    if (oneRuleAny) {
                        // COLLECT: the alternatives are one expression in the column of the first term.
                        Condition first = terms.get(0);
                        if (!first.isExpression() && first.fieldRef().equals(field)) entry = combined;
                    } else {
                        for (int a : active) {
                            Condition t = terms.get(a);
                            if (!t.isExpression() && t.fieldRef().equals(field)) {
                                entry = DmnGenerator.inputEntry(t, "in" + (i + 1));
                                break;
                            }
                        }
                    }
                    x.inline("", "inputEntry", ns, "text", entry, !"-".equals(entry), "id", rule + "_in" + (i + 1));
                }
                if (table.dated()) {
                    var window = row.effective();
                    Integer from = window == null ? null : DecisionTable.Window.number(window.from());
                    Integer to = window == null ? null : DecisionTable.Window.number(window.to());
                    x.inline("", "inputEntry", ns, "text", from == null ? "-" : ">= " + from, from != null, "id", rule + "_inFrom");
                    x.inline("", "inputEntry", ns, "text", to == null ? "-" : "<= " + to, to != null, "id", rule + "_inTo");
                }
                for (int j = 0; j < tests.size(); j++) {
                    Test t = tests.get(j);
                    String entry = "-";
                    if (t.row() == row.index()) {
                        if (oneRuleAny) {
                            if (t.term() == 0) entry = combined;
                        } else if (active.contains(t.term())) {
                            entry = "== true";
                        }
                    }
                    x.inline("", "inputEntry", ns, "text", entry, !"-".equals(entry), "id", rule + "_x" + (j + 1));
                }
                for (String name : table.defaultOutput().keySet()) {
                    String entry;
                    int slot = computed.indexOf(new Computed(row.index(), name));
                    entry = slot >= 0 ? "#{y" + (slot + 1) + "}" : DmnGenerator.outputEntry(row.then().get(name));
                    x.inline("", "outputEntry", ns, "text", entry, true, "id", rule + "_" + name);
                }
                x.close();
            }
        }
        if (!table.collect()) {
            String rule = key + "__default";
            x.open("", "rule", ns).attr("id", rule);
            for (int i = 0; i < fields.size(); i++) x.inline("", "inputEntry", ns, "text", "-", false, "id", rule + "_in" + (i + 1));
            if (table.dated()) {
                x.inline("", "inputEntry", ns, "text", "-", false, "id", rule + "_inFrom");
                x.inline("", "inputEntry", ns, "text", "-", false, "id", rule + "_inTo");
            }
            for (int j = 0; j < tests.size(); j++) x.inline("", "inputEntry", ns, "text", "-", false, "id", rule + "_x" + (j + 1));
            table.defaultOutput().forEach((name, literal) -> x.inline("", "outputEntry", ns, "text",
                    DmnGenerator.outputEntry(literal), true, "id", rule + "_" + name));
            x.close();
        }
        x.close();
        x.close();
        x.close();
        return x.finish();
    }

    /** COLLECT: the alternatives of an OR row as one expression over the {@code in<n>} and {@code x<k>} variables. */
    private static String anyExpression(DecisionTable.Row row, List<Condition> terms, List<String> fields, List<Test> tests) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            Condition t = terms.get(i);
            if (b.length() > 0) b.append(" || ");
            if (t.isExpression()) {
                b.append("(x").append(tests.indexOf(new Test(row.index(), i)) + 1).append(" == true)");
            } else {
                b.append("(").append(DmnGenerator.expression(t, "in" + (fields.indexOf(t.fieldRef()) + 1))).append(")");
            }
        }
        return b.toString();
    }
}
