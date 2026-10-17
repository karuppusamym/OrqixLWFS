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

    /** One DMN rule per alternative of an OR row, so a row of k alternatives occupies k consecutive rules. */
    static int ruleCount(DecisionTable.Row row) {
        return row.when().isAny() ? row.when().terms().size() : 1;
    }

    /**
     * Maps each generated DMN rule (1-based position) to the index of the canonical row it came from; the trailing
     * catch-all default rule is not included. Runtime evidence uses it to name the matched row.
     */
    public static java.util.List<Integer> ruleOwners(DecisionTable table) {
        java.util.List<Integer> owners = new java.util.ArrayList<>();
        for (DecisionTable.Row row : table.rows()) {
            for (int i = 0; i < ruleCount(row); i++) {
                owners.add(row.index());
            }
        }
        return owners;
    }

    public static java.util.List<String> inputFields(DecisionTable table) {
        return table.rows().stream().flatMap(r -> (r.when().compound()?r.when().terms():java.util.List.of(r.when())).stream())
            .map(Condition::fieldRef).distinct().sorted().toList();
    }

    /** Flat conjunctions become one DMN input column per distinct canonical field. */
    public static String generate(DecisionTable table, Map<String,FieldKind> kinds) {
        if(table.rows().stream().noneMatch(r->r.when().compound())) return generate(table,kinds.get(table.rows().get(0).when().fieldRef()));
        var fields=inputFields(table);
        String key=table.key();
        XmlOut x=new XmlOut();
        x.open("","definitions",DMN_NS).defaultNamespace(DMN_NS).attr("id","dmn_"+key+"_v"+table.version()).attr("name",key).attr("namespace",DECISIONS_NAMESPACE);
        x.open("","decision",DMN_NS).attr("id",key).attr("name",key);
        x.open("","decisionTable",DMN_NS).attr("id",key+"__table").attr("hitPolicy","FIRST");
        for(int i=0;i<fields.size();i++) {
            x.open("","input",DMN_NS).attr("id",key+"__in"+(i+1)).attr("label",fields.get(i));
            x.inline("","inputExpression",DMN_NS,"text","in"+(i+1),false,"id",key+"__inexpr"+(i+1),"typeRef",typeRef(kinds.get(fields.get(i))));
            x.close();
        }
        table.defaultOutput().forEach((name,literal)->x.leaf("","output",DMN_NS).attr("id",key+"__out_"+name).attr("name",name).attr("typeRef",literal.typeName()));
        for(DecisionTable.Row row:table.rows()) {
            var terms=row.when().compound()?row.when().terms():java.util.List.of(row.when());
            int rules=ruleCount(row);
            for(int k=0;k<rules;k++) {
                String rule=key+"__r"+(row.index()+1)+(rules>1?"_"+(k+1):"");
                x.open("","rule",DMN_NS).attr("id",rule);
                for(int i=0;i<fields.size();i++) {
                    String field=fields.get(i);
                    // A conjunction fills one entry per field; each alternative of an OR row fills only its own field.
                    String entry=row.when().isAny()
                        ? (terms.get(k).fieldRef().equals(field)?inputEntry(terms.get(k)):"-")
                        : terms.stream().filter(t->t.fieldRef().equals(field)).findFirst().map(DmnGenerator::inputEntry).orElse("-");
                    x.inline("","inputEntry",DMN_NS,"text",entry,true,"id",rule+"_in"+(i+1));
                }
                table.defaultOutput().keySet().forEach(name->x.inline("","outputEntry",DMN_NS,"text",outputEntry(row.then().get(name)),true,"id",rule+"_"+name));
                x.close();
            }
        }
        String rule=key+"__default";
        x.open("","rule",DMN_NS).attr("id",rule);
        for(int i=0;i<fields.size();i++) x.inline("","inputEntry",DMN_NS,"text","-",false,"id",rule+"_in"+(i+1));
        table.defaultOutput().forEach((name,literal)->x.inline("","outputEntry",DMN_NS,"text",outputEntry(literal),true,"id",rule+"_"+name));
        x.close(); x.close(); x.close(); x.close();
        return x.finish();
    }

    static String typeRef(FieldKind kind) {
        return switch (kind) {
            case STRING -> "string";
            case NUMBER, INTEGER -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY, OBJECT, DATE, ATTACHMENT -> throw new IllegalArgumentException("Collection, object, date and file conditions are not supported");
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
