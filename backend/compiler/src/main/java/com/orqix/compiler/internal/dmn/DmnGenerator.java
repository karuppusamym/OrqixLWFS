package com.orqix.compiler.internal.dmn;

import java.util.List;
import java.util.Map;

import com.orqix.compiler.internal.bpmn.XmlOut;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;

/**
 * Engine DMN generation (S4 section 7), the file deployed to Flowable. Canonical {@code UNIQUE}, {@code FIRST} and
 * {@code ANY} + {@code default} become hit policy {@code FIRST} with the user rows followed by one catch-all row
 * carrying the default (UNIQUE rows are proven disjoint, overlapping ANY rows proven to give the same results).
 * {@code PRIORITY} is the same with the rows sorted by the rank of their priority result, so the first match is the
 * highest priority (docs/build/S121). {@code COLLECT} stays {@code COLLECT} with its aggregation and has no catch-all
 * row: when nothing matches the engine returns no result and the runtime applies the default. The input expression is
 * the variable {@code in1} (one column) or {@code in<n>} per distinct field; outputs are written in key order.
 * <p>
 * Set comparisons ({@code in}, {@code between}) are written as {@code #{...}} entries, which Flowable evaluates as
 * written. The standard, portable form of a table is {@link DmnExport}.
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
            x.inline("", "inputEntry", DMN_NS, "text", inputEntry(row.when(), "in1"), true, "id", rule + "_in");
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

    /**
     * One DMN rule per alternative of an OR row, so a row of k alternatives occupies k consecutive rules. Under
     * COLLECT an OR row is one rule (each match must count once).
     */
    static int ruleCount(DecisionTable table, DecisionTable.Row row) {
        return row.when().isAny() && !table.collect() ? row.when().terms().size() : 1;
    }

    /**
     * Maps each generated DMN rule (1-based position) to the index of the canonical row it came from; the trailing
     * catch-all default rule is not included. Runtime evidence uses it to name the matched row.
     */
    public static java.util.List<Integer> ruleOwners(DecisionTable table) {
        java.util.List<Integer> owners = new java.util.ArrayList<>();
        for (DecisionTable.Row row : table.evaluationOrder()) {
            for (int i = 0; i < ruleCount(table, row); i++) {
                owners.add(row.index());
            }
        }
        return owners;
    }

    public static java.util.List<String> inputFields(DecisionTable table) {
        return table.rows().stream().flatMap(r -> (r.when().compound()?r.when().terms():java.util.List.of(r.when())).stream())
            .map(Condition::fieldRef).distinct().sorted().toList();
    }

    /** @return true for tables the one-column form above cannot hold */
    private static boolean multiColumn(DecisionTable table) {
        return table.rows().stream().anyMatch(r -> r.when().compound()) || table.dated() || table.collect()
                || DecisionTable.PRIORITY.equals(table.hitPolicy());
    }

    /** Flat conjunctions become one DMN input column per distinct canonical field. */
    public static String generate(DecisionTable table, Map<String,FieldKind> kinds) {
        if(!multiColumn(table)) return generate(table,kinds.get(table.rows().get(0).when().fieldRef()));
        var fields=inputFields(table);
        String key=table.key();
        XmlOut x=new XmlOut();
        x.open("","definitions",DMN_NS).defaultNamespace(DMN_NS).attr("id","dmn_"+key+"_v"+table.version()).attr("name",key).attr("namespace",DECISIONS_NAMESPACE);
        x.open("","decision",DMN_NS).attr("id",key).attr("name",key);
        x.open("","decisionTable",DMN_NS).attr("id",key+"__table").attr("hitPolicy",table.collect()?"COLLECT":"FIRST");
        if(table.collect()) x.attr("aggregation",table.aggregation());
        for(int i=0;i<fields.size();i++) {
            x.open("","input",DMN_NS).attr("id",key+"__in"+(i+1)).attr("label",fields.get(i));
            x.inline("","inputExpression",DMN_NS,"text","in"+(i+1),false,"id",key+"__inexpr"+(i+1),"typeRef",typeRef(kinds.get(fields.get(i))));
            x.close();
        }
        if(table.dated()) {
            // The effective window is two ordinary comparisons of the same number, the submission date as yyyymmdd.
            for(String edge:new String[] {"From","To"}) {
                x.open("","input",DMN_NS).attr("id",key+"__in"+edge).attr("label","effective"+edge);
                x.inline("","inputExpression",DMN_NS,"text","asOf",false,"id",key+"__inexpr"+edge,"typeRef","number");
                x.close();
            }
        }
        table.defaultOutput().forEach((name,literal)->x.leaf("","output",DMN_NS).attr("id",key+"__out_"+name).attr("name",name).attr("typeRef",literal.typeName()));
        for(DecisionTable.Row row:table.evaluationOrder()) {
            var terms=row.when().compound()?row.when().terms():java.util.List.of(row.when());
            int rules=ruleCount(table,row);
            boolean oneRuleAny=row.when().isAny() && rules==1;
            for(int k=0;k<rules;k++) {
                String rule=key+"__r"+(row.index()+1)+(rules>1?"_"+(k+1):"");
                x.open("","rule",DMN_NS).attr("id",rule);
                for(int i=0;i<fields.size();i++) {
                    String field=fields.get(i);
                    String entry;
                    if(oneRuleAny) {
                        // COLLECT: the alternatives become one expression in the column of the first term's field.
                        entry=terms.get(0).fieldRef().equals(field)?"#{"+anyExpression(terms,fields)+"}":"-";
                    } else if(row.when().isAny()) {
                        // Each alternative of an OR row fills only its own field.
                        entry=terms.get(k).fieldRef().equals(field)?inputEntry(terms.get(k),"in"+(i+1)):"-";
                    } else {
                        // A conjunction fills one entry per field.
                        final String variable="in"+(i+1);
                        entry=terms.stream().filter(t->t.fieldRef().equals(field)).findFirst().map(t->inputEntry(t,variable)).orElse("-");
                    }
                    x.inline("","inputEntry",DMN_NS,"text",entry,true,"id",rule+"_in"+(i+1));
                }
                if(table.dated()) {
                    var window=row.effective();
                    Integer from=window==null?null:DecisionTable.Window.number(window.from());
                    Integer to=window==null?null:DecisionTable.Window.number(window.to());
                    x.inline("","inputEntry",DMN_NS,"text",from==null?"-":">= "+from,from!=null,"id",rule+"_inFrom");
                    x.inline("","inputEntry",DMN_NS,"text",to==null?"-":"<= "+to,to!=null,"id",rule+"_inTo");
                }
                table.defaultOutput().keySet().forEach(name->x.inline("","outputEntry",DMN_NS,"text",outputEntry(row.then().get(name)),true,"id",rule+"_"+name));
                x.close();
            }
        }
        if(!table.collect()) {
            String rule=key+"__default";
            x.open("","rule",DMN_NS).attr("id",rule);
            for(int i=0;i<fields.size();i++) x.inline("","inputEntry",DMN_NS,"text","-",false,"id",rule+"_in"+(i+1));
            if(table.dated()) {
                x.inline("","inputEntry",DMN_NS,"text","-",false,"id",rule+"_inFrom");
                x.inline("","inputEntry",DMN_NS,"text","-",false,"id",rule+"_inTo");
            }
            table.defaultOutput().forEach((name,literal)->x.inline("","outputEntry",DMN_NS,"text",outputEntry(literal),true,"id",rule+"_"+name));
            x.close();
        }
        x.close(); x.close(); x.close();
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

    /**
     * Input entries: {@code >= n}, {@code < n}, {@code == n}, {@code == "s"}, {@code == true|false}; set comparisons
     * as an expression of the column's variable, {@code #{in1 == "EU" || in1 == "UK"}} or
     * {@code #{in1 >= 1000 && in1 < 5000}}.
     */
    static String inputEntry(Condition c, String variable) {
        if (c.operator().set()) {
            return "#{" + expression(c, variable) + "}";
        }
        String operand = c.value() instanceof Literal.Str s ? "\"" + s.string() + "\"" : c.value().text();
        return switch (c.operator()) {
            case GTE -> ">= " + operand;
            case GT -> "> " + operand;
            case LT -> "< " + operand;
            case LTE -> "<= " + operand;
            case EQ -> "== " + operand;
            case NE -> "!= " + operand;
            case IN, BETWEEN -> throw new IllegalStateException("handled above");
        };
    }

    /** One comparison as a boolean expression of {@code variable}. Text literals are safe (no quotes, no braces). */
    static String expression(Condition c, String variable) {
        return switch (c.operator()) {
            case IN -> {
                StringBuilder b = new StringBuilder();
                for (Literal l : c.list()) {
                    if (b.length() > 0) b.append(" || ");
                    b.append(variable).append(" == ").append(outputEntry(l));
                }
                yield c.list().size() == 1 ? b.toString() : "(" + b + ")";
            }
            case BETWEEN -> "(" + variable + (c.range().includeFrom() ? " >= " : " > ") + c.range().from().text()
                    + " && " + variable + (c.range().includeTo() ? " <= " : " < ") + c.range().to().text() + ")";
            default -> variable + " " + inputEntry(c, variable);
        };
    }

    /** The alternatives of an OR row as one expression; each term reads the variable of its own field's column. */
    private static String anyExpression(List<Condition> terms, List<String> fields) {
        StringBuilder b = new StringBuilder();
        for (Condition t : terms) {
            if (b.length() > 0) b.append(" || ");
            b.append("(").append(expression(t, "in" + (fields.indexOf(t.fieldRef()) + 1))).append(")");
        }
        return b.toString();
    }

    /** Output entries: {@code "s"}, plain numbers, {@code true|false}. */
    static String outputEntry(Literal literal) {
        return literal instanceof Literal.Str s ? "\"" + s.string() + "\"" : literal.text();
    }

    /** Kept for callers that test one column ({@code in1}). */
    static String inputEntry(Condition c) {
        return inputEntry(c, "in1");
    }
}
