package com.orqix.compiler.internal.dmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.internal.bpmn.XmlOut;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;

/**
 * Standard DMN 1.3 form of a decision table for download (docs/build/S121), as opposed to {@link DmnGenerator}'s engine
 * file. It keeps the authored hit policy (with {@code aggregation} for COLLECT and {@code outputValues} on the priority
 * output for PRIORITY), writes FEEL unary tests ({@code >= 5000}, {@code "EU","UK"}, {@code [1000..5000)},
 * {@code not("x")}) and gives the default as each output's {@code defaultOutputEntry}, so other DMN tools read the
 * same meaning. Column types come from the literals the rows compare with.
 * <p>
 * Two shapes need a rewrite that keeps the meaning: an OR row whose alternatives test one field is one rule with a
 * list of tests; one whose alternatives test several fields becomes one rule per field. Under UNIQUE those rules of one
 * row may overlap, so such a table is written with hit policy ANY (the same results by construction). Under COLLECT
 * each match must count once, so the rules are made exclusive with {@code not(...)} of the earlier alternatives.
 * Effective windows become a date column on {@code submissionDate}.
 */
public final class DmnExport {

    /** Input expression of the effective-window column. */
    public static final String SUBMISSION_DATE = "submissionDate";

    private DmnExport() {
    }

    /** FEEL name of a field reference: {@code /input/amount} is {@code amount}, other paths join with dots. */
    public static String feelName(String fieldRef) {
        String[] parts = fieldRef.substring(1).split("/");
        if (parts.length == 2 && "input".equals(parts[0])) {
            return parts[1];
        }
        return String.join(".", parts);
    }

    public static String export(DecisionTable table, String name) {
        String key = table.key();
        List<String> fields = DmnGenerator.inputFields(table);
        boolean multiFieldAny = table.rows().stream().anyMatch(r -> r.when().isAny()
                && r.when().terms().stream().map(Condition::fieldRef).distinct().count() > 1);
        String policy = DecisionTable.UNIQUE.equals(table.hitPolicy()) && multiFieldAny ? DecisionTable.ANY : table.hitPolicy();

        XmlOut x = new XmlOut();
        String ns = DmnGenerator.DMN_NS;
        x.open("", "definitions", ns).defaultNamespace(ns)
                .attr("id", "definitions_" + key)
                .attr("name", name == null || name.isBlank() ? key : name)
                .attr("namespace", DmnGenerator.DECISIONS_NAMESPACE)
                .attr("exporter", "Orqix")
                .attr("exporterVersion", "S121");
        x.open("", "decision", ns).attr("id", key).attr("name", name == null || name.isBlank() ? key : name);
        if (!policy.equals(table.hitPolicy())) {
            x.inline("", "description", ns, null, "Exported with hit policy ANY: an Any comparison row of this UNIQUE "
                    + "table tests several fields, so it is written as one rule per field with the same results.", false);
        }
        x.open("", "decisionTable", ns).attr("id", key + "_table").attr("hitPolicy", policy);
        if (table.collect()) {
            x.attr("aggregation", table.aggregation());
        }
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            x.open("", "input", ns).attr("id", key + "_in" + (i + 1)).attr("label", feelName(field));
            x.inline("", "inputExpression", ns, "text", feelName(field), false, "id", key + "_inexpr" + (i + 1),
                    "typeRef", typeOf(table, field));
            x.close();
        }
        if (table.dated()) {
            x.open("", "input", ns).attr("id", key + "_inDate").attr("label", "Submitted on");
            x.inline("", "inputExpression", ns, "text", SUBMISSION_DATE, false, "id", key + "_inexprDate",
                    "typeRef", "date");
            x.close();
        }
        table.defaultOutput().forEach((output, literal) -> {
            x.open("", "output", ns).attr("id", key + "_out_" + output).attr("name", output)
                    .attr("typeRef", literal.typeName());
            if (table.priority() != null && output.equals(table.priority().output())) {
                List<String> values = table.priority().order().stream().map(DmnExport::literal).toList();
                x.inline("", "outputValues", ns, "text", String.join(",", values), false);
            }
            x.inline("", "defaultOutputEntry", ns, "text", literal(literal), false);
            x.close();
        });
        for (DecisionTable.Row row : table.rows()) {
            List<Map<String, String>> rules = rules(table, row);
            for (int k = 0; k < rules.size(); k++) {
                String rule = key + "_r" + (row.index() + 1) + (rules.size() > 1 ? "_" + (k + 1) : "");
                x.open("", "rule", ns).attr("id", rule);
                for (int i = 0; i < fields.size(); i++) {
                    x.inline("", "inputEntry", ns, "text", rules.get(k).getOrDefault(fields.get(i), "-"), false,
                            "id", rule + "_in" + (i + 1));
                }
                if (table.dated()) {
                    x.inline("", "inputEntry", ns, "text", window(row.effective()), false, "id", rule + "_inDate");
                }
                for (String output : table.defaultOutput().keySet()) {
                    x.inline("", "outputEntry", ns, "text", literal(row.then().get(output)), false,
                            "id", rule + "_" + output);
                }
                x.close();
            }
        }
        x.close();
        x.close();
        x.close();
        return x.finish();
    }

    /** The cells (field to unary tests) of the rule or rules one row becomes. */
    private static List<Map<String, String>> rules(DecisionTable table, DecisionTable.Row row) {
        Condition when = row.when();
        if (!when.isAny()) {
            Map<String, String> cells = new LinkedHashMap<>();
            for (Condition t : when.compound() ? when.terms() : List.of(when)) {
                cells.put(t.fieldRef(), test(t));
            }
            return List.of(cells);
        }
        // Group the alternatives by field, in order of first appearance; one field's tests join as a FEEL list.
        Map<String, List<String>> byField = new LinkedHashMap<>();
        for (Condition t : when.terms()) {
            byField.computeIfAbsent(t.fieldRef(), f -> new ArrayList<>()).add(test(t));
        }
        List<String> order = new ArrayList<>(byField.keySet());
        List<Map<String, String>> rules = new ArrayList<>();
        for (int k = 0; k < order.size(); k++) {
            Map<String, String> cells = new LinkedHashMap<>();
            if (table.collect()) {
                for (int j = 0; j < k; j++) {
                    cells.put(order.get(j), "not(" + String.join(", ", byField.get(order.get(j))) + ")");
                }
            }
            cells.put(order.get(k), String.join(", ", byField.get(order.get(k))));
            rules.add(cells);
        }
        return rules;
    }

    /** One comparison as a FEEL unary test. */
    static String test(Condition c) {
        return switch (c.operator()) {
            case EQ -> literal(c.value());
            case NE -> "not(" + literal(c.value()) + ")";
            case GTE -> ">= " + c.value().text();
            case GT -> "> " + c.value().text();
            case LT -> "< " + c.value().text();
            case LTE -> "<= " + c.value().text();
            case IN -> String.join(",", c.list().stream().map(DmnExport::literal).toList());
            case BETWEEN -> (c.range().includeFrom() ? "[" : "(") + c.range().from().text() + ".."
                    + c.range().to().text() + (c.range().includeTo() ? "]" : ")");
        };
    }

    private static String window(DecisionTable.Window w) {
        if (w == null) {
            return "-";
        }
        if (w.from() != null && w.to() != null) {
            return "[date(\"" + w.from() + "\")..date(\"" + w.to() + "\")]";
        }
        return w.from() != null ? ">= date(\"" + w.from() + "\")" : "<= date(\"" + w.to() + "\")";
    }

    static String literal(Literal literal) {
        return literal instanceof Literal.Str s ? "\"" + s.string() + "\"" : literal.text();
    }

    /** {@code number}, {@code string} or {@code boolean}, from the literals the rows compare the field with. */
    private static String typeOf(DecisionTable table, String field) {
        for (DecisionTable.Row row : table.rows()) {
            for (Condition t : row.when().compound() ? row.when().terms() : List.of(row.when())) {
                if (t.fieldRef().equals(field) && !t.literals().isEmpty()) {
                    return t.literals().get(0).typeName();
                }
            }
        }
        return "string";
    }
}
