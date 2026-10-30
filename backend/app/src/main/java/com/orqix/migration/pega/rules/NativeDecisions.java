package com.orqix.migration.pega.rules;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Decision tables and decision trees as DMN FIRST tables (docs/build/S112 "Decisions"). The table shape is the one of the
 * MIG14 snapshot converter (docs/build/S55): one input column on a case property, plain text results, a final wildcard row
 * for the otherwise result. The generated DMN goes through the ordinary DMN analyzer before any draft uses it.
 */
final class NativeDecisions {
    static final String NS = "http://www.omg.org/spec/DMN/20151101/dmn.xsd";
    static final Pattern LITERAL = Pattern.compile("[A-Za-z][A-Za-z0-9 _-]{0,99}");
    private static final Pattern NUMERIC = Pattern.compile("(>=|<=|!=|=|>|<)?(-?(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,8})?)");
    private static final Pattern DECISION_ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,79}");

    /** One row: an operator and literal on the input, and the plain text result. */
    record Row(String operator, String value, String result, String sourcePath) { }
    record Table(String decisionId, String label, String fieldKey, String fieldType, List<Row> rows, String otherwise) {
        Set<String> results() { Set<String> r = new LinkedHashSet<>(); rows.forEach(x -> r.add(x.result())); r.add(otherwise); return r; }
    }

    private NativeDecisions() { }

    static String decisionId(String ruleName, String path) {
        if (!DECISION_ID.matcher(ruleName).matches()) throw new Page.Grammar(path, "the rule name cannot be a DMN decision id");
        return ruleName;
    }

    static String literal(String value, String path) {
        if (value == null || !LITERAL.matcher(value).matches()) throw new Page.Grammar(path, "expected a plain text literal (letters, digits, spaces, underscores, hyphens)");
        return value;
    }

    /** A condition cell: text columns accept an equal literal; numeric columns an optional operator and a number. */
    static Row row(String condition, String defaultOperator, String fieldType, String result, String path) {
        if (condition == null || condition.length() > 100) throw new Page.Grammar(path, "expected a bounded condition");
        if (fieldType.equals("string")) {
            if (!defaultOperator.equals("=")) throw new Page.Grammar(path, "text conditions support equality only");
            return new Row("=", literal(condition, path), literal(result, path), path);
        }
        var m = NUMERIC.matcher(condition);
        if (!m.matches()) throw new Page.Grammar(path, "only literal numeric comparisons are supported");
        if (fieldType.equals("integer") && m.group(2).contains(".")) throw new Page.Grammar(path, "an integer column needs integer operands");
        return new Row(m.group(1) == null ? defaultOperator : m.group(1), new BigDecimal(m.group(2)).toPlainString(), literal(result, path), path);
    }

    static String dmn(Table t) {
        String typeRef = switch (t.fieldType()) { case "string" -> "string"; case "integer" -> "integer"; default -> "double"; };
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<definitions xmlns=\"").append(NS)
            .append("\" id=\"pegaDefinitions\" name=\"").append(esc(t.label())).append("\" namespace=\"urn:orqix:pega-native\">\n")
            .append("  <decision id=\"").append(t.decisionId()).append("\" name=\"").append(esc(t.label())).append("\">\n")
            .append("    <decisionTable id=\"pegaTable\" hitPolicy=\"FIRST\">\n")
            .append("      <input id=\"pegaInput\" label=\"").append(t.fieldKey()).append("\">\n")
            .append("        <inputExpression id=\"pegaExpression\" typeRef=\"").append(typeRef).append("\"><text>").append(t.fieldKey()).append("</text></inputExpression>\n")
            .append("      </input>\n      <output id=\"pegaOutput\" name=\"result\" typeRef=\"string\"/>\n");
        int i = 0;
        for (Row r : t.rows()) {
            String test = t.fieldType().equals("string") ? quote(r.value())
                : r.operator().equals("!=") ? "not(" + r.value() + ")" : r.operator().equals("=") ? r.value() : r.operator() + " " + r.value();
            rule(x, ++i, test, r.result());
        }
        rule(x, ++i, "-", t.otherwise());
        return x.append("    </decisionTable>\n  </decision>\n</definitions>\n").toString();
    }

    private static void rule(StringBuilder x, int i, String test, String output) {
        x.append("      <rule id=\"pegaRow").append(i).append("\"><inputEntry id=\"input").append(i).append("\"><text>").append(esc(test))
            .append("</text></inputEntry><outputEntry id=\"output").append(i).append("\"><text>").append(esc(quote(output))).append("</text></outputEntry></rule>\n");
    }

    private static String quote(String s) { return "\"" + s + "\""; }
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
