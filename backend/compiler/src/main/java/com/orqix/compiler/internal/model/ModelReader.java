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
                exec.get("maxAttempts").intValue(), exec.get("retryDelaySeconds").intValue(), stepRetry(nodeArray),
                stepForEach(nodeArray));
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
                readSubprocesses(root.get("subprocesses")),
                WaitsReader.read(nodeArray, root.get("dependencies")));
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
                        s.path("interrupting").asBoolean(false),
                        trigger == null ? null : text(trigger, "message"),
                        trigger == null ? null : text(trigger, "correlationField"),
                        trigger == null ? List.of() : payloadFields(trigger.get("payload"))));
            }
        }
        return List.copyOf(list);
    }

    /** Step-level retry policies (CG-W04, S131) of http, externalTask and sendAdapter steps, by step id. */
    /** CG-E06 (S138): {@code config.forEach} of http, externalTask and aiTask steps, by step id. */
    private static Map<String, StepForEach> stepForEach(JsonNode nodeArray) {
        Map<String, StepForEach> policies = new TreeMap<>();
        for (int i = 0; i < nodeArray.size(); i++) {
            JsonNode node = nodeArray.get(i);
            JsonNode f = node.path("config").path("forEach");
            String type = node.path("type").asString("");
            if (!f.isObject() || !java.util.Set.of("http", "externalTask", "aiTask").contains(type)) {
                continue; // a child workflow's forEach is its own S14 policy (Node.ForEach)
            }
            JsonNode completion = f.path("completion");
            boolean parallel = "parallel".equals(f.path("mode").asString("sequential"));
            policies.putIfAbsent(node.get("id").stringValue(), new StepForEach("/nodes/" + i + "/config/forEach",
                    f.get("itemsRef").stringValue(), f.has("itemInput") ? f.get("itemInput").stringValue() : null,
                    f.has("itemMapping") ? stringMap(f.get("itemMapping")) : Map.of(), f.get("maxItems").intValue(),
                    parallel, f.has("batchSize") ? f.get("batchSize").intValue() : StepForEach.MAX_BATCH,
                    completion.path("when").asString(StepForEach.ALL),
                    completion.has("count") ? completion.get("count").intValue() : null,
                    f.has("collect") ? stringMap(f.get("collect")) : Map.of()));
        }
        return Collections.unmodifiableMap(policies);
    }

    private static Map<String, StepRetry> stepRetry(JsonNode nodeArray) {
        Map<String, StepRetry> policies = new TreeMap<>();
        for (int i = 0; i < nodeArray.size(); i++) {
            JsonNode node = nodeArray.get(i);
            JsonNode retry = node.path("config").path("retry");
            if (!retry.isObject()) {
                continue;
            }
            policies.put(node.get("id").stringValue(), new StepRetry("/nodes/" + i + "/config/retry",
                    retry.get("maxAttempts").intValue(), retry.get("delay").stringValue(),
                    retry.has("multiplier") ? retry.get("multiplier").decimalValue() : BigDecimal.ONE,
                    retry.has("maxDelay") ? retry.get("maxDelay").stringValue() : null,
                    retry.path("honorRetryAfter").asBoolean(true)));
        }
        return Collections.unmodifiableMap(policies);
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
                            c.get("perItem").has("assigneeFrom") ? c.get("perItem").get("assigneeFrom").stringValue() : null) : null,
                    c.has("fieldAccess") ? stringMap(c.get("fieldAccess")) : Map.of(),
                    c.has("capture") ? readForm("/nodes/" + index + "/config/capture", c.get("capture")) : null,
                    c.get("escalationPolicy"), c.has("aiReviewer") ? aiReviewer(c.get("aiReviewer")) : null);
            case DECISION -> new Node.Decision(index, id, label, c.get("decisionRef").stringValue());
            case BRANCH -> new Node.Branch(index, id, label);
            case HTTP -> new Node.Http(index, id, label, c.get("connectionRef").stringValue(),
                    c.get("operation").stringValue(), inputsWithItem(c),
                    stringMap(c.get("resultMapping")), c.get("timeoutSeconds").intValue(),
                    c.has("compensates") ? c.get("compensates").stringValue() : null,
                    "automatic".equals(c.path("compensation").asString("manual")),
                    c.has("raiseErrorOnFailure") ? c.get("raiseErrorOnFailure").stringValue() : null,
                    c.has("poll") ? readPoll(c.get("poll")) : null);
            case GENERATE_DOCUMENT -> new Node.GenerateDocument(index, id, label, c.get("format").stringValue(),
                    c.get("fileName").stringValue(), c.get("template").stringValue(), stringMap(c.get("inputMapping")));
            case EXTERNAL_TASK -> new Node.ExternalTask(index, id, label, c.get("topic").stringValue(),
                    inputsWithItem(c), payloadFields(c.get("result")),
                    c.has("leaseSeconds") ? c.get("leaseSeconds").intValue() : 120,
                    c.has("raiseErrorOnFailure") ? c.get("raiseErrorOnFailure").stringValue() : null);
            case WAIT_FOR_FIRST -> new Node.WaitForFirst(index, id, label, firstBranches(c.get("branches")));
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
                            c.get("forEach").has("itemInput") ? c.get("forEach").get("itemInput").stringValue() : null,
                            c.get("forEach").get("maxItems").intValue(),
                            "parallel".equals(c.get("forEach").path("mode").asString()),
                            itemMapping(c.get("forEach").path("itemMapping"))),
                    c.has("collectionMapping") ? stringMap(c.get("collectionMapping")) : Map.of());
            case WAIT_FOR_MESSAGE -> new Node.WaitForMessage(index, id, label, c.get("message").stringValue(),
                    c.get("correlationField").stringValue(),
                    c.has("timeoutAfter") ? c.get("timeoutAfter").stringValue() : null, payloadFields(c.get("payload")),
                    c.has("externalCallback") ? c.get("externalCallback").get("tokenValidFor").stringValue() : null,
                    c.has("fallbackCheck") ? readFallback(c.get("fallbackCheck")) : null,
                    c.has("guestResponse") ? c.get("guestResponse") : null);
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
            case AI_TASK -> new Node.AiTask(index, id, label, c.get("skillRef").stringValue(),
                    c.has("skillVersion") ? c.get("skillVersion").intValue() : null, c.get("mode").stringValue(),
                    inputsWithItem(c), c.path("autonomy").asString(Node.AiTask.SUGGEST),
                    c.has("minConfidence") ? c.get("minConfidence").decimalValue() : Node.AiTask.DEFAULT_MIN_CONFIDENCE,
                    c.path("reviewSamplePercent").asInt(Node.AiTask.DEFAULT_REVIEW_PERCENT),
                    c.has("redaction") ? stringMap(c.get("redaction")) : Map.of());
            case CALCULATE -> {
                List<Node.Calculate.Output> outputs = new ArrayList<>();
                int at = 0;
                for (JsonNode o : c.path("outputs")) {
                    outputs.add(new Node.Calculate.Output(at++, o.get("key").stringValue(), o.get("expression").stringValue(),
                            o.get("type").stringValue(), o.has("scale") ? o.get("scale").intValue() : null,
                            o.has("rounding") ? o.get("rounding").stringValue() : null,
                            o.has("description") ? o.get("description").stringValue() : null,
                            o.path("optional").asBoolean(false)));
                }
                yield new Node.Calculate(index, id, label, List.copyOf(outputs), c.get("calendar"));
            }
        };
    }

    /** S129: "Keep checking until..." settings with their defaults filled in. */
    private static Node.Poll readPoll(JsonNode p) {
        JsonNode backoff = p.get("backoff");
        JsonNode errors = p.get("onCheckError");
        List<String> shown = new ArrayList<>();
        p.path("showWhileWaiting").forEach(v -> shown.add(v.stringValue()));
        List<String> optional = new ArrayList<>();
        p.path("optionalResults").forEach(v -> optional.add(v.stringValue()));
        String every = p.get("every").stringValue();
        return new Node.Poll(!"afterInterval".equals(p.path("firstCheck").asString("immediate")), every,
                backoff == null ? BigDecimal.ONE : backoff.get("multiplier").decimalValue(),
                backoff == null ? every : backoff.get("maxInterval").stringValue(),
                p.path("jitterPercent").asInt(10), readCondition(p.get("until"), true),
                p.has("failWhen") ? readCondition(p.get("failWhen"), true) : null,
                p.get("giveUpAfter").stringValue(), p.has("maxChecks") ? p.get("maxChecks").intValue() : null,
                errors == null ? 5 : errors.get("maxConsecutive").intValue(),
                errors != null && "timedOut".equals(errors.path("then").asString("needsAttention")),
                p.path("finalCheckAtDeadline").asBoolean(true), List.copyOf(shown), List.copyOf(optional), p);
    }

    /** S129: the safety-net check of a message wait. */
    private static Node.FallbackCheck readFallback(JsonNode f) {
        return new Node.FallbackCheck(f.get("connectionRef").stringValue(), f.get("operation").stringValue(),
                stringMap(f.get("inputMapping")), stringMap(f.get("resultMapping")), f.get("timeoutSeconds").intValue(),
                f.get("every").stringValue(), f.path("jitterPercent").asInt(10), readCondition(f.get("until"), true),
                f.has("maxChecks") ? f.get("maxChecks").intValue() : null, f);
    }

    /** S139: the branches of a "Wait for the first" step, in authored order. */
    private static List<Node.FirstBranch> firstBranches(JsonNode array) {
        List<Node.FirstBranch> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode b = array.get(i);
            list.add(new Node.FirstBranch(i, b.get("id").stringValue(), b.get("label").stringValue(),
                    b.get("kind").stringValue(), text(b, "message"), text(b, "correlationField"),
                    payloadFields(b.get("payload")), text(b, "signal"), text(b, "after"), text(b, "untilField"),
                    text(b, "atTime"), text(b, "workingDuration"), text(b, "calendarRef"), text(b, "tokenValidFor"),
                    b.has("check") ? readFallback(b.get("check")) : null));
        }
        return List.copyOf(list);
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
                condition == null ? null : readCondition(condition),
                t.has("aiLabel") ? t.get("aiLabel").stringValue() : null,
                t.has("maxTraversals") ? t.get("maxTraversals").intValue() : null,
                t.has("branch") ? t.get("branch").stringValue() : null);
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
            JsonNode then = row.get("then");
            rowList.add(new DecisionTable.Row(r, readRowCondition(row.get("when")), outputMap(literalOutputs(then)),
                    readWindow(row.get("effective")), outputExpressions(then)));
        }
        JsonNode policy = t.get("hitPolicy");
        require(policy == null || (policy.isString() && DecisionTable.HIT_POLICIES.contains(policy.stringValue())),
                "decision table hitPolicy must be UNIQUE, FIRST, ANY, PRIORITY or COLLECT");
        JsonNode aggregation = t.get("aggregation");
        require(aggregation == null || (aggregation.isString()
                && DecisionTable.AGGREGATIONS.contains(aggregation.stringValue())),
                "decision table aggregation must be SUM, MIN, MAX or COUNT");
        return new DecisionTable(index, key.stringValue(), version.intValue(),
                policy == null ? DecisionTable.UNIQUE : policy.stringValue(), List.copyOf(rowList), outputMap(def),
                aggregation == null ? null : aggregation.stringValue(), readPriority(t.get("priority")));
    }

    private static DecisionTable.Priority readPriority(JsonNode p) {
        if (p == null || p.isNull()) {
            return null;
        }
        require(p.isObject() && p.path("output").isString() && p.path("order").isArray(),
                "decision table priority needs an output and an order");
        List<Literal> order = new ArrayList<>();
        for (JsonNode v : p.path("order")) {
            require(v.isString() || v.isNumber() || v.isBoolean(), "priority order values must be scalars");
            order.add(Literal.of(v));
        }
        return new DecisionTable.Priority(p.path("output").stringValue(), List.copyOf(order));
    }

    /**
     * Reads a route, form or section condition. The set operators {@code in} and {@code between} are refused: they
     * belong to decision rows only.
     *
     * @throws IllegalArgumentException when the condition is malformed
     */
    public static Condition readCondition(JsonNode c) {
        return readCondition(c, false);
    }

    /**
     * @param decisionRow true for a decision row condition, which may use {@code in} and {@code between}
     * @throws IllegalArgumentException when the condition is malformed
     */
    public static Condition readCondition(JsonNode c, boolean decisionRow) {
        require(c != null && c.isObject(), "condition must be an object");
        if (c.has("expression")) {
            require(!decisionRow, "an expression cannot be used as a decision row or check condition");
            require(c.size() == 1 && c.get("expression").isString(), "an expression condition has only its expression text");
            return Condition.ofExpression(c.get("expression").stringValue());
        }
        if (c.has("all") || c.has("any")) {
            String group = c.has("all") ? "all" : "any";
            require(c.size() == 1 && c.path(group).isArray() && c.path(group).size() >= 2 && c.path(group).size() <= 8,
                    "logical conditions require 2 to 8 atomic terms");
            List<Condition> terms = new ArrayList<>();
            for (JsonNode term : c.path(group)) {
                require(!term.has("all") && !term.has("any"), "nested logical conditions are not supported");
                terms.add(readCondition(term, decisionRow));
            }
            return new Condition(null, null, null, group, List.copyOf(terms));
        }
        JsonNode fieldRef = c.get("fieldRef");
        JsonNode operator = c.get("operator");
        JsonNode value = c.get("value");
        require(fieldRef != null && fieldRef.isString(), "condition fieldRef must be a string");
        Operator op = operator != null && operator.isString() ? Operator.fromJson(operator.stringValue()) : null;
        require(op != null, "condition operator must be eq, ne, gte, gt, lt or lte");
        if (op.set()) {
            require(decisionRow, "the comparisons in and between can be used in decision table rows only");
            return op == Operator.IN ? readIn(fieldRef.stringValue(), value) : readBetween(fieldRef.stringValue(), value);
        }
        require(value != null && (value.isString() || value.isNumber() || value.isBoolean()),
                "condition value must be a string, number or boolean");
        return new Condition(fieldRef.stringValue(), op, Literal.of(value));
    }

    /** {@code in}: 1 to 20 distinct strings or numbers, all of one type. */
    private static Condition readIn(String fieldRef, JsonNode value) {
        require(value != null && value.isArray() && value.size() >= 1 && value.size() <= 20,
                "the value of in must list 1 to 20 values");
        List<Literal> list = new ArrayList<>();
        for (JsonNode v : value) {
            require(v.isString() || v.isNumber(), "the values of in must be texts or numbers");
            Literal literal = Literal.of(v);
            require(list.isEmpty() || list.get(0).typeName().equals(literal.typeName()),
                    "the values of in must all be texts or all be numbers");
            require(list.stream().noneMatch(old -> com.orqix.compiler.ConditionMatcher.matches("eq", old.value(),
                    literal.value())), "the values of in must be different from each other");
            list.add(literal);
        }
        return new Condition(fieldRef, Operator.IN, null, null, List.of(), List.copyOf(list), null);
    }

    /** {@code between}: {@code from < to}, both numbers; each bound is included unless it says otherwise. */
    private static Condition readBetween(String fieldRef, JsonNode value) {
        require(value != null && value.isObject(), "the value of between must be an object with from and to");
        for (String name : value.propertyNames()) {
            require(java.util.Set.of("from", "to", "includeFrom", "includeTo").contains(name),
                    "the value of between allows from, to, includeFrom and includeTo only");
        }
        require(value.path("from").isNumber() && value.path("to").isNumber(), "between needs from and to numbers");
        require(!value.has("includeFrom") || value.get("includeFrom").isBoolean(), "includeFrom must be true or false");
        require(!value.has("includeTo") || value.get("includeTo").isBoolean(), "includeTo must be true or false");
        Literal.Num from = new Literal.Num(value.path("from").decimalValue());
        Literal.Num to = new Literal.Num(value.path("to").decimalValue());
        require(from.number().compareTo(to.number()) < 0, "the from of between must be smaller than its to");
        return new Condition(fieldRef, Operator.BETWEEN, null, null, List.of(), List.of(),
                new WorkflowDefinition.Range(from, value.path("includeFrom").asBoolean(true), to,
                        value.path("includeTo").asBoolean(true)));
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

    /** The literal results of a row; results written as {@code {"expression": ...}} are read by {@link #outputExpressions}. */
    private static JsonNode literalOutputs(JsonNode o) {
        require(o != null && o.isObject(), "decision output must be an object");
        tools.jackson.databind.node.ObjectNode literals = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        o.properties().forEach(e -> {
            if (!e.getValue().isObject()) literals.set(e.getKey(), e.getValue());
        });
        return literals;
    }

    /** S146: results of a row written as Orqix expressions, by result key (sorted). */
    private static Map<String, String> outputExpressions(JsonNode o) {
        Map<String, String> map = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : o.properties()) {
            JsonNode v = e.getValue();
            if (v.isObject()) {
                require(v.size() == 1 && v.path("expression").isString() && !v.get("expression").stringValue().isEmpty(),
                        "a decision result written as an expression has only its expression text");
                map.put(e.getKey(), v.get("expression").stringValue());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * A decision row condition: like {@link #readCondition(JsonNode, boolean)} with {@code in} and {@code between},
     * and a term may be an input entry written as an Orqix expression, {@code {"fieldRef": ..., "expression": ...}}
     * (S146), on its own or inside {@code all}/{@code any}.
     */
    public static Condition readRowCondition(JsonNode c) {
        require(c != null && c.isObject(), "condition must be an object");
        if (c.has("all") || c.has("any")) {
            String group = c.has("all") ? "all" : "any";
            require(c.size() == 1 && c.path(group).isArray() && c.path(group).size() >= 2 && c.path(group).size() <= 8,
                    "logical conditions require 2 to 8 atomic terms");
            List<Condition> terms = new ArrayList<>();
            for (JsonNode term : c.path(group)) {
                require(term.isObject() && !term.has("all") && !term.has("any"), "nested logical conditions are not supported");
                terms.add(rowTerm(term));
            }
            return new Condition(null, null, null, group, List.copyOf(terms));
        }
        return rowTerm(c);
    }

    private static Condition rowTerm(JsonNode c) {
        if (c.has("expression")) {
            require(c.size() == 2 && c.path("fieldRef").isString() && c.get("expression").isString()
                    && !c.get("expression").stringValue().isEmpty(),
                    "a decision row test written as an expression has its column fieldRef and its expression text only");
            return new Condition(c.get("fieldRef").stringValue(), null, null, null, List.of(), List.of(), null,
                    c.get("expression").stringValue());
        }
        return readCondition(c, true);
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

    /** S105: child field -> row column of a for-each over a list of rows, in a stable order. */
    private static java.util.Map<String, String> itemMapping(JsonNode mapping) {
        if (!mapping.isObject()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        mapping.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        return java.util.Collections.unmodifiableMap(out);
    }

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
                    f.path("formatMaximum").asString(null),f.path("incidentEditable").asBoolean(false),
                    List.of(), Map.of(), f));
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
                f.path("formatMinimum").asString(null), f.path("formatMaximum").asString(null),f.path("incidentEditable").asBoolean(false),
                List.of(), Map.of(), f);
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
        List<InputUi.FormRule> rules = new ArrayList<>();
        int ruleIndex = 0;
        for (JsonNode r : ui.path("rules")) {
            List<String> ruleFields = new ArrayList<>();
            r.path("fields").forEach(f -> ruleFields.add(f.stringValue()));
            rules.add(new InputUi.FormRule(ruleIndex++, r.path("id").asString(""), r.path("check").asString(""),
                    r.path("message").asString(""), List.copyOf(ruleFields)));
        }
        return new InputUi(List.copyOf(order), ui.has("widgets") ? stringMap(ui.get("widgets")) : Map.of(),
                conditionMap(ui.get("visibleWhen")), conditionMap(ui.get("requiredWhen")), List.copyOf(sections),
                "above".equals(ui.path("layout").path("helpText").asString("below")),
                ui.has("computed") ? stringMap(ui.get("computed")) : Map.of(), List.copyOf(rules));
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

    /**
     * The step's input mapping plus, for a for-each step (CG-E06, S138), the inputs that receive the current item:
     * {@code /input/<list>/*} for a list of single values, {@code /input/<list>/*}{@code /<column>} for a row column.
     * An explicit mapping of the same input wins (ForEachRules reports the clash).
     */
    private static Map<String, String> inputsWithItem(JsonNode c) {
        Map<String, String> mapping = stringMap(c.get("inputMapping"));
        JsonNode f = c.path("forEach");
        if (!f.isObject()) {
            return mapping;
        }
        Map<String, String> all = new TreeMap<>(mapping);
        String list = f.get("itemsRef").stringValue() + "/*";
        if (f.has("itemInput")) {
            all.putIfAbsent(f.get("itemInput").stringValue(), list);
        }
        for (Map.Entry<String, JsonNode> e : f.path("itemMapping").properties()) {
            all.putIfAbsent(e.getKey(), list + "/" + e.getValue().stringValue());
        }
        return Collections.unmodifiableMap(all);
    }

    /** S152: the AI reviewer of an approval step. */
    private static Node.AiReviewer aiReviewer(JsonNode r) {
        return new Node.AiReviewer(r.get("skill").get("key").stringValue(), r.get("skill").get("version").intValue(),
                r.path("autonomy").asString(Node.AiTask.SUGGEST),
                r.has("minConfidence") ? r.get("minConfidence").decimalValue() : Node.AiReviewer.DEFAULT_MIN_CONFIDENCE,
                r.path("reviewSamplePercent").asInt(Node.AiTask.DEFAULT_REVIEW_PERCENT), stringMap(r.get("inputs")),
                r.has("redaction") ? stringMap(r.get("redaction")) : Map.of(), r);
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
