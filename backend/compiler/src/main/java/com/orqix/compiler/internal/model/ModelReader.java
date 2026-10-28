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
                readLayout(root.get("uiLayout")),
                readStages(root.get("stages")),
                readSubprocesses(root.get("subprocesses")));
    }

    private static List<WorkflowDefinition.Subprocess> readSubprocesses(JsonNode array) {
        List<WorkflowDefinition.Subprocess> list = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode s = array.get(i);
                List<String> nodes = new ArrayList<>();
                s.get("nodes").forEach(n -> nodes.add(n.stringValue()));
                JsonNode onError = s.get("onError");
                JsonNode trigger = s.get("trigger");
                list.add(new WorkflowDefinition.Subprocess(i, s.get("id").stringValue(), s.get("label").stringValue(),
                        "event".equals(s.get("type").stringValue()), List.copyOf(nodes),
                        text(s, "timeoutAfter"), text(s, "onTimeout"), onError == null ? null : text(onError, "to"),
                        onError == null ? null : text(onError, "errorCode"),
                        trigger == null ? null : text(trigger, "type"),
                        trigger == null ? null : text(trigger, "errorCode"),
                        trigger == null ? null : text(trigger, "signal"),
                        trigger == null ? null : text(trigger, "after"),
                        s.path("interrupting").asBoolean(false)));
            }
        }
        return List.copyOf(list);
    }

    private static String text(JsonNode object, String member) {
        JsonNode v = object.get(member);
        return v == null || v.isNull() ? null : v.stringValue();
    }

    private static List<WorkflowDefinition.Stage> readStages(JsonNode stages) {
        List<WorkflowDefinition.Stage> list = new ArrayList<>();
        if (stages != null) {
            for (int i = 0; i < stages.size(); i++) {
                JsonNode s = stages.get(i);
                List<String> nodes = new ArrayList<>();
                s.get("nodes").forEach(n -> nodes.add(n.stringValue()));
                list.add(new WorkflowDefinition.Stage(i, s.get("id").stringValue(), s.get("label").stringValue(),
                        List.copyOf(nodes), s.has("sla") ? s.get("sla").stringValue() : null));
            }
        }
        return List.copyOf(list);
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
                    c.has("timeoutAfter") ? c.get("timeoutAfter").stringValue() : null,
                    c.has("requiredSkills") ? java.util.stream.StreamSupport.stream(c.get("requiredSkills").spliterator(),false)
                            .map(JsonNode::stringValue).toList() : List.of(), c.get("calendar"), c.get("escalation"),
                    c.has("separateFrom") ? java.util.stream.StreamSupport.stream(c.get("separateFrom").spliterator(),false)
                            .map(JsonNode::stringValue).toList() : List.of(),
                    c.has("correctable") ? java.util.stream.StreamSupport.stream(c.get("correctable").spliterator(),false)
                            .map(JsonNode::stringValue).toList() : List.of(),
                    c.has("multiInstance") ? c.get("multiInstance").get("approvers").intValue() : null,
                    c.has("dueAfterMinutes") ? c.get("dueAfterMinutes").intValue() : null,
                    c.has("perItem") ? new Node.PerItem(c.get("perItem").get("itemsRef").stringValue(),
                            c.get("perItem").has("itemLabel") ? c.get("perItem").get("itemLabel").stringValue() : null,
                            c.get("perItem").path("allowSamePerson").asBoolean(false),
                            c.get("perItem").path("sequential").asBoolean(false),
                            c.get("perItem").path("quorum").isIntegralNumber() ? c.get("perItem").get("quorum").intValue() : null,
                            c.get("perItem").has("assigneeFrom") ? c.get("perItem").get("assigneeFrom").stringValue() : null) : null);
            case DECISION -> new Node.Decision(index, id, label, c.get("decisionRef").stringValue());
            case BRANCH -> new Node.Branch(index, id, label);
            case HTTP -> new Node.Http(index, id, label, c.get("connectionRef").stringValue(),
                    c.get("operation").stringValue(), stringMap(c.get("inputMapping")),
                    stringMap(c.get("resultMapping")), c.get("timeoutSeconds").intValue(),
                    c.has("compensates") ? c.get("compensates").stringValue() : null,
                    "automatic".equals(c.path("compensation").asString("manual")),
                    c.has("raiseErrorOnFailure") ? c.get("raiseErrorOnFailure").stringValue() : null);
            case GENERATE_DOCUMENT -> new Node.GenerateDocument(index, id, label, c.get("format").stringValue(),
                    c.get("fileName").stringValue(), c.get("template").stringValue(), stringMap(c.get("inputMapping")));
            case EXTERNAL_TASK -> new Node.ExternalTask(index, id, label, c.get("topic").stringValue(),
                    stringMap(c.get("inputMapping")), payloadFields(c.get("result")),
                    c.has("leaseSeconds") ? c.get("leaseSeconds").intValue() : 120);
            case NOTIFICATION -> new Node.Notification(index, id, label, c.get("channel").stringValue(),
                    c.get("recipient").stringValue(), c.get("message").stringValue());
            case PARALLEL_SPLIT -> new Node.ParallelSplit(index, id, label, c.get("joinRef").stringValue(),
                    "inclusive".equals(c.path("mode").asString("all")));
            case PARALLEL_JOIN -> new Node.ParallelJoin(index, id, label);
            case WAIT -> new Node.Wait(index, id, label, c.has("duration") ? c.get("duration").stringValue() : null,
                    c.has("untilField") ? c.get("untilField").stringValue() : null,
                    c.has("atTime") ? c.get("atTime").stringValue() : null);
            case START_WORKFLOW -> new Node.StartWorkflow(index, id, label, c.get("workflowRef").stringValue(),
                    stringMap(c.get("inputMapping")), stringMap(c.get("resultMapping")),
                    c.get("repeat") == null ? null : new Node.Repeat(c.get("repeat").get("maxIterations").intValue(),
                            c.get("repeat").get("untilOutcome").stringValue()),
                    c.get("forEach") == null ? null : new Node.ForEach(c.get("forEach").get("itemsRef").stringValue(),
                            c.get("forEach").get("itemInput").stringValue(), c.get("forEach").get("maxItems").intValue(),
                            "parallel".equals(c.get("forEach").path("mode").asString())),
                    c.has("collectionMapping") ? stringMap(c.get("collectionMapping")) : Map.of());
            case WAIT_FOR_MESSAGE -> new Node.WaitForMessage(index, id, label, c.get("message").stringValue(),
                    c.get("correlationField").stringValue(),
                    c.has("timeoutAfter") ? c.get("timeoutAfter").stringValue() : null, payloadFields(c.get("payload")),
                    c.has("externalCallback") ? c.get("externalCallback").get("tokenValidFor").stringValue() : null);
            case END -> new Node.End(index, id, label, c.get("businessOutcome").stringValue(),
                    c.get("outcomeKind").stringValue());
            case RAISE_ERROR -> new Node.RaiseError(index, id, label, c.get("errorCode").stringValue());
            case SEND_SIGNAL -> new Node.SendSignal(index, id, label, c.get("signal").stringValue());
            case WAIT_FOR_SIGNAL -> new Node.WaitForSignal(index, id, label, c.get("signal").stringValue());
            case COMPENSATE -> new Node.Compensate(index, id, label);
            case SEND_ADAPTER -> new Node.SendAdapter(index, id, label, c.get("adapter").stringValue(),
                    c.get("kind").stringValue(),
                    c.has("to") ? java.util.stream.StreamSupport.stream(c.get("to").spliterator(), false)
                            .map(JsonNode::stringValue).toList() : List.of(),
                    text(c, "subject"), text(c, "body"), text(c, "title"), text(c, "text"), text(c, "topic"),
                    text(c, "key"), stringMap(c.get("inputMapping")));
        };
    }

    private static List<Node.PayloadField> payloadFields(JsonNode payload) {
        List<Node.PayloadField> fields = new ArrayList<>();
        if (payload != null) {
            for (JsonNode f : payload) {
                String type = f.get("type").stringValue();
                fields.add(new Node.PayloadField(f.get("name").stringValue(),
                        "date".equals(type) ? FieldKind.DATE : FieldKind.fromJson(type),
                        f.has("title") ? f.get("title").stringValue() : f.get("name").stringValue(),
                        f.has("required") && f.get("required").booleanValue()));
            }
        }
        return List.copyOf(fields);
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
            rowList.add(new DecisionTable.Row(r, readCondition(row.get("when")), outputMap(row.get("then")),
                    readWindow(row.get("effective"))));
        }
        JsonNode policy = t.get("hitPolicy");
        require(policy == null || (policy.isString() && (DecisionTable.UNIQUE.equals(policy.stringValue())
                || DecisionTable.FIRST.equals(policy.stringValue()))), "decision table hitPolicy must be UNIQUE or FIRST");
        return new DecisionTable(index, key.stringValue(), version.intValue(),
                policy == null ? DecisionTable.UNIQUE : policy.stringValue(), List.copyOf(rowList), outputMap(def));
    }

    /**
     * @throws IllegalArgumentException when the condition is malformed
     */
    public static Condition readCondition(JsonNode c) {
        require(c != null && c.isObject(), "condition must be an object");
        if (c.has("all") || c.has("any")) {
            String group = c.has("all") ? "all" : "any";
            require(c.size() == 1 && c.path(group).isArray() && c.path(group).size() >= 2 && c.path(group).size() <= 8,
                    "logical conditions require 2 to 8 atomic terms");
            List<Condition> terms = new ArrayList<>();
            for (JsonNode term : c.path(group)) {
                require(!term.has("all") && !term.has("any"), "nested logical conditions are not supported");
                terms.add(readCondition(term));
            }
            return new Condition(null, null, null, group, List.copyOf(terms));
        }
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

    private static DecisionTable.Window readWindow(JsonNode w) {
        if (w == null || w.isNull()) {
            return null;
        }
        require(w.isObject(), "effective must be an object");
        try {
            java.time.LocalDate from = w.has("from") ? java.time.LocalDate.parse(w.get("from").stringValue()) : null;
            java.time.LocalDate to = w.has("to") ? java.time.LocalDate.parse(w.get("to").stringValue()) : null;
            require(from != null || to != null, "effective needs a from or a to date");
            require(from == null || to == null || !from.isAfter(to), "effective.from must not be after effective.to");
            return new DecisionTable.Window(from, to);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("effective dates must be real calendar dates (YYYY-MM-DD)");
        }
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
            fields.put(e.getKey(), new FormSchema.Field(e.getKey(), FieldKind.fromJson(f.get("type").stringValue(),
                    f.path("format").asString(null)),
                    f.get("title").stringValue(), enumValues, intOrNull(f.get("minLength")),
                    intOrNull(f.get("maxLength")), decimalOrNull(f.get("minimum")), decimalOrNull(f.get("maximum")),
                    decimalOrNull(f.get("multipleOf")), f.has("items") ? readField(e.getKey(), f.get("items")) : null,
                    intOrNull(f.get("minItems")), intOrNull(f.get("maxItems")), f.path("formatMinimum").asString(null),
                    f.path("formatMaximum").asString(null),f.path("incidentEditable").asBoolean(false)));
        }
        return new FormSchema(path, List.copyOf(required), Collections.unmodifiableMap(fields));
    }

    private static FormSchema.Field readField(String key, JsonNode f) {
        if ("object".equals(f.path("type").asString(null))) {
            Map<String, FormSchema.Field> columns = new TreeMap<>();
            for (Map.Entry<String, JsonNode> e : f.get("properties").properties()) {
                columns.put(e.getKey(), readField(e.getKey(), e.getValue()));
            }
            List<String> required = new ArrayList<>();
            f.path("required").forEach(r -> required.add(r.stringValue()));
            return new FormSchema.Field(key, FieldKind.OBJECT, f.path("title").asString(key), null, null, null, null,
                    null, null, null, null, null, null, null, false, List.copyOf(required),
                    Collections.unmodifiableMap(columns));
        }
        List<String> enums = null;
        if (f.has("enum")) {
            List<String> values = new ArrayList<>();
            f.get("enum").forEach(v -> values.add(v.stringValue()));
            enums = List.copyOf(values);
        }
        return new FormSchema.Field(key, FieldKind.fromJson(f.get("type").stringValue(), f.path("format").asString(null)),
                f.get("title").stringValue(),
                enums, intOrNull(f.get("minLength")), intOrNull(f.get("maxLength")), decimalOrNull(f.get("minimum")),
                decimalOrNull(f.get("maximum")), decimalOrNull(f.get("multipleOf")), null, null, null,
                f.path("formatMinimum").asString(null), f.path("formatMaximum").asString(null),f.path("incidentEditable").asBoolean(false));
    }

    private static InputUi readInputUi(JsonNode ui) {
        if (ui == null) {
            return null;
        }
        List<String> order = new ArrayList<>();
        if (ui.has("order")) {
            ui.get("order").forEach(o -> order.add(o.stringValue()));
        }
        List<InputUi.Section> sections = new ArrayList<>();
        int index = 0;
        for (JsonNode s : ui.path("layout").path("sections")) {
            List<String> fields = new ArrayList<>();
            s.path("fields").forEach(f -> fields.add(f.stringValue()));
            sections.add(new InputUi.Section(index++, s.path("id").asString(""), s.path("title").asString(""),
                    s.path("columns").asInt(1), List.copyOf(fields),
                    s.has("visibleWhen") ? readCondition(s.get("visibleWhen")) : null));
        }
        return new InputUi(List.copyOf(order), ui.has("widgets") ? stringMap(ui.get("widgets")) : Map.of(),
                conditionMap(ui.get("visibleWhen")), conditionMap(ui.get("requiredWhen")), List.copyOf(sections),
                "above".equals(ui.path("layout").path("helpText").asString("below")));
    }

    private static Map<String, Condition> conditionMap(JsonNode o) {
        Map<String, Condition> map = new TreeMap<>();
        if (o != null) {
            for (Map.Entry<String, JsonNode> e : o.properties()) {
                map.put(e.getKey(), readCondition(e.getValue()));
            }
        }
        return Collections.unmodifiableMap(map);
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
