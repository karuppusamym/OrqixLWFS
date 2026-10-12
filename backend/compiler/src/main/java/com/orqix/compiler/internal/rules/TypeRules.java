package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema;
import com.orqix.compiler.internal.model.WorkflowDefinition.OutputEntry;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;
import com.orqix.compiler.internal.rules.FieldTypes.Resolution;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

/** Types, decisions, branches, forms, output mapping, layout and labels (S4 sections 4, 5.2 and 5.4). */
final class TypeRules {

    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^(step|task|node|activity|untitled|new step|new task|label|name|todo|tbd)( ?\\d+)?$");

    private TypeRules() {
    }

    static void check(RuleContext rc) {
        decisions(rc);
        branches(rc);
        outputMapping(rc);
        form(rc, rc.definition().inputSchema(), "inputSchema", "request form");
        form(rc, rc.definition().outputSchema(), "outputSchema", "result form");
        inputUi(rc);
        layout(rc);
        labels(rc);
    }

    // ---------------------------------------------------------------- decisions

    private static void decisions(RuleContext rc) {
        WorkflowDefinition d = rc.definition();
        Set<String> used = new HashSet<>();
        for (Node n : d.nodes()) {
            if (n instanceof Node.Decision dn) {
                used.add(dn.decisionRef());
                if (rc.types().table(dn.decisionRef()) == null) {
                    rc.issues().blocker("WF_DECISION_UNKNOWN", n.configPath("decisionRef"), n.id(), null, n.label()
                            + " uses the rule table " + q(dn.decisionRef()) + ", which does not exist.");
                }
            }
        }
        Set<String> keys = new HashSet<>();
        for (DecisionTable table : d.decisions()) {
            if (!keys.add(table.key())) {
                rc.issues().blocker("WF_DECISION_DUPLICATE_KEY", table.path() + "/key", null, null,
                        "Another rule table already uses the key " + q(table.key()) + ".");
                continue;
            }
            if (!used.contains(table.key())) {
                rc.issues().warning("WF_DECISION_UNUSED", table.path(), null, null, "The rule table "
                        + q(table.key()) + " is not used by any decision step.");
            }
            table(rc, table);
        }
    }

    private static void table(RuleContext rc, DecisionTable table) {
        String name = q(table.key());
        // Output literals and shape.
        Map<String, Literal> reference = table.defaultOutput();
        reference.forEach((k, v) -> Conditions.literalSafety(rc, v, table.path() + "/default/" + k, null, null));
        for (DecisionTable.Row row : table.rows()) {
            String rowPath = table.path() + "/rows/" + row.index();
            row.then().forEach((k, v) -> Conditions.literalSafety(rc, v, rowPath + "/then/" + k, null, null));
            if (!row.then().keySet().equals(reference.keySet())) {
                rc.issues().blocker("WF_DECISION_OUTPUT_SHAPE", rowPath + "/then", null, null, "Rule "
                        + (row.index() + 1) + " of " + name + " must set exactly the same results as the default: "
                        + String.join(", ", reference.keySet()) + ".");
                continue;
            }
            for (Map.Entry<String, Literal> e : row.then().entrySet()) {
                Literal def = reference.get(e.getKey());
                if (!def.typeName().equals(e.getValue().typeName())) {
                    rc.issues().blocker("WF_DECISION_OUTPUT_SHAPE", rowPath + "/then/" + e.getKey(), null, null,
                            "Rule " + (row.index() + 1) + " of " + name + " sets " + q(e.getKey()) + " to "
                                    + words(e.getValue()) + ", but the default sets it to " + words(def) + ".");
                }
            }
        }
        boolean scalar=table.rows().stream().noneMatch(r->r.when().compound());
        String field=scalar?table.rows().get(0).when().fieldRef():null;
        Map<DecisionTable.Row,List<Disjointness.Typed>> typed=new LinkedHashMap<>();
        List<Node> users=rc.definition().nodes().stream().filter(n->n instanceof Node.Decision dn && dn.decisionRef().equals(table.key()) && !rc.graph().isShadowed(n)).toList();
        for (DecisionTable.Row row:table.rows()) {
            String base=table.path()+"/rows/"+row.index()+"/when";
            if(scalar && !row.when().fieldRef().equals(field)) {
                rc.issues().blocker("WF_DECISION_MIXED_FIELDS",base+"/fieldRef",null,null,"Scalar rows must test the same field; use an All condition for multiple inputs.");
                continue;
            }
            List<Condition> terms=row.when().compound()?row.when().terms():List.of(row.when());
            if(terms.stream().map(Condition::fieldRef).distinct().count()!=terms.size()) {
                rc.issues().blocker("WF_DECISION_DUPLICATE_INPUT",base,null,null,"Each field may appear once in a conjunctive decision row.");
                continue;
            }
            var checked=LogicalConditions.check(rc,row.when(),base,null,null);
            if(checked!=null) typed.put(row,checked);
            for(Node user:users) for(Condition term:terms) Conditions.availability(rc,term.fieldRef(),user,base+"/fieldRef",null);
        }
        if(typed.size()!=table.rows().size()) return;
        for(int i=0;i<table.rows().size();i++) for(int j=i+1;j<table.rows().size();j++) {
            var x=table.rows().get(i); var y=table.rows().get(j);
            if(LogicalConditions.overlap(x.when(),typed.get(x),y.when(),typed.get(y)))
                rc.issues().blocker("WF_DECISION_OVERLAP",table.path()+"/rows/"+j+"/when",null,null,"Rules "+(i+1)+" and "+(j+1)+" can both apply. Make their conditions exclusive.");
        }
        if(scalar) {
            var atoms=table.rows().stream().map(r->typed.get(r).get(0)).toList();
            if(Disjointness.covers(atoms,atoms.get(0).type())) rc.issues().warning("WF_DECISION_DEFAULT_UNREACHABLE",table.path()+"/default",null,null,"The rules cover every possible value, so the default is never used.");
        }
    }

    private static String words(Literal literal) {
        return switch (literal) {
            case Literal.Str s -> "text";
            case Literal.Num n -> "a number";
            case Literal.Bool b -> "true or false";
        };
    }

    // ---------------------------------------------------------------- branches

    private static void branches(RuleContext rc) {
        Map<Node, List<Transition>> byBranch = new LinkedHashMap<>();
        Map<Transition, List<Disjointness.Typed>> typed = new LinkedHashMap<>();
        for (Transition t : rc.definition().transitions()) {
            if (t.condition() == null) continue;
            Node from = rc.graph().node(t.from());
            var checked = LogicalConditions.check(rc, t.condition(), t.path() + "/condition", from == null ? null : from.id(), t.id());
            if (checked != null) typed.put(t, checked);
            if (from != null && from.kind() == NodeKind.BRANCH && t.on() == Route.CONDITION)
                byBranch.computeIfAbsent(from, key -> new ArrayList<>()).add(t);
        }
        for (var e : byBranch.entrySet()) {
            Node branch = e.getKey(); List<Transition> routes = e.getValue();
            boolean scalar = routes.stream().noneMatch(t -> t.condition().compound());
            String field = scalar ? routes.get(0).condition().fieldRef() : null;
            boolean mixed = false;
            if (scalar) for (Transition t : routes) {
                if (!t.condition().fieldRef().equals(field)) {
                    mixed = true;
                    rc.issues().blocker("WF_BRANCH_MIXED_FIELDS", t.path() + "/condition/fieldRef", branch.id(), t.id(),
                            "Every condition of " + q(branch) + " must test the same field (" + field + ").");
                }
            }
            if (mixed || !routes.stream().allMatch(typed::containsKey)) continue;
            for (int i = 0; i < routes.size(); i++) for (int j = i + 1; j < routes.size(); j++) {
                Transition x = routes.get(i), y = routes.get(j);
                boolean overlap = scalar ? Disjointness.overlap(typed.get(x).get(0), typed.get(y).get(0))
                        : LogicalConditions.overlap(x.condition(), typed.get(x), y.condition(), typed.get(y));
                if (overlap) rc.issues().blocker("WF_BRANCH_OVERLAP", y.path() + "/condition", branch.id(), y.id(),
                        "The conditions of paths " + pathName(x) + " and " + pathName(y) + " of " + q(branch)
                                + " can both be true. Make the conditions exclusive.");
            }
        }
    }

    private static String pathName(Transition t) {
        return t.label() == null ? q(t.id()) : q(t.label());
    }

    // ---------------------------------------------------------------- output mapping

    private static void outputMapping(RuleContext rc) {
        WorkflowDefinition d = rc.definition();
        for (OutputEntry entry : d.outputMapping().values()) {
            String path = JsonPointers.of("outputMapping", entry.key());
            FormSchema.Field target = d.outputSchema().properties().get(entry.key());
            if (target == null) {
                rc.issues().blocker("WF_OUTPUT_MAPPING_UNKNOWN", path, null, null, "The result field "
                        + q(entry.key()) + " is not defined in the result form (outputSchema).");
            }
            Resolution res = rc.types().resolve(entry.fieldRef());
            if (res instanceof Unknown u) {
                rc.issues().blocker("WF_FIELDREF_UNKNOWN", path + "/fieldRef", null, null, u.message());
                continue;
            }
            if (!(res instanceof Resolved r)) {
                continue;
            }
            if (target != null) {
                Set<String> targetEnum = target.enumValues() == null ? null : new TreeSet<>(target.enumValues());
                String problem = FieldTypes.incompatibility(r.type(), target);
                if (problem != null) {
                    rc.issues().blocker("WF_OUTPUT_MAPPING_UNKNOWN", path + "/fieldRef", null, null,
                            "The result field " + q(entry.key()) + " " + problem + ".");
                }
            }
            if (entry.required() && r.producer() != null) {
                requiredOutput(rc, entry, r, path + "/fieldRef");
            }
        }
        List<String> required = d.outputSchema().required();
        for (int i = 0; i < required.size(); i++) {
            String key = required.get(i);
            OutputEntry entry = d.outputMapping().get(key);
            if (d.outputSchema().properties().containsKey(key) && (entry == null || !entry.required())) {
                rc.issues().blocker("WF_OUTPUT_MAPPING_MISSING", "/outputSchema/required/" + i, null, null,
                        "The result field " + q(key) + " is required, so it needs a mapping marked as required.");
            }
        }
    }

    private static void requiredOutput(RuleContext rc, OutputEntry entry, Resolved r, String path) {
        Node producer = r.producer();
        Graph g = rc.graph();
        if (g.hasDominators()) {
            for (Node end : g.ends()) {
                if (g.isReachable(end) && !g.dominates(producer, end)) {
                    rc.issues().blocker("WF_OUTPUT_NOT_GUARANTEED", path, producer.id(), null, q(producer)
                            + " does not happen on every path to a Finish step (for example " + q(end) + "), so"
                            + " the result field " + q(entry.key()) + " cannot be required.");
                    return;
                }
            }
        }
        if (FailureRules.hasFailedRoute(g, producer)) {
            boolean failureFact = FailureRules.isFailureOutput(r.key());
            for (Node end : g.ends()) {
                if (g.isReachable(end) && FailureRules.reachableWithout(rc, producer,
                        failureFact ? com.orqix.compiler.internal.model.Enums.Route.FAILED
                                : com.orqix.compiler.internal.model.Enums.Route.NEXT, end)) {
                    rc.issues().blocker("WF_OUTPUT_NOT_GUARANTEED", path, producer.id(), null, "The " + r.key()
                            + " of " + q(producer) + " exists only when the step " + (failureFact ? "has failed"
                            : "works") + ", but " + q(end) + " can also be reached otherwise, so the result field "
                            + q(entry.key()) + " cannot be required.");
                    return;
                }
            }
        }
        if (producer instanceof Node.WaitForMessage m && m.timeoutAfter() != null) {
            for (Node end : g.ends()) {
                if (g.isReachable(end) && MessageRules.reachableWithoutMessage(rc, m, end)) {
                    rc.issues().blocker("WF_OUTPUT_NOT_GUARANTEED", path, producer.id(), null, "The " + r.key()
                            + " of " + q(producer) + " exists only when the message arrives in time, but " + q(end)
                            + " can also be reached when it does not, so the result field " + q(entry.key())
                            + " cannot be required.");
                    return;
                }
            }
        }
        if (r.type().mayBeAbsent()) {
            rc.issues().blocker("WF_OUTPUT_NOT_GUARANTEED", path, producer.id(), null, "The " + r.key() + " of "
                    + q(producer) + " " + Conditions.absentWhy(producer) + ", so the result field "
                    + q(entry.key()) + " cannot be required.");
        }
    }

    // ---------------------------------------------------------------- forms and presentation

    private static void form(RuleContext rc, FormSchema form, String member, String formName) {
        for (int i = 0; i < form.required().size(); i++) {
            String key = form.required().get(i);
            if (!form.properties().containsKey(key)) {
                rc.issues().blocker("WF_FORM_INVALID", "/" + member + "/required/" + i, null, null, "The "
                        + formName + " requires " + q(key) + ", but has no such field.");
            }
        }
        for (FormSchema.Field f : form.properties().values()) {
            if (f.type() == FieldKind.ARRAY && f.minItems() > f.maxItems()) {
                invalid(rc, "/" + member + "/properties/" + f.key() + "/minItems", "The minimum collection size exceeds its maximum.");
            }
            if (f.type() == FieldKind.ARRAY) {
                FormSchema.Field item = f.items();
                String itemBase = "/" + member + "/properties/" + f.key() + "/items";
                if (item.type()==FieldKind.OBJECT) {
                    for(String required:item.rowRequired())if(!item.rowFields().containsKey(required))invalid(rc,itemBase+"/required","A required row column does not exist.");
                    for(var column:item.rowFields().values()) {
                        String p=itemBase+"/properties/"+column.key();
                        if(column.type()==FieldKind.ATTACHMENT || column.incidentEditable())invalid(rc,p,"Rows cannot contain files or incident-editable columns.");
                        if(column.minLength()!=null && column.maxLength()!=null && column.minLength()>column.maxLength())invalid(rc,p,"The column's minimum text length exceeds its maximum.");
                        if(column.minimum()!=null && column.maximum()!=null && column.minimum().compareTo(column.maximum())>0)invalid(rc,p,"The column's minimum exceeds its maximum.");
                        if(column.type()!=FieldKind.STRING && (column.minLength()!=null || column.maxLength()!=null || column.enumValues()!=null))invalid(rc,p,"Text constraints require a text column.");
                        if(!column.type().numeric() && (column.minimum()!=null || column.maximum()!=null || column.multipleOf()!=null))invalid(rc,p,"Numeric constraints require a numeric column.");
                        if(column.type()==FieldKind.INTEGER && column.multipleOf()!=null && column.multipleOf().compareTo(BigDecimal.ONE)!=0)invalid(rc,p,"Whole-number columns cannot allow cents.");
                        if(column.type()==FieldKind.DATE)dateBounds(rc,column,p);
                    }
                }
                if (item.minLength() != null && item.maxLength() != null && item.minLength() > item.maxLength())
                    invalid(rc, itemBase + "/minLength", "The item's minimum text length exceeds its maximum.");
                if (item.minimum() != null && item.maximum() != null && item.minimum().compareTo(item.maximum()) > 0)
                    invalid(rc, itemBase + "/minimum", "The item's minimum value exceeds its maximum.");
                if (item.type() != FieldKind.STRING && (item.minLength() != null || item.maxLength() != null || item.enumValues() != null))
                    invalid(rc, itemBase, "Text constraints require a text item type.");
                if (!item.type().numeric() && (item.minimum() != null || item.maximum() != null || item.multipleOf() != null))
                    invalid(rc, itemBase, "Numeric constraints require a numeric item type.");
                if (item.type() == FieldKind.INTEGER && item.multipleOf() != null && item.multipleOf().compareTo(BigDecimal.ONE) != 0)
                    invalid(rc, itemBase + "/multipleOf", "Whole-number items cannot allow cents.");
            }
            String base = JsonPointers.of(member, "properties", f.key());
            if (f.incidentEditable() && (!"inputSchema".equals(member) || f.type() == FieldKind.ARRAY || f.type() == FieldKind.ATTACHMENT))
                invalid(rc,base+"/incidentEditable","Only scalar request fields can be corrected during an incident.");
            if (f.items() != null && f.items().incidentEditable()) invalid(rc,base+"/items/incidentEditable",
                    "Collection items cannot be corrected during an incident.");
            String title = q(f.title());
            String is = title + " is " + f.type().description() + ", so it cannot ";
            if (f.type() == FieldKind.ATTACHMENT && !"inputSchema".equals(member)) {
                invalid(rc, base + "/format", title + " is a file, and files can only be asked for on the request"
                        + " form, not in the " + formName + ".");
            }
            if (f.type() == FieldKind.ARRAY && f.items() != null && f.items().type() == FieldKind.ATTACHMENT) {
                invalid(rc, base + "/items/format", title + " is a list, and a list cannot hold files. Add one"
                        + " file field for each file, or attach more files from the request page.");
            }
            if (f.type() == FieldKind.ATTACHMENT && (f.formatMinimum() != null || f.formatMaximum() != null)) {
                invalid(rc, base + (f.formatMinimum() != null ? "/formatMinimum" : "/formatMaximum"), is
                        + "have earliest or latest dates.");
            }
            if (f.enumValues() != null && f.type() != FieldKind.STRING) {
                invalid(rc, base + "/enum", is + "have a list of choices.");
            }
            if (f.type() != FieldKind.STRING) {
                if (f.minLength() != null) {
                    invalid(rc, base + "/minLength", is + "have a text length limit.");
                }
                if (f.maxLength() != null) {
                    invalid(rc, base + "/maxLength", is + "have a text length limit.");
                }
            }
            if (!f.type().numeric()) {
                if (f.minimum() != null) {
                    invalid(rc, base + "/minimum", is + "have a minimum.");
                }
                if (f.maximum() != null) {
                    invalid(rc, base + "/maximum", is + "have a maximum.");
                }
                if (f.multipleOf() != null) {
                    invalid(rc, base + "/multipleOf", is + "have a precision (multipleOf).");
                }
            } else if (f.type() == FieldKind.INTEGER && f.multipleOf() != null
                    && f.multipleOf().compareTo(BigDecimal.ONE) != 0) {
                invalid(rc, base + "/multipleOf", title + " is a whole number, so it cannot allow cents"
                        + " (multipleOf " + f.multipleOf().toPlainString() + ").");
            }
            dateBounds(rc, f, base);
            if (f.minLength() != null && f.maxLength() != null && f.minLength() > f.maxLength()) {
                invalid(rc, base + "/minLength", title + " has a minimum length greater than its maximum length.");
            }
            if (f.minimum() != null && f.maximum() != null && f.minimum().compareTo(f.maximum()) > 0) {
                invalid(rc, base + "/minimum", title + " has a minimum greater than its maximum.");
            }
        }
    }

    /** formatMinimum / formatMaximum of a date field: real calendar dates, minimum not after maximum. */
    private static void dateBounds(RuleContext rc, FormSchema.Field f, String base) {
        LocalDate min = date(rc, f, base + "/formatMinimum", f.formatMinimum(), "earliest");
        LocalDate max = date(rc, f, base + "/formatMaximum", f.formatMaximum(), "latest");
        if (min != null && max != null && min.isAfter(max)) {
            invalid(rc, base + "/formatMinimum", q(f.title()) + " has an earliest date after its latest date.");
        }
    }

    private static LocalDate date(RuleContext rc, FormSchema.Field f, String path, String text, String which) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            invalid(rc, path, q(f.title()) + " has an " + which + " date that does not exist (" + text + ").");
            return null;
        }
    }

    private static void invalid(RuleContext rc, String path, String message) {
        rc.issues().blocker("WF_FORM_INVALID", path, null, null, message);
    }

    private static void inputUi(RuleContext rc) {
        FormGuards.validateUi(rc);
        WorkflowDefinition.InputUi ui = rc.definition().inputUi();
        if (ui == null) {
            return;
        }
        Map<String, FormSchema.Field> fields = rc.definition().inputSchema().properties();
        for (int i = 0; i < ui.order().size(); i++) {
            String key = ui.order().get(i);
            if (!fields.containsKey(key)) {
                rc.issues().warning("WF_UI_UNKNOWN_FIELD", "/inputUi/order/" + i, null, null, "The form layout"
                        + " mentions " + q(key) + ", which is not a field of the request form.");
            }
        }
        for (Map.Entry<String, String> e : ui.widgets().entrySet()) {
            String path = JsonPointers.of("inputUi", "widgets", e.getKey());
            FormSchema.Field f = fields.get(e.getKey());
            if (f == null) {
                rc.issues().warning("WF_UI_UNKNOWN_FIELD", path, null, null, "The form layout mentions "
                        + q(e.getKey()) + ", which is not a field of the request form.");
                continue;
            }
            String need = switch (e.getValue()) {
                case "text", "textarea" -> f.type() == FieldKind.STRING ? null : "a text field";
                case "number" -> f.type().numeric() ? null : "a number field";
                case "money" -> f.type() == FieldKind.NUMBER && f.multipleOf() != null
                        && f.multipleOf().compareTo(CENT) == 0 ? null : "a number field with cents (multipleOf 0.01)";
                case "select", "radio" -> f.type() == FieldKind.STRING && f.enumValues() != null ? null
                        : "a text field with a list of choices (enum)";
                case "checkbox" -> f.type() == FieldKind.BOOLEAN ? null : "a true/false field";
                default -> null;
            };
            if (need != null) {
                rc.issues().warning("WF_UI_WIDGET_MISMATCH", path, null, null, "The " + e.getValue()
                        + " widget needs " + need + "; " + q(f.title()) + " is not.");
            }
        }
    }

    private static void layout(RuleContext rc) {
        if (rc.definition().uiLayout() == null) {
            return;
        }
        for (String id : rc.definition().uiLayout().positions().keySet()) {
            if (rc.graph().node(id) == null) {
                rc.issues().blocker("WF_LAYOUT_UNKNOWN_NODE", JsonPointers.of("uiLayout", "positions", id), null,
                        null, "The canvas layout has a position for " + q(id) + ", which is not a step.");
            }
        }
    }

    private static void labels(RuleContext rc) {
        WorkflowDefinition d = rc.definition();
        expression(rc, d.name(), "/name", null, null);
        for (Node n : d.nodes()) {
            expression(rc, n.label(), n.path() + "/label", n.id(), null);
            if (n instanceof Node.Notification note) {
                expression(rc, note.message(), n.configPath("message"), n.id(), null);
            }
            String label = n.label().strip();
            if (label.length() < 3) {
                rc.issues().warning("WF_LABEL_STYLE", n.path() + "/label", n.id(), null, q(n)
                        + " is too short to be a clear step name. Say what happens in this step.");
            } else if (PLACEHOLDER.matcher(label).matches()) {
                rc.issues().warning("WF_LABEL_STYLE", n.path() + "/label", n.id(), null, q(n)
                        + " is a placeholder name. Say what happens in this step.");
            }
        }
        for (Transition t : d.transitions()) {
            if (t.label() != null) {
                expression(rc, t.label(), t.path() + "/label", null, t.id());
            }
        }
    }

    private static void expression(RuleContext rc, String text, String path, String nodeId, String transitionId) {
        if (text.contains("${") || text.contains("#{")) {
            rc.issues().blocker("WF_LABEL_EXPRESSION", path, nodeId, transitionId,
                    "Names, labels and messages cannot contain \"${\" or \"#{\".");
        }
    }
}
