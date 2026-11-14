package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.LinkedHashMap;
import java.util.Map;

import com.orqix.compiler.FormComputation;
import com.orqix.compiler.expr.ExprType;
import com.orqix.compiler.expr.Expression;
import com.orqix.compiler.expr.ExpressionException;
import com.orqix.compiler.expr.Scope;
import com.orqix.compiler.expr.Values;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;
import com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema;
import com.orqix.compiler.internal.model.WorkflowDefinition.InputUi;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;
import com.orqix.compiler.internal.rules.FieldTypes.FieldType;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;

/**
 * Orqix expressions in workflows (CG-X01, CG-F04, docs/build/S137): Calculate steps, expression conditions on paths
 * and form visibility, {@code =} mappings of calls, computed form fields and cross-field rules. Every expression is
 * parsed and type checked against what is known at the point it runs; step results must come from a step that
 * always happens before. Missing values are allowed: the language handles null.
 */
public final class ExpressionRules {

    private ExpressionRules() {
    }

    static void check(RuleContext rc) {
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.Calculate c && !rc.graph().isShadowed(n)) {
                calculate(rc, c);
            }
            if (n instanceof Node.Http h) {
                h.inputMapping().forEach((target, source) -> {
                    if (source.startsWith("=")) {
                        expression(rc, source.substring(1), scope(rc, n, Map.of()), n,
                                n.configPath("inputMapping") + "/" + target, null, null);
                    }
                });
            }
        }
        for (Transition t : rc.definition().transitions()) {
            if (t.condition() != null && t.condition().isExpression()) {
                Node from = rc.graph().node(t.from());
                expression(rc, t.condition().expression(), scope(rc, from, Map.of()), from,
                        t.path() + "/condition/expression", t.id(), ExprType.BOOLEAN);
            }
        }
        form(rc);
        for (DecisionTable table : rc.definition().decisions()) {
            if (table.usesExpressions()) decision(rc, table);
        }
    }

    // ---------------------------------------------------------------- decision tables (S146, CG-D04)

    /**
     * What a decision entry may read: request fields and step results (whether a step result is available at each
     * decision step that uses the table is checked separately). There are no locals, so a result cannot use another
     * result of the table.
     */
    static Scope decisionScope(RuleContext rc) {
        return new Scope() {
            @Override
            public ExprType input(String key) {
                FormSchema.Field f = rc.definition().inputSchema().properties().get(key);
                return f == null ? null : ofField(f);
            }

            @Override
            public ExprType step(String stepId, String key) {
                return rc.types().resolve("/steps/" + stepId + "/" + key) instanceof Resolved r ? ofFieldType(r.type()) : null;
            }
        };
    }

    private static void decision(RuleContext rc, DecisionTable table) {
        java.util.List<Node> users = rc.definition().nodes().stream()
                .filter(n -> n instanceof Node.Decision dn && dn.decisionRef().equals(table.key()) && !rc.graph().isShadowed(n))
                .toList();
        Scope scope = decisionScope(rc);
        for (DecisionTable.Row row : table.rows()) {
            String base = table.path() + "/rows/" + row.index();
            java.util.List<Condition> terms = row.terms();
            for (int i = 0; i < terms.size(); i++) {
                Condition term = terms.get(i);
                if (!term.isExpression()) continue;
                String path = base + "/when" + (row.when().compound() ? "/" + row.when().group() + "/" + i : "") + "/expression";
                Expression e = expression(rc, term.expression(), scope, null, path, null, ExprType.BOOLEAN);
                if (e == null) continue;
                if (!e.fieldRefs().contains(term.fieldRef())) {
                    rc.issues().blocker("WF_DECISION_EXPRESSION_COLUMN", path, null, null, "The formula of rule "
                            + (row.index() + 1) + " is in the column " + term.fieldRef() + ", so it must use that field ("
                            + name(term.fieldRef()) + ").");
                }
                decisionEntry(rc, e, scope, users, path);
            }
            for (Map.Entry<String, String> out : row.thenExpressions().entrySet()) {
                String path = base + "/then/" + out.getKey() + "/expression";
                com.orqix.compiler.internal.model.Literal reference = table.defaultOutput().get(out.getKey());
                if (table.priority() != null && out.getKey().equals(table.priority().output())) {
                    rc.issues().blocker("WF_DECISION_EXPRESSION_UNSUPPORTED", path, null, null, "The result "
                            + q(out.getKey()) + " decides the priority between rows, so it must be a fixed value, not a formula.");
                    continue;
                }
                ExprType wanted = reference == null ? null : switch (reference) {
                    case com.orqix.compiler.internal.model.Literal.Num n -> ExprType.NUMBER;
                    case com.orqix.compiler.internal.model.Literal.Str s -> ExprType.STRING;
                    case com.orqix.compiler.internal.model.Literal.Bool b -> ExprType.BOOLEAN;
                };
                Expression e = expression(rc, out.getValue(), scope, null, path, null, null);
                if (e == null) continue;
                if (wanted != null && !fits(e.type(), wanted)) {
                    rc.issues().blocker("WF_EXPRESSION_TYPE", path, null, null, "The result " + q(out.getKey())
                            + " of rule " + (row.index() + 1) + " must be " + wanted.describe()
                            + " like the default, but its formula gives " + e.type().describe() + ".");
                }
                decisionEntry(rc, e, scope, users, path);
            }
        }
    }

    private static String name(String fieldRef) {
        String[] parts = fieldRef.split("/");
        return parts.length == 3 && "input".equals(parts[1]) ? "input." + parts[2]
                : parts.length == 4 ? "steps." + parts[2] + "." + parts[3] : fieldRef;
    }

    /** Only single values (no lists or rows), and step results that always come before every step using the table. */
    private static void decisionEntry(RuleContext rc, Expression e, Scope scope, java.util.List<Node> users, String path) {
        for (String ref : e.fieldRefs()) {
            String[] parts = ref.split("/");
            ExprType t = ref.startsWith("/input/") ? scope.input(parts[2]) : scope.step(parts[2], parts[3]);
            if (!(t == ExprType.NUMBER || t == ExprType.STRING || t == ExprType.BOOLEAN || t == ExprType.DATE)) {
                rc.issues().blocker("WF_DECISION_EXPRESSION_FIELD", path, null, null, "A rule table formula can use"
                        + " single values only (a number, text, true or false, or a date); " + name(ref) + " is "
                        + (t == null ? "not available" : t.describe()) + ".");
                continue;
            }
            if (!ref.startsWith("/steps/") || !(rc.types().resolve(ref) instanceof Resolved r) || r.producer() == null) {
                continue;
            }
            for (Node user : users) {
                if (r.producer() == user) {
                    rc.issues().blocker("WF_FIELDREF_NOT_GUARANTEED", path, user.id(), null, q(user)
                            + " cannot use its own result in its rule table.");
                } else if (rc.graph().hasDominators() && rc.graph().isReachable(user)
                        && !rc.graph().dominates(r.producer(), user)) {
                    rc.issues().blocker("WF_FIELDREF_NOT_GUARANTEED", path, user.id(), null, q(r.producer())
                            + " does not always happen before " + q(user) + ", so its result cannot be used in its rule table.");
                }
            }
        }
    }

    /** @return the expressions of a table's entries, compiled with the decision scope (those with problems left out) */
    static java.util.List<Expression> decisionExpressions(RuleContext rc, DecisionTable table) {
        java.util.List<Expression> list = new java.util.ArrayList<>();
        Scope scope = decisionScope(rc);
        for (DecisionTable.Row row : table.rows()) {
            row.terms().stream().filter(Condition::isExpression).forEach(t -> compiled(t.expression(), scope, list));
            row.thenExpressions().values().forEach(v -> compiled(v, scope, list));
        }
        return list;
    }

    /** @return the pinned types (S137 notation) of the expressions of a table that uses them */
    public static tools.jackson.databind.node.ObjectNode decisionTypes(RuleContext rc, DecisionTable table) {
        return com.orqix.compiler.expr.PinnedTypes.of(decisionExpressions(rc, table), decisionScope(rc));
    }

    /**
     * @return the expression type of a request field, or null when formulas cannot use it: a file, or a time of day or
     *     date-time (S140), which the expression language has no type for; comparing them as text would be wrong
     *     across time-zone offsets
     */
    static ExprType ofField(FormSchema.Field f) {
        return switch (f.type()) {
            case NUMBER, INTEGER -> ExprType.NUMBER;
            case STRING -> ExprType.STRING;
            case BOOLEAN -> ExprType.BOOLEAN;
            case DATE -> ExprType.DATE;
            case ATTACHMENT, TIME, DATETIME -> null;
            case ARRAY -> {
                if (f.items() == null) yield null;
                if (f.items().type() == FieldKind.OBJECT || !f.items().rowFields().isEmpty()) {
                    Map<String, ExprType> columns = new LinkedHashMap<>();
                    f.items().rowFields().forEach((k, col) -> {
                        ExprType t = ofField(col);
                        if (t != null) columns.put(k, t);
                    });
                    yield new ExprType.ListOf(new ExprType.Row(columns));
                }
                ExprType item = ofField(f.items());
                yield item == null ? null : new ExprType.ListOf(item);
            }
            case OBJECT -> {
                Map<String, ExprType> columns = new LinkedHashMap<>();
                f.rowFields().forEach((k, col) -> {
                    ExprType t = ofField(col);
                    if (t != null) columns.put(k, t);
                });
                yield new ExprType.Row(columns);
            }
        };
    }

    static ExprType ofFieldType(FieldType t) {
        return switch (t.kind()) {
            case NUMBER, INTEGER -> ExprType.NUMBER;
            case STRING -> ExprType.STRING;
            case BOOLEAN -> ExprType.BOOLEAN;
            case DATE -> ExprType.DATE;
            case ARRAY -> new ExprType.ListOf(t.items() == null ? ExprType.ANY : ofFieldType(t.items()));
            case OBJECT -> ExprType.ANY;
            case ATTACHMENT, TIME, DATETIME -> null;
        };
    }

    /** @return the declared type of a Calculate output or a form field as an expression type */
    static ExprType declared(String type) {
        return switch (type) {
            case "number", "integer" -> ExprType.NUMBER;
            case "boolean" -> ExprType.BOOLEAN;
            case "date" -> ExprType.DATE;
            default -> ExprType.STRING;
        };
    }

    /** What an expression used at {@code usedAt} may read: request fields, step results and the given locals. */
    public static Scope scope(RuleContext rc, Node usedAt, Map<String, ExprType> locals) {
        return new Scope() {
            @Override
            public ExprType input(String key) {
                FormSchema.Field f = rc.definition().inputSchema().properties().get(key);
                return f == null ? null : ofField(f);
            }

            @Override
            public ExprType step(String stepId, String key) {
                if (usedAt == null) return null;
                return rc.types().resolve("/steps/" + stepId + "/" + key) instanceof Resolved r ? ofFieldType(r.type()) : null;
            }

            @Override
            public ExprType local(String name) {
                return locals.get(name);
            }
        };
    }

    /** Request fields only: form rules, computed fields and form visibility. */
    static Scope formScope(RuleContext rc) {
        return scope(rc, null, Map.of());
    }

    /**
     * Compiles and checks one expression; reports WF_EXPRESSION_INVALID, WF_EXPRESSION_TYPE (when {@code expected}
     * is given) and WF_FIELDREF_NOT_GUARANTEED for step results that do not always come before.
     *
     * @return the expression, or null when it has a problem
     */
    static Expression expression(RuleContext rc, String source, Scope scope, Node usedAt, String path,
            String transitionId, ExprType expected) {
        String nodeId = usedAt == null ? null : usedAt.id();
        if (source.contains("${") || source.contains("#{")) {
            // The generated BPMN may contain only the engine expressions the compiler writes itself (S6).
            rc.issues().blocker("WF_EXPRESSION_INVALID", path, nodeId, transitionId,
                    "An expression cannot contain the characters ${ or #{, even inside text.");
            return null;
        }
        Expression e;
        try {
            e = Expression.compile(source, scope);
        } catch (ExpressionException problem) {
            rc.issues().blocker("WF_EXPRESSION_INVALID", path, nodeId, transitionId, problem.getMessage()
                    + " (at character " + (problem.position() + 1) + ")");
            return null;
        }
        if (expected != null && !fits(e.type(), expected)) {
            rc.issues().blocker("WF_EXPRESSION_TYPE", path, nodeId, transitionId, "This expression must give "
                    + expected.describe() + ", but it gives " + e.type().describe() + ".");
            return null;
        }
        for (String ref : e.fieldRefs()) {
            if (ref.startsWith("/steps/") && usedAt != null
                    && rc.types().resolve(ref) instanceof Resolved r && r.producer() != null) {
                Node producer = r.producer();
                if (producer == usedAt) {
                    rc.issues().blocker("WF_FIELDREF_NOT_GUARANTEED", path, nodeId, transitionId, q(usedAt)
                            + " cannot use its own result; use the earlier output by name.");
                } else if (rc.graph().hasDominators() && rc.graph().isReachable(usedAt)
                        && !rc.graph().dominates(producer, usedAt)) {
                    rc.issues().blocker("WF_FIELDREF_NOT_GUARANTEED", path, nodeId, transitionId, q(producer)
                            + " does not always happen before " + q(usedAt) + ", so its result cannot be used there.");
                }
            }
        }
        return e;
    }

    static boolean fits(ExprType actual, ExprType expected) {
        return actual == ExprType.NULL || actual == ExprType.ANY || actual.equals(expected);
    }

    private static void calculate(RuleContext rc, Node.Calculate c) {
        Map<String, ExprType> locals = new LinkedHashMap<>();
        if (c.calendar() != null && !c.calendar().isNull()) {
            try {
                Values.calendar(c.calendar());
            } catch (IllegalArgumentException e) {
                rc.issues().blocker("WF_CALENDAR_INVALID", c.configPath("calendar"), c.id(), null, e.getMessage());
            }
        }
        for (Node.Calculate.Output o : c.outputs()) {
            String base = c.configPath("outputs") + "/" + o.index();
            if (locals.containsKey(o.key())) {
                rc.issues().blocker("WF_CALCULATE_OUTPUT_DUPLICATE", base + "/key", c.id(), null, q(c)
                        + " has two outputs named " + q(o.key()) + ".");
                continue;
            }
            boolean numeric = o.type().equals("number") || o.type().equals("integer");
            if (!numeric && (o.scale() != null || o.rounding() != null)) {
                rc.issues().blocker("WF_CALCULATE_ROUNDING", base, c.id(), null, "Only a number output can be"
                        + " rounded; " + q(o.key()) + " is " + declared(o.type()).describe() + ".");
            }
            if (o.type().equals("integer") && o.scale() != null && o.scale() != 0) {
                rc.issues().blocker("WF_CALCULATE_ROUNDING", base + "/scale", c.id(), null, q(o.key())
                        + " is a whole number, so it has no decimal places.");
            }
            Expression e = expression(rc, o.expression(), scope(rc, c, locals), c, base + "/expression", null, null);
            ExprType wanted = declared(o.type());
            if (e != null && !fits(e.type(), wanted)) {
                rc.issues().blocker("WF_CALCULATE_TYPE", base + "/expression", c.id(), null, "The output "
                        + q(o.key()) + " is declared as " + wanted.describe() + ", but its expression gives "
                        + e.type().describe() + ".");
            }
            locals.put(o.key(), wanted);
        }
    }

    /** Computed form fields and cross-field rules (CG-F04). */
    private static void form(RuleContext rc) {
        InputUi ui = rc.definition().inputUi();
        if (ui == null) return;
        var props = rc.definition().inputSchema().properties();
        ui.computed().forEach((key, source) -> {
            String path = "/inputUi/computed/" + key;
            FormSchema.Field field = props.get(key);
            ExprType wanted = field == null ? null : ofField(field);
            if (wanted == null || wanted instanceof ExprType.ListOf || wanted instanceof ExprType.Row) {
                rc.issues().blocker("WF_FORM_COMPUTED_INVALID", path, null, null, field == null
                        ? q(key) + " is not a question of the request form." : "A computed field must be a single"
                        + " number, text, true-or-false or date question; " + q(key) + " is not.");
                return;
            }
            Expression e = expression(rc, source, formScope(rc), null, path, null, null);
            if (e == null) return;
            if (!fits(e.type(), wanted)) {
                rc.issues().blocker("WF_EXPRESSION_TYPE", path, null, null, q(key) + " is " + wanted.describe()
                        + ", but its expression gives " + e.type().describe() + ".");
            }
            if (e.fieldRefs().contains("/input/" + key)) {
                rc.issues().blocker("WF_FORM_COMPUTED_INVALID", path, null, null, "The computed field " + q(key)
                        + " cannot use its own value.");
            }
            if (ui.requiredWhen().containsKey(key) || rc.definition().inputSchema().required().contains(key)) {
                rc.issues().warning("WF_FORM_COMPUTED_REQUIRED", path, null, null, "The computed field " + q(key)
                        + " is required, so a request is refused whenever its expression gives no value.");
            }
        });
        String cycle = FormComputation.cycle(ui.computed(), key -> {
            try {
                return Expression.compile(ui.computed().get(key), formScope(rc)).fieldRefs();
            } catch (ExpressionException e) {
                return java.util.Set.of();
            }
        });
        if (cycle != null) {
            rc.issues().blocker("WF_FORM_COMPUTED_CYCLE", "/inputUi/computed/" + cycle, null, null, "The computed"
                    + " fields use each other in a circle (through " + q(cycle) + "); one of them must be entered.");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (InputUi.FormRule rule : ui.rules()) {
            String path = "/inputUi/rules/" + rule.index();
            if (!ids.add(rule.id())) {
                rc.issues().blocker("WF_FORM_RULE_INVALID", path + "/id", null, null, "Two form rules are named "
                        + q(rule.id()) + ".");
            }
            for (String f : rule.fields()) {
                if (!props.containsKey(f)) {
                    rc.issues().blocker("WF_FORM_RULE_INVALID", path + "/fields", null, null, "The rule "
                            + q(rule.id()) + " shows its message at " + q(f) + ", which is not a question of the form.");
                }
            }
            expression(rc, rule.check(), formScope(rc), null, path + "/check", null, ExprType.BOOLEAN);
        }
    }

    /**
     * The pinned types (S137) of every expression site, as base64 canonical JSON: {@code node/<id>} for a Calculate
     * step or the = mappings of a call, {@code flow/<transitionId>} for an expression condition.
     */
    public static Map<String, String> pinnedTypes(RuleContext rc) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Node n : rc.definition().nodes()) {
            java.util.List<Expression> list = new java.util.ArrayList<>();
            Map<String, ExprType> locals = new LinkedHashMap<>();
            if (n instanceof Node.Calculate c) {
                for (Node.Calculate.Output o : c.outputs()) {
                    compiled(o.expression(), scope(rc, n, locals), list);
                    locals.put(o.key(), declared(o.type()));
                }
            } else if (n instanceof Node.Http h) {
                h.inputMapping().values().stream().filter(v -> v.startsWith("="))
                        .forEach(v -> compiled(v.substring(1), scope(rc, n, Map.of()), list));
            }
            if (!list.isEmpty() || n instanceof Node.Calculate) {
                out.put("node/" + n.id(), base64(com.orqix.compiler.expr.PinnedTypes.of(list, scope(rc, n, locals))));
            }
            if (n instanceof Node.Decision dn) {
                // S146: only a decision step whose table uses expressions gets pinned types.
                rc.definition().decisions().stream().filter(t -> t.key().equals(dn.decisionRef())).findFirst()
                        .filter(DecisionTable::usesExpressions)
                        .ifPresent(t -> out.put("node/" + n.id(), base64(decisionTypes(rc, t))));
            }
        }
        for (Transition t : rc.definition().transitions()) {
            if (t.condition() != null && t.condition().isExpression()) {
                Node from = rc.graph().node(t.from());
                java.util.List<Expression> list = new java.util.ArrayList<>();
                compiled(t.condition().expression(), scope(rc, from, Map.of()), list);
                out.put("flow/" + t.id(), base64(com.orqix.compiler.expr.PinnedTypes.of(list, scope(rc, from, Map.of()))));
            }
        }
        return out;
    }

    /** The pinned types of one Calculate step, as JSON (the simulator runs it like the engine does). */
    public static tools.jackson.databind.node.ObjectNode pinnedTypesOf(RuleContext rc, Node.Calculate c) {
        java.util.List<Expression> list = new java.util.ArrayList<>();
        Map<String, ExprType> locals = new LinkedHashMap<>();
        for (Node.Calculate.Output o : c.outputs()) {
            compiled(o.expression(), scope(rc, c, locals), list);
            locals.put(o.key(), declared(o.type()));
        }
        return com.orqix.compiler.expr.PinnedTypes.of(list, scope(rc, c, locals));
    }

    private static void compiled(String source, Scope scope, java.util.List<Expression> into) {
        try {
            into.add(Expression.compile(source, scope));
        } catch (ExpressionException e) {
            // reported as a blocker; a definition with blockers is never generated
        }
    }

    private static String base64(tools.jackson.databind.JsonNode node) {
        return java.util.Base64.getEncoder().encodeToString(com.orqix.compiler.internal.json.Jcs.canonicalize(node)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Field references a step reads through expressions (Calculate outputs, = mappings, expression conditions). */
    static java.util.Set<String> refs(RuleContext rc, Node node, String source, Map<String, ExprType> locals) {
        try {
            return Expression.compile(source, scope(rc, node, locals)).fieldRefs();
        } catch (ExpressionException e) {
            return java.util.Set.of();
        }
    }

    /** @return every field reference a Calculate step reads */
    static java.util.Set<String> calculateRefs(RuleContext rc, Node.Calculate c) {
        java.util.Set<String> refs = new java.util.LinkedHashSet<>();
        Map<String, ExprType> locals = new LinkedHashMap<>();
        for (Node.Calculate.Output o : c.outputs()) {
            refs.addAll(refs(rc, c, o.expression(), locals));
            locals.put(o.key(), declared(o.type()));
        }
        return refs;
    }
}
