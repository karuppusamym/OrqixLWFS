package com.orqix.compiler.internal.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Enums.Operator;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;
import com.orqix.compiler.internal.model.WorkflowDefinition.Dependency;
import com.orqix.compiler.internal.model.WorkflowDefinition.Execution;
import com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema;
import com.orqix.compiler.internal.model.WorkflowDefinition.InputUi;
import com.orqix.compiler.internal.model.WorkflowDefinition.Layout;
import com.orqix.compiler.internal.model.WorkflowDefinition.OutputEntry;
import com.orqix.compiler.internal.model.WorkflowDefinition.Position;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

import tools.jackson.databind.JsonNode;

/**
 * Pipeline stage 4: JSON tree to typed records. The tree must have passed the workflow JSON Schema; decision tables
 * can also be read from unvalidated drafts through {@link #readDecisionTable}, which checks what it reads.
 */
public final class ModelReader {

    private ModelReader() {
    }

    /** @param root a schema-valid workflow definition */
    public static WorkflowDefinition read(JsonNode root) {
        List<Node> nodes = new ArrayList<>();
        JsonNode nodeArray = root.get("nodes");
        for (int i = 0; i < nodeArray.size(); i++) {
            nodes.add(readNode(i, nodeArray.get(i)));
        }
        List<Transition> transitions = new ArrayList<>();
        JsonNode transitionArray = root.get("transitions");
        for (int i = 0; i < transitionArray.size(); i++) {
            transitions.add(readTransition(i, transitionArray.get(i)));
        }
        List<DecisionTable> decisions = new ArrayList<>();
        JsonNode decisionArray = root.get("decisions");
        for (int i = 0; i < decisionArray.size(); i++) {
            decisions.add(readDecisionTable(i, decisionArray.get(i)));
        }
        JsonNode exec = root.get("execution");
        Execution execution = new Execution(exec.get("maxConcurrentRuns").intValue(),
                exec.get("maxAttempts").intValue(), exec.get("retryDelaySeconds").intValue());
        List<Dependency> dependencies = new ArrayList<>();
        JsonNode deps = root.get("dependencies").get("connections");
        for (int i = 0; i < deps.size(); i++) {
            JsonNode d = deps.get(i);
            dependencies.add(new Dependency(i, d.get("key").stringValue(), d.get("definitionVersion").intValue()));
        }
        Map<String, OutputEntry> outputMapping = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : root.get("outputMapping").properties()) {
            outputMapping.put(e.getKey(), new OutputEntry(e.getKey(), e.getValue().get("fieldRef").stringValue(),
                    e.getValue().get("required").booleanValue()));
        }
        return new WorkflowDefinition(
                root.get("schemaVersion").stringValue(),
                root.get("workflowKey").stringValue(),
                root.get("name").stringValue(),
                readForm("/inputSchema", root.get("inputSchema")),
                readInputUi(root.get("inputUi")),
                readForm("/outputSchema", root.get("outputSchema")),
                List.copyOf(nodes),
                List.copyOf(transitions),
                List.copyOf(decisions),
                execution,
                root.get("security").get("selfApproval").stringValue(),
                List.copyOf(dependencies),
                Collections.unmodifiableMap(outputMapping),
                readLayout(root.get("uiLayout")));
    }

    private static Node readNode(int index, JsonNode n) {
        String id = n.get("id").stringValue();
        String label = n.get("label").stringValue();
        JsonNode c = n.get("config");
        NodeKind kind = NodeKind.fromJson(n.get("type").stringValue());
        return switch (kind) {
            case START -> new Node.Start(index, id, label);
            case APPROVAL -> new Node.Approval(index, id, label, c.get("candidateGroupRef").stringValue(),
                    c.get("rejectReasonRequired").booleanValue(),
                    c.has("dueAfterHours") ? c.get("dueAfterHours").intValue() : null,
                    c.has("timeoutAfter") ? c.get("timeoutAfter").stringValue() : null);
            case DECISION -> new Node.Decision(index, id, label, c.get("decisionRef").stringValue());
            case BRANCH -> new Node.Branch(index, id, label);
            case HTTP -> new Node.Http(index, id, label, c.get("connectionRef").stringValue(),
                    c.get("operation").stringValue(), stringMap(c.get("inputMapping")),
                    stringMap(c.get("resultMapping")), c.get("timeoutSeconds").intValue());
            case NOTIFICATION -> new Node.Notification(index, id, label, c.get("channel").stringValue(),
                    c.get("recipient").stringValue(), c.get("message").stringValue());
            case PARALLEL_SPLIT -> new Node.ParallelSplit(index, id, label, c.get("joinRef").stringValue());
            case PARALLEL_JOIN -> new Node.ParallelJoin(index, id, label);
            case WAIT -> new Node.Wait(index, id, label, c.get("duration").stringValue());
            case START_WORKFLOW -> new Node.StartWorkflow(index, id, label, c.get("workflowRef").stringValue(),
                    stringMap(c.get("inputMapping")), stringMap(c.get("resultMapping")),
                    c.get("repeat") == null ? null : new Node.Repeat(c.get("repeat").get("maxIterations").intValue(),
                            c.get("repeat").get("untilOutcome").stringValue()),
                    c.get("forEach") == null ? null : new Node.ForEach(c.get("forEach").get("itemsRef").stringValue(),
                            c.get("forEach").get("itemInput").stringValue(), c.get("forEach").get("maxItems").intValue()));
            case END -> new Node.End(index, id, label, c.get("businessOutcome").stringValue(),
                    c.get("outcomeKind").stringValue());
        };
    }

    private static Transition readTransition(int index, JsonNode t) {
        Route on = Route.fromJson(t.get("on").stringValue());
        JsonNode label = t.get("label");
        JsonNode condition = t.get("condition");
        return new Transition(index, t.get("id").stringValue(), t.get("from").stringValue(),
                t.get("to").stringValue(), on, label == null ? null : label.stringValue(),
                condition == null ? null : readCondition(condition));
    }

    /**
     * Reads one decision table, checking its structure (usable on drafts that were not schema-validated).
     *
     * @throws IllegalArgumentException when the table is malformed
     */
    public static DecisionTable readDecisionTable(int index, JsonNode t) {
        require(t != null && t.isObject(), "decision table must be an object");
        JsonNode key = t.get("key");
        JsonNode version = t.get("version");
        JsonNode rows = t.get("rows");
        JsonNode def = t.get("default");
        require(key != null && key.isString(), "decision table key must be a string");
        require(version != null && version.isIntegralNumber() && version.canConvertToInt(),
                "decision table version must be an integer");
        require(rows != null && rows.isArray(), "decision table rows must be an array");
        List<DecisionTable.Row> rowList = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            JsonNode row = rows.get(r);
            require(row.isObject(), "decision row must be an object");
            rowList.add(new DecisionTable.Row(r, readCondition(row.get("when")), outputMap(row.get("then"))));
        }
        return new DecisionTable(index, key.stringValue(), version.intValue(), List.copyOf(rowList),
                outputMap(def));
    }

    /**
     * @throws IllegalArgumentException when the condition is malformed
     */
    public static Condition readCondition(JsonNode c) {
        require(c != null && c.isObject(), "condition must be an object");
        JsonNode fieldRef = c.get("fieldRef");
        JsonNode operator = c.get("operator");
        JsonNode value = c.get("value");
        require(fieldRef != null && fieldRef.isString(), "condition fieldRef must be a string");
        require(operator != null && operator.isString() && Operator.fromJson(operator.stringValue()) != null,
                "condition operator must be eq, gte or lt");
        require(value != null && (value.isString() || value.isNumber() || value.isBoolean()),
                "condition value must be a string, number or boolean");
        return new Condition(fieldRef.stringValue(), Operator.fromJson(operator.stringValue()), Literal.of(value));
    }

    private static Map<String, Literal> outputMap(JsonNode o) {
        require(o != null && o.isObject(), "decision output must be an object");
        Map<String, Literal> map = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : o.properties()) {
            JsonNode v = e.getValue();
            require(v.isString() || v.isNumber() || v.isBoolean(), "decision output values must be scalars");
            map.put(e.getKey(), Literal.of(v));
        }
        return Collections.unmodifiableMap(map);
    }

    /** Reads a form schema (request or result form) that already passed the workflow JSON Schema. */
    public static FormSchema readFormSchema(String path, JsonNode form) {
        return readForm(path, form);
    }

    private static FormSchema readForm(String path, JsonNode form) {
        List<String> required = new ArrayList<>();
        JsonNode req = form.get("required");
        if (req != null) {
            req.forEach(r -> required.add(r.stringValue()));
        }
        Map<String, FormSchema.Field> fields = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : form.get("properties").properties()) {
            JsonNode f = e.getValue();
            List<String> enumValues = null;
            if (f.has("enum")) {
                List<String> values = new ArrayList<>();
                f.get("enum").forEach(v -> values.add(v.stringValue()));
                enumValues = List.copyOf(values);
            }
            fields.put(e.getKey(), new FormSchema.Field(e.getKey(), FieldKind.fromJson(f.get("type").stringValue()),
                    f.get("title").stringValue(), enumValues, intOrNull(f.get("minLength")),
                    intOrNull(f.get("maxLength")), decimalOrNull(f.get("minimum")), decimalOrNull(f.get("maximum")),
                    decimalOrNull(f.get("multipleOf")), f.has("items") ? readField(e.getKey(), f.get("items")) : null,
                    intOrNull(f.get("minItems")), intOrNull(f.get("maxItems"))));
        }
        return new FormSchema(path, List.copyOf(required), Collections.unmodifiableMap(fields));
    }

    private static FormSchema.Field readField(String key, JsonNode f) {
        List<String> enums = null;
        if (f.has("enum")) {
            List<String> values = new ArrayList<>();
            f.get("enum").forEach(v -> values.add(v.stringValue()));
            enums = List.copyOf(values);
        }
        return new FormSchema.Field(key, FieldKind.fromJson(f.get("type").stringValue()), f.get("title").stringValue(),
                enums, intOrNull(f.get("minLength")), intOrNull(f.get("maxLength")), decimalOrNull(f.get("minimum")),
                decimalOrNull(f.get("maximum")), decimalOrNull(f.get("multipleOf")), null, null, null);
    }

    private static InputUi readInputUi(JsonNode ui) {
        if (ui == null) {
            return null;
        }
        List<String> order = new ArrayList<>();
        if (ui.has("order")) {
            ui.get("order").forEach(o -> order.add(o.stringValue()));
        }
        return new InputUi(List.copyOf(order), ui.has("widgets") ? stringMap(ui.get("widgets")) : Map.of());
    }

    private static Layout readLayout(JsonNode layout) {
        if (layout == null) {
            return null;
        }
        Map<String, Position> positions = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : layout.get("positions").properties()) {
            positions.put(e.getKey(), new Position(e.getValue().get("x").decimalValue(),
                    e.getValue().get("y").decimalValue()));
        }
        return new Layout(Collections.unmodifiableMap(positions));
    }

    private static Map<String, String> stringMap(JsonNode o) {
        Map<String, String> map = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : o.properties()) {
            map.put(e.getKey(), e.getValue().stringValue());
        }
        return Collections.unmodifiableMap(map);
    }

    private static Integer intOrNull(JsonNode n) {
        return n == null ? null : n.intValue();
    }

    private static BigDecimal decimalOrNull(JsonNode n) {
        return n == null ? null : n.decimalValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
