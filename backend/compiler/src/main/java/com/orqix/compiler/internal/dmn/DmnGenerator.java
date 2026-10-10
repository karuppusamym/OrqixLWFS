package com.orqix.compiler.internal.dmn;

import java.util.Map;

import com.orqix.compiler.internal.bpmn.XmlOut;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;

/**
 * DMN generation (S4 section 7): canonical {@code UNIQUE} + {@code default} becomes hit policy {@code FIRST} with the
 * (proven disjoint) user rows followed by one catch-all row carrying the default. The input expression is the variable
 * {@code in1}; outputs are written in key order.
 */
public final class DmnGenerator {

    /** DMN 1.3 model namespace (newest expected to be parsed by Flowable 8; confirmed by spike S-3). */
    public static final String DMN_NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";
    public static final String DECISIONS_NAMESPACE = "https://orqix.example/decisions";

    private DmnGenerator() {
    }

    /** @return resource name of a table's DMN file */
    public static String resourceName(String key) {
        return "decisions/" + key + ".dmn";
    }

    /**
     * @param inputKind type of the field all rows test
     */
    public static String generate(DecisionTable table, FieldKind inputKind) {
        String key = table.key();
        XmlOut x = new XmlOut();
        x.open("", "definitions", DMN_NS)
                .defaultNamespace(DMN_NS)
                .attr("id", "dmn_" + key + "_v" + table.version())
                .attr("name", key)
                .attr("namespace", DECISIONS_NAMESPACE);
        x.open("", "decision", DMN_NS).attr("id", key).attr("name", key);
        x.open("", "decisionTable", DMN_NS).attr("id", key + "__table").attr("hitPolicy", "FIRST");
        x.open("", "input", DMN_NS).attr("id", key + "__in").attr("label", table.rows().get(0).when().fieldRef());
        x.inline("", "inputExpression", DMN_NS, "text", "in1", false,
                "id", key + "__inexpr", "typeRef", typeRef(inputKind));
        x.close();
        Map<String, Literal> outputs = table.defaultOutput();
        outputs.forEach((name, literal) -> x.leaf("", "output", DMN_NS)
                .attr("id", key + "__out_" + name)
                .attr("name", name)
                .attr("typeRef", literal.typeName()));
        for (DecisionTable.Row row : table.rows()) {
            String rule = key + "__r" + (row.index() + 1);
            x.open("", "rule", DMN_NS).attr("id", rule);
            x.inline("", "inputEntry", DMN_NS, "text", inputEntry(row.when()), true, "id", rule + "_in");
            outputs.keySet().forEach(name -> x.inline("", "outputEntry", DMN_NS, "text",
                    outputEntry(row.then().get(name)), true, "id", rule + "_" + name));
            x.close();
        }
        String rule = key + "__default";
        x.open("", "rule", DMN_NS).attr("id", rule);
        x.inline("", "inputEntry", DMN_NS, "text", "-", false, "id", rule + "_in");
        outputs.forEach((name, literal) -> x.inline("", "outputEntry", DMN_NS, "text", outputEntry(literal), true,
                "id", rule + "_" + name));
        x.close();
        x.close();
        x.close();
        x.close();
        return x.finish();
    }

    static String typeRef(FieldKind kind) {
        return switch (kind) {
            case STRING -> "string";
            case NUMBER, INTEGER -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY, DATE -> throw new IllegalArgumentException("Collection and date conditions are not supported");
        };
    }

    /** Input entries: {@code >= n}, {@code < n}, {@code == n}, {@code == "s"}, {@code == true|false}. */
    static String inputEntry(Condition c) {
        String operand = c.value() instanceof Literal.Str s ? "\"" + s.string() + "\"" : c.value().text();
        return switch (c.operator()) {
            case GTE -> ">= " + operand;
            case GT -> "> " + operand;
            case LT -> "< " + operand;
            case LTE -> "<= " + operand;
            case EQ -> "== " + operand;
            case NE -> "!= " + operand;
        };
    }

    /** Output entries: {@code "s"}, plain numbers, {@code true|false}. */
    static String outputEntry(Literal literal) {
        return literal instanceof Literal.Str s ? "\"" + s.string() + "\"" : literal.text();
    }
}
