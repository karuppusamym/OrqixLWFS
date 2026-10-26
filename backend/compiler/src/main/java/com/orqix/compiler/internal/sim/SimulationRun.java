package com.orqix.compiler.internal.sim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Compilers;
import com.orqix.compiler.ConditionMatcher;
import com.orqix.compiler.DecisionEvaluator;
import com.orqix.compiler.Severity;
import com.orqix.compiler.SimulationContext;
import com.orqix.compiler.SimulationResult;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.internal.Pipeline;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Durations;
import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Enums.Operator;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;
import com.orqix.compiler.internal.rules.Connections;
import com.orqix.compiler.internal.rules.Graph;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One dry run of one workflow (S4 section 13).
 * <ul>
 * <li>The graph is acyclic, so the walk is a depth-first traversal; the first transition (canonical order) that
 * matches is taken. A parallel split walks every branch in canonical order up to its join; a branch that reaches a
 * Finish step ends the whole run (the engine terminates the other branches), a branch that stops for attention does
 * not stop its siblings.</li>
 * <li>Decisions and conditions use {@link ConditionMatcher} and {@link DecisionEvaluator}, the same code the runtime
 * uses. A value the test cannot supply is reported as a stop that needs attention, as the engine would.</li>
 * <li>Nothing waits: waits are noted, timers fire only when the scenario says so.</li>
 * </ul>
 * Every sentence has a twin in frontend/src/mocks/simulator.ts; examples/fixtures/simulation holds golden traces both
 * implementations must reproduce.
 */
final class SimulationRun {

    static final int MAX_ENTRIES = 400;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    static final String LIMITS = "This is a dry run of the workflow as designed, not the workflow engine. It does not"
            + " call real systems, check that connections work, that people are in the groups, or how long anything"
            + " takes.";

    private enum Flow { ARRIVED, ENDED, STALLED }

    private final DefaultWorkflowSimulator owner;
    private final DefaultWorkflowSimulator.Budget budget;
    private final WorkflowDefinition def;
    private final Graph graph;
    private final Connections connections;
    private final String definitionJson;
    private final Map<String, Object> input;
    private final JsonNode inputValues;
    private final JsonNode scenario;
    private final SimulationContext context;
    private final CompileContext workspace;
    private final int depth;
    private final ValidationReport report;
    private final List<ValidationIssue> tolerated;

    private final Map<String, Map<String, Object>> steps = new HashMap<>();
    private final List<ObjectNode> trace = new ArrayList<>();
    private final Set<String> visited = new LinkedHashSet<>();
    private final Set<String> taken = new LinkedHashSet<>();
    private final Set<String> matchedRules = new LinkedHashSet<>();
    private final List<ObjectNode> warnings = new ArrayList<>();
    private String lane;
    private Node endNode;
    /** Where a raised error continues (S59); consumed by the walk. */
    private Node jump;
    private ObjectNode attention;

    SimulationRun(DefaultWorkflowSimulator owner, Pipeline.Outcome outcome, String definitionJson, JsonNode input,
                  JsonNode scenario, SimulationContext context, int depth, ValidationReport report,
                  List<ValidationIssue> tolerated, DefaultWorkflowSimulator.Budget budget) {
        this.owner = owner;
        this.budget = budget;
        this.def = outcome.definition();
        this.graph = outcome.analysis().graph();
        this.connections = outcome.analysis().connections();
        this.definitionJson = definitionJson;
        this.inputValues = input;
        this.input = new LinkedHashMap<>();
        input.properties().forEach(e -> this.input.put(e.getKey(), DefaultWorkflowSimulator.plain(e.getValue())));
        this.scenario = scenario;
        this.context = context;
        this.workspace = context.workspace();
        this.depth = depth;
        this.report = report;
        this.tolerated = tolerated;
    }

    // ------------------------------------------------------------------ run

    ObjectNode execute() {
        collectWarnings();
        walk(graph.starts().get(0), null);
        ObjectNode result = F.objectNode();
        result.set("outcome", outcome());
        result.set("outputs", outputs());
        ArrayNode traceArray = result.putArray("trace");
        trace.forEach(traceArray::add);
        scenarioWarnings();
        ArrayNode warningArray = result.putArray("warnings");
        warnings.forEach(warningArray::add);
        result.set("coverage", coverage());
        result.put("limits", LIMITS);
        return result;
    }

    private ObjectNode outcome() {
        ObjectNode o = F.objectNode();
        if (endNode != null) {
            Node.End end = (Node.End) endNode;
            o.put("status", "finished");
            o.put("businessOutcome", end.businessOutcome());
            o.put("outcomeKind", end.outcomeKind());
            o.put("endStepId", end.id());
            o.putNull("attention");
            o.put("summary", "The request would finish with the outcome " + Text.quote(end.businessOutcome()) + " ("
                    + kindWord(end.outcomeKind()) + ").");
        } else {
            o.put("status", "needsAttention");
            o.putNull("businessOutcome");
            o.putNull("outcomeKind");
            o.putNull("endStepId");
            o.set("attention", attention == null ? F.nullNode() : attention);
            o.put("summary", "The request would stop and need attention"
                    + (attention == null ? "." : " at " + Text.quote(attention.path("stepLabel").asString()) + "."));
        }
        return o;
    }

    private static String kindWord(String kind) {
        return switch (kind) {
            case "success" -> "successful";
            default -> kind;
        };
    }

    private ObjectNode outputs() {
        ObjectNode o = F.objectNode();
        if (endNode == null) {
            return o;
        }
        def.outputMapping().forEach((key, entry) -> {
            Object value = resolve(entry.fieldRef());
            if (value != null) {
                o.set(key, DefaultWorkflowSimulator.json(value));
            }
        });
        return o;
    }

    // ------------------------------------------------------------------ walking

    private Flow walk(Node from, String stopJoin) {
        Node node = from;
        while (true) {
            if (trace.size() >= MAX_ENTRIES || !budget.visit()) {
                stall(node, "TEST_LIMIT", "The test reached its bounded step budget (400 per workflow, 1000 across children).");
                return Flow.STALLED;
            }
            if (node.kind() == NodeKind.PARALLEL_JOIN && node.id().equals(stopJoin)) {
                return Flow.ARRIVED;
            }
            visited.add(node.id());
            Transition next = step(node);
            if (next == null && jump != null) {
                // A raised error continues where its section or handler says (S59).
                node = jump;
                jump = null;
                continue;
            }
            if (next == null) {
                return endNode != null ? Flow.ENDED : Flow.STALLED;
            }
            taken.add(next.id());
            node = graph.node(next.to());
        }
    }

    /** @return the transition taken, or null when the run ended or stalled here */
    private Transition step(Node node) {
        return switch (node) {
            case Node.Start n -> {
                Transition t = next(n, Route.NEXT);
                add(n, "The request is submitted with the answers you entered.", F.objectNode(), null, t);
                yield t;
            }
            case Node.Approval n -> approval(n);
            case Node.Decision n -> decision(n);
            case Node.Branch n -> branch(n);
            case Node.Http n -> http(n);
            case Node.GenerateDocument n -> {
                ObjectNode inputs = F.objectNode();
                boolean missing = false;
                for (var entry : n.inputMapping().entrySet()) {
                    Object value = resolve(entry.getValue());
                    if (value == null) { missing = true; break; }
                    inputs.set(entry.getKey(), DefaultWorkflowSimulator.json(value));
                }
                if (missing) {
                    stall(n, "TEST_DOCUMENT_FIELD_MISSING", "A mapped document field is missing from this sample request.");
                    yield null;
                }
                Transition t = next(n, Route.NEXT);
                ObjectNode detail = F.objectNode().put("format", n.format()).put("mappedFieldCount", n.inputMapping().size());
                add(n, "The workflow would generate a " + n.format().toUpperCase(java.util.Locale.ROOT)
                        + " document from its pinned template. This test creates no file.", detail, null, t);
                yield t;
            }
            case Node.ExternalTask n -> externalTask(n);
            case Node.SendAdapter n -> sendAdapter(n);
            case Node.Notification n -> notification(n);
            case Node.ParallelSplit n -> split(n);
            case Node.ParallelJoin n -> {
                Transition t = next(n, Route.NEXT);
                add(n, "The parallel work is joined here and the request continues.", F.objectNode(), null, t);
                yield t;
            }
            case Node.Wait n -> wait(n);
            case Node.WaitForMessage n -> waitForMessage(n);
            case Node.StartWorkflow n -> startWorkflow(n);
            case Node.RaiseError n -> raiseError(n);
            case Node.SendSignal n -> {
                Transition t = next(n, Route.NEXT);
                ObjectNode d = F.objectNode().put("signal", n.signal());
                add(n, Text.quote(n.label()) + " sends the signal " + Text.quote(n.signal())
                        + " to the steps of this request that wait for it. This test does not start signal handlers.",
                        d, null, t);
                yield t;
            }
            case Node.WaitForSignal n -> {
                Transition t = next(n, Route.NEXT);
                ObjectNode d = F.objectNode().put("signal", n.signal());
                add(n, Text.quote(n.label()) + " waits for the signal " + Text.quote(n.signal())
                        + ". The test assumes it arrives.", d, null, t);
                yield t;
            }
            case Node.Compensate n -> compensate(n);
            case Node.End n -> {
                endNode = n;
                ObjectNode d = F.objectNode();
                d.put("businessOutcome", n.businessOutcome());
                d.put("outcomeKind", n.outcomeKind());
                add(n, "The request finishes: " + Text.quote(n.businessOutcome()) + " (" + kindWord(n.outcomeKind())
                        + ").", d, null, null);
                yield null;
            }
            default -> {
                stallWith(node, "This kind of step (" + node.kind().businessLabel() + ") cannot be tested yet.",
                        F.objectNode(), "UNSUPPORTED_STEP", "The test does not know how to run this kind of step.");
                yield null;
            }
        };
    }

    private Transition next(Node n, Route on) {
        for (Transition t : graph.outgoing(n)) {
            if (t.on() == on) {
                return t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ approval

    private Transition approval(Node.Approval n) {
        JsonNode s = scenario.path("approvals").path(n.id());
        String choice = s.path("outcome").asString("approve");
        if (!Set.of("approve", "reject", "timeout").contains(choice)) {
            throw new IllegalArgumentException("approvals." + n.id() + ".outcome must be approve, reject or timeout");
        }
        CompileContext.GroupInfo group = workspace.groups().get(n.candidateGroupRef());
        String groupName = group != null ? group.name()
                : n.candidateGroupRef() == null || n.candidateGroupRef().isBlank() ? "nobody (no group is chosen)"
                : n.candidateGroupRef();
        ObjectNode d = F.objectNode();
        d.put("group", n.candidateGroupRef());
        d.put("groupName", groupName);
        if (n.dueAfterHours() != null) {
            d.put("dueAfterHours", n.dueAfterHours());
        } else {
            d.putNull("dueAfterHours");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Transition t;
        String sentence = Text.quote(n.label()) + " goes to " + groupName + ".";
        if (n.multiInstance()) {
            d.put("approvers", n.approvers());
            sentence = Text.quote(n.label()) + " goes to " + n.approvers() + " different people in " + groupName
                    + ", and all of them must approve.";
        }
        String note = null;
        if ("timeout".equals(choice) && next(n, Route.TIMED_OUT) == null) {
            note = "This step has no time limit, so the test treats it as approved.";
            choice = "approve";
        }
        switch (choice) {
            case "reject" -> {
                String reason = s.path("reason").asString(n.rejectReasonRequired() ? "Sample rejection reason" : null);
                out.put("decision", "rejected");
                if (reason != null) {
                    out.put("reason", reason);
                }
                t = next(n, Route.REJECTED);
                sentence += (n.multiInstance() ? " In this test the first approver rejects it, so the others are"
                        + " cancelled" : " In this test they reject it") + (reason == null ? "." : " with the reason "
                        + Text.quote(reason) + ".");
            }
            case "timeout" -> {
                out.put("decision", "timedOut");
                t = next(n, Route.TIMED_OUT);
                long minutes = Durations.minutes(n.timeoutAfter());
                sentence += " In this test nobody answers within "
                        + (minutes > 0 ? Durations.describe(minutes) : "the time limit")
                        + ", so the time limit fires and the request takes the timed-out route.";
            }
            default -> {
                out.put("decision", "approved");
                t = next(n, Route.APPROVED);
                sentence += n.multiInstance() ? " In this test all " + n.approvers() + " approve."
                        : " In this test they approve.";
            }
        }
        if (!"timedOut".equals(out.get("decision"))) {
            out.put("decidedBy", "Test approver");
        }
        d.put("decision", String.valueOf(out.get("decision")));
        if (out.containsKey("reason")) {
            d.put("reason", String.valueOf(out.get("reason")));
        }
        if (note != null) {
            d.put("note", note);
            sentence += " " + note;
        }
        steps.put(n.id(), out);
        add(n, sentence, d, out, t);
        return t;
    }

    // ------------------------------------------------------------------ decision

    private Transition decision(Node.Decision n) {
        DecisionTable table = null;
        for (DecisionTable candidate : def.decisions()) {
            if (candidate.key().equals(n.decisionRef())) {
                table = candidate;
            }
        }
        ObjectNode d = F.objectNode();
        if (table == null) {
            String message = "The decision table " + Text.quote(n.decisionRef()) + " does not exist.";
            stallWith(n, message, d, "INTERNAL_ERROR", message);
            return null;
        }
        d.put("decisionKey", table.key());
        d.put("version", table.version());
        Map<String, Object> values = new LinkedHashMap<>();
        // Dated rows are judged by the day the simulated request is submitted.
        java.time.LocalDate asOf = table.dated() ? context.today() : null;
        if (asOf != null) {
            d.put("asOf", asOf.toString());
        }
        ArrayNode inputs = d.putArray("inputs");
        for (DecisionTable.Row row : table.rows()) {
            for(Condition term:row.when().compound()?row.when().terms():List.of(row.when())) {
            String ref = term.fieldRef();
            if (!values.containsKey(ref)) {
                Object v = resolve(ref);
                values.put(ref, v);
                ObjectNode in = inputs.addObject();
                in.put("fieldRef", ref);
                in.put("label", info(ref).label());
                in.set("value", DefaultWorkflowSimulator.json(v));
                in.put("shown", v == null ? "no value" : Text.value(v, info(ref).money()));
            }
            }
        }
        DecisionEvaluator.Result result;
        try {
            Map<String, Object> evaluated = new LinkedHashMap<>(values);
            if (asOf != null) {
                evaluated.put(com.orqix.compiler.internal.eval.DefaultDecisionEvaluator.AS_OF, asOf);
            }
            result = Compilers.decisionEvaluator().evaluate(definitionJson, table.key(), evaluated);
        } catch (IllegalArgumentException e) {
            String missing = firstMissing(values);
            String message = Text.quote(n.label()) + " needs " + (missing == null ? "a value it does not have"
                    : info(missing).label()) + ", but the test has no usable value for it.";
            stallWith(n, message, d, "INTERNAL_ERROR", message);
            return null;
        }
        ArrayNode rows = d.putArray("rows");
        Integer firstMatch = null;
        for (DecisionTable.Row row : table.rows()) {
            Boolean matched = row.when().compound()?evaluate(row.when(),F.arrayNode()):matches(row.when(), values.get(row.when().fieldRef()));
            if (asOf != null && !row.appliesOn(asOf)) {
                matched = Boolean.FALSE; // outside its effective window the row never applies
            }
            if (Boolean.TRUE.equals(matched) && firstMatch == null) {
                firstMatch = row.index();
            }
            ObjectNode r = rows.addObject();
            r.put("row", row.index() + 1);
            r.put("condition", conditionText(row.when(), false, null));
            if (row.effective() != null) {
                r.put("effective", (row.effective().from() == null ? "until " : "from " + row.effective().from()
                        + (row.effective().to() == null ? "" : " to ")) + (row.effective().to() == null ? ""
                        : row.effective().to().toString()));
            }
            if (matched == null) {
                r.putNull("matched");
            } else {
                r.put("matched", matched);
            }
            r.put("selected", result.matchedRow() != null && result.matchedRow() == row.index());
            r.set("outputs", literalMap(row.then()));
        }
        if (!Objects.equals(firstMatch, result.matchedRow())) {
            throw new IllegalStateException("The decision evaluator and the row check disagree for " + table.key());
        }
        d.put("usedDefault", result.usedDefault());
        if (result.matchedRow() == null) {
            d.putNull("matchedRow");
        } else {
            d.put("matchedRow", result.matchedRow() + 1);
        }
        d.set("outputs", DefaultWorkflowSimulator.json(result.output()));
        matchedRules.add(table.key() + "#" + (result.matchedRow() == null ? "default" : result.matchedRow()));
        Map<String, Object> out = new LinkedHashMap<>(result.output());
        steps.put(n.id(), out);
        String outputsText = outputsText(out);
        String sentence;
        if (result.matchedRow() == null) {
            sentence = Text.quote(n.label()) + ": no rule applies, so the \"otherwise\" result is used: " + outputsText
                    + ".";
        } else {
            DecisionTable.Row row = table.rows().get(result.matchedRow());
            sentence = Text.quote(n.label()) + ": rule " + (row.index() + 1) + " applies because "
                    + conditionText(row.when(), true, values.get(row.when().fieldRef())) + ", so " + outputsText + ".";
        }
        Transition t = next(n, Route.NEXT);
        add(n, sentence, d, out, t);
        return t;
    }

    private String firstMissing(Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() == null) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String outputsText(Map<String, Object> out) {
        List<String> parts = new ArrayList<>();
        out.forEach((k, v) -> parts.add(k + " = " + Text.value(v, false)));
        return String.join(", ", parts);
    }

    private static JsonNode literalMap(Map<String, Literal> literals) {
        ObjectNode o = F.objectNode();
        literals.forEach((k, v) -> o.set(k, DefaultWorkflowSimulator.json(v.value())));
        return o;
    }

    // ------------------------------------------------------------------ branch

    private Transition branch(Node.Branch n) {
        ObjectNode d = F.objectNode();
        ArrayNode routes = d.putArray("routes");
        Transition chosen = null;
        String error = null;
        for (Transition t : graph.outgoing(n)) {
            if (t.on() != Route.CONDITION || t.condition() == null) {
                continue;
            }
            ObjectNode r = routes.addObject();
            r.put("transitionId", t.id());
            r.put("label", routeName(t));
            r.put("condition", conditionText(t.condition(), false, null));
            if (chosen != null || error != null) {
                r.putNull("matched");
                continue;
            }
            try {
                ArrayNode terms = F.arrayNode();
                boolean matched = evaluate(t.condition(), terms);
                r.put("matched", matched);
                if (t.condition().compound()) {
                    r.set("terms", terms);
                }
                if (matched) {
                    chosen = t;
                }
            } catch (IllegalArgumentException e) {
                r.putNull("matched");
                error = e.getMessage();
            }
        }
        if (error != null) {
            String message = Text.quote(n.label()) + " cannot choose a path: " + error;
            stallWith(n, message, d, "INTERNAL_ERROR", message);
            return null;
        }
        String sentence;
        if (chosen != null) {
            sentence = Text.quote(n.label()) + ": takes the route " + Text.quote(routeName(chosen)) + " because "
                    + conditionText(chosen.condition(), true, null) + ".";
        } else {
            chosen = next(n, Route.DEFAULT);
            sentence = Text.quote(n.label()) + ": takes the route "
                    + Text.quote(chosen == null ? "Otherwise" : routeName(chosen))
                    + " because none of the other routes applies.";
        }
        if (chosen == null) {
            d.putNull("chosen");
        } else {
            d.put("chosen", chosen.id());
        }
        if (chosen == null) {
            stallWith(n, sentence, d, "INTERNAL_ERROR", "The step has no route for this case.");
            return null;
        }
        add(n, sentence, d, null, chosen);
        return chosen;
    }

    /** Evaluates a condition with the runtime's short-circuit rules; throws IllegalArgumentException for a missing value. */
    private boolean evaluate(Condition c, ArrayNode terms) {
        if (!c.compound()) {
            return atom(c, null);
        }
        boolean all = "all".equals(c.group());
        boolean result = all;
        boolean decided = false;
        for (Condition term : c.terms()) {
            ObjectNode tn = terms.addObject();
            tn.put("text", conditionText(term, false, null));
            if (decided) {
                tn.putNull("matched");
                continue;
            }
            boolean m = atom(term, tn);
            tn.put("matched", m);
            if (all && !m) {
                result = false;
                decided = true;
            } else if (!all && m) {
                result = true;
                decided = true;
            }
        }
        return result;
    }

    private boolean atom(Condition c, ObjectNode record) {
        Object actual = resolve(c.fieldRef());
        if (actual == null) {
            throw new IllegalArgumentException(info(c.fieldRef()).label() + " has no value in this test.");
        }
        if (record != null) {
            record.set("value", DefaultWorkflowSimulator.json(actual));
        }
        try {
            return ConditionMatcher.matches(c.operator().json(), actual, c.value().value());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(info(c.fieldRef()).label() + " cannot be compared like that.");
        }
    }

    private Boolean matches(Condition c, Object actual) {
        if (actual == null) {
            return null;
        }
        try {
            return ConditionMatcher.matches(c.operator().json(), actual, c.value().value());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String routeName(Transition t) {
        if (t.label() != null && !t.label().isBlank()) {
            return t.label();
        }
        if (t.on() == Route.DEFAULT) {
            return "Otherwise";
        }
        return t.condition() == null ? t.id() : conditionText(t.condition(), false, null);
    }

    // ------------------------------------------------------------------ http

    private Transition http(Node.Http n) {
        JsonNode s = scenario.path("calls").path(n.id());
        String failure = s.path("failure").asString(null);
        if (failure != null && !Set.of("rejected", "unavailable", "unknownOutcome").contains(failure)) {
            throw new IllegalArgumentException("calls." + n.id() + ".failure must be rejected, unavailable or"
                    + " unknownOutcome");
        }
        Connections.Pinned pinned = connections.pinned(n.connectionRef());
        boolean registered = pinned != null && pinned.status() == Connections.Status.OK;
        JsonNode op = connections.operation(n.connectionRef(), n.operation());
        String connName = registered ? pinned.definition().path("name").asString(n.connectionRef())
                : n.connectionRef();
        boolean agent = registered && "agent".equals(pinned.definition().path("kind").asString(""));
        String opLabel = op == null ? n.operation() : op.path("label").asString(n.operation());
        String effect = op == null ? "nonIdempotent" : op.path("effectClass").asString("nonIdempotent");
        ObjectNode d = F.objectNode();
        d.put("connection", n.connectionRef());
        d.put("connectionName", connName);
        d.put("operation", n.operation());
        d.put("operationLabel", opLabel);
        d.put("agent", agent);
        d.put("effectClass", effect);
        ObjectNode request = d.putObject("request");
        new TreeMap<>(n.inputMapping()).forEach((field, ref) -> request.set(field,
                DefaultWorkflowSimulator.json(resolve(ref))));
        String callText = Text.quote(n.label()) + (agent ? " asks the assistant " : " calls ") + connName + " ("
                + opLabel + ").";
        if (failure != null) {
            String reason;
            String what;
            String consequence;
            switch (failure) {
                case "rejected" -> {
                    reason = "EFFECT_REJECTED";
                    what = "In this test the system refuses the request.";
                    consequence = "Nothing was done, so the request stops here and needs attention. Someone must fix"
                            + " the cause and retry.";
                }
                case "unavailable" -> {
                    reason = "RETRIES_EXHAUSTED";
                    what = "In this test the system does not answer.";
                    consequence = "Orqix would try " + Text.count(def.execution().maxAttempts(), "time") + ", "
                            + Text.count(def.execution().retryDelaySeconds(), "second") + " apart, and then the"
                            + " request needs attention until someone retries.";
                }
                default -> {
                    if ("readOnly".equals(effect)) {
                        reason = "RESPONSE_INVALID";
                        what = "In this test the system answers with something that cannot be used.";
                        consequence = "The request needs attention. Someone must fix the connection and retry.";
                    } else {
                        reason = "EFFECT_UNKNOWN";
                        what = "In this test the answer is lost, so nobody knows whether it was done.";
                        consequence = "The request needs attention. Someone must check the other system before"
                                + " retrying.";
                    }
                }
            }
            d.put("failure", failure);
            if (("EFFECT_REJECTED".equals(reason) || "RESPONSE_INVALID".equals(reason)) && next(n, Route.FAILED) != null) {
                return knownFailure(n, d, callText + " " + what, reason);
            }
            if (("EFFECT_REJECTED".equals(reason) || "RESPONSE_INVALID".equals(reason)) && n.raiseOnFailure() != null) {
                return raiseFromCall(n, d, callText + " " + what, reason);
            }
            stallWith(n, callText + " " + what + " " + consequence, d, reason, what + " " + consequence);
            return null;
        }
        JsonNode response = s.path("result");
        boolean synthesized = !response.isObject();
        if (synthesized) {
            response = op != null && op.path("responseSchema").isObject() ? sample(op.path("responseSchema"), "")
                    : null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        boolean missing = false;
        for (Map.Entry<String, String> e : n.resultMapping().entrySet()) {
            JsonNode value = response == null ? null : pointer(response, e.getValue());
            if (response == null) {
                out.put(e.getKey(), "sample-" + e.getKey());
            } else if (value == null || value.isNull() || value.isObject() || value.isArray()) {
                missing = true;
            } else {
                out.put(e.getKey(), DefaultWorkflowSimulator.plain(value));
            }
        }
        if (missing) {
            boolean readOnly = "readOnly".equals(effect);
            String what = "In this test the system answers without all the information this step needs.";
            String consequence = readOnly ? "The request needs attention. Someone must fix the connection and retry."
                    : "The request needs attention. Someone must check the other system before retrying.";
            d.put("failure", "unknownOutcome");
            if (readOnly && next(n, Route.FAILED) != null) {
                return knownFailure(n, d, callText + " " + what, "RESPONSE_INVALID");
            }
            if (readOnly && n.raiseOnFailure() != null) {
                return raiseFromCall(n, d, callText + " " + what, "RESPONSE_INVALID");
            }
            stallWith(n, callText + " " + what + " " + consequence, d, readOnly ? "RESPONSE_INVALID" : "EFFECT_UNKNOWN",
                    what + " " + consequence);
            return null;
        }
        d.put("sampleAnswer", synthesized);
        d.set("result", DefaultWorkflowSimulator.json(out));
        steps.put(n.id(), out);
        Transition t = next(n, Route.NEXT);
        String returns = out.isEmpty() ? "It succeeds." : "In this test it succeeds and returns "
                + outputsText(out) + ".";
        add(n, callText + " " + returns, d, out, t);
        return t;
    }

    /** Only known rejections and unusable read-only answers take the authored failure route. */
    private Transition knownFailure(Node.Http n, ObjectNode details, String description, String reason) {
        String kind = "EFFECT_REJECTED".equals(reason) ? "rejected" : "invalidResponse";
        String message = "rejected".equals(kind) ? "The external system rejected this request."
                : "The read-only system returned an invalid response.";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("failureKind", kind);
        out.put("failureHttpStatus", "rejected".equals(kind) ? 400 : 0);
        out.put("failureMessage", message);
        steps.put(n.id(), out);
        details.put("routed", true);
        Transition route = next(n, Route.FAILED);
        add(n, description + " The workflow takes its authored failure path.", details, out, route);
        return route;
    }

    /**
     * A known failure of a call set to raise an error on failure (S71): the section holding it stops and the request
     * continues at the section's error route (the compiler guarantees the route catches the code).
     */
    private Transition raiseFromCall(Node.Http n, ObjectNode details, String description, String reason) {
        WorkflowDefinition.Subprocess scope = graph.scopeOf(n);
        Node target = scope == null || scope.errorTo() == null ? null : graph.node(scope.errorTo());
        details.put("errorCode", n.raiseOnFailure());
        if (target == null) {
            stallWith(n, description + " It raises the error " + Text.quote(n.raiseOnFailure()) + ", which nothing"
                    + " catches.", details, "ERROR_NOT_CAUGHT", "The raised error is not caught.");
            return null;
        }
        details.put("failureReason", reason);
        details.put("continuesAt", target.id());
        add(n, description + " It raises the error " + Text.quote(n.raiseOnFailure()) + ": the section "
                + Text.quote(scope.label()) + " stops and the request continues at " + Text.quote(target.label()) + ".",
                details, null, null);
        jump = target;
        return null;
    }

    private static JsonNode pointer(JsonNode body, String pointer) {
        JsonNode current = body;
        for (String token : JsonPointers.parse(pointer)) {
            current = current == null ? null : current.get(token);
        }
        return current;
    }

    /** A deterministic sample document for a JSON schema object or scalar. */
    static JsonNode sample(JsonNode schema, String key) {
        String type = schema.path("type").asString("string");
        switch (type) {
            case "object" -> {
                ObjectNode o = F.objectNode();
                List<String> names = new ArrayList<>(schema.path("properties").propertyNames());
                Collections.sort(names);
                for (String name : names) {
                    o.set(name, sample(schema.path("properties").get(name), name));
                }
                return o;
            }
            case "array" -> {
                return F.arrayNode();
            }
            case "boolean" -> {
                return F.booleanNode(false);
            }
            case "integer", "number" -> {
                BigDecimal v = BigDecimal.ONE;
                if (schema.has("minimum") && schema.get("minimum").decimalValue().compareTo(v) > 0) {
                    v = schema.get("minimum").decimalValue();
                }
                if (schema.has("maximum") && schema.get("maximum").decimalValue().compareTo(v) < 0) {
                    v = schema.get("maximum").decimalValue();
                }
                return DefaultWorkflowSimulator.json(v);
            }
            default -> {
                if (schema.has("enum") && schema.get("enum").size() > 0) {
                    return F.stringNode(schema.get("enum").get(0).asString());
                }
                if ("date".equals(schema.path("format").asString(""))) {
                    return F.stringNode("2026-01-15");
                }
                if (schema.path("pattern").isString()) {
                    String example = com.orqix.compiler.FormPattern.problem(schema.get("pattern").asString()) == null
                            ? com.orqix.compiler.FormPattern.compile(schema.get("pattern").asString()).example() : null;
                    if (example != null) {
                        return F.stringNode(example);
                    }
                }
                String text = "sample-" + key;
                if (schema.has("maxLength") && text.length() > schema.get("maxLength").asInt()) {
                    text = text.substring(0, Math.max(0, schema.get("maxLength").asInt()));
                }
                return F.stringNode(text);
            }
        }
    }

    // ------------------------------------------------------------------ notification and wait

    // ------------------------------------------------------------------ errors and undo (S59)

    private Transition raiseError(Node.RaiseError n) {
        ObjectNode d = F.objectNode().put("errorCode", n.errorCode());
        WorkflowDefinition.Subprocess scope = graph.scopeOf(n);
        Node target = null;
        String where = null;
        if (scope != null && scope.errorTo() != null
                && (scope.errorCode() == null || scope.errorCode().equals(n.errorCode()))) {
            target = graph.node(scope.errorTo());
            where = "the section " + Text.quote(scope.label()) + " stops and the request continues at "
                    + Text.quote(target.label()) + ".";
        } else {
            for (WorkflowDefinition.Subprocess s : def.subprocesses()) {
                if (s.event() && "error".equals(s.triggerType())
                        && (s.triggerErrorCode() == null || s.triggerErrorCode().equals(n.errorCode()))) {
                    target = graph.entry(s);
                    where = "the handler " + Text.quote(s.label()) + " takes over and the rest of the request stops.";
                    break;
                }
            }
        }
        if (target == null) {
            stallWith(n, Text.quote(n.label()) + " raises the error " + Text.quote(n.errorCode())
                    + ", which nothing catches.", d, "ERROR_NOT_CAUGHT", "The raised error is not caught.");
            return null;
        }
        d.put("continuesAt", target.id());
        add(n, Text.quote(n.label()) + " raises the error " + Text.quote(n.errorCode()) + ": " + where, d, null, null);
        jump = target;
        return null;
    }

    private Transition compensate(Node.Compensate n) {
        List<String> undone = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Node> calls = new ArrayList<>();
        for (String id : visited) {
            Node v = graph.node(id);
            if (v instanceof Node.Http h && !h.undoHandler() && steps.containsKey(id)
                    && !steps.get(id).containsKey("failureKind")) {
                calls.add(v);
            }
        }
        Collections.reverse(calls);
        for (Node call : calls) {
            for (Node other : def.nodes()) {
                if (other instanceof Node.Http h && h.undoHandler() && call.id().equals(h.compensates())) {
                    undone.add(h.id());
                    labels.add(Text.quote(h.label()));
                }
            }
        }
        ObjectNode d = F.objectNode();
        ArrayNode list = d.putArray("undoCalls");
        undone.forEach(list::add);
        Transition t = next(n, Route.NEXT);
        add(n, labels.isEmpty() ? Text.quote(n.label()) + ": no call that can be undone has completed in this test,"
                + " so nothing is undone."
                : Text.quote(n.label()) + " would run " + String.join(", ", labels)
                + ", most recent call first. This test calls no system.", d, null, t);
        return t;
    }

    private Transition notification(Node.Notification n) {
        ObjectNode d = F.objectNode();
        d.put("channel", n.channel());
        d.put("recipient", n.recipient());
        d.put("message", n.message());
        Transition t = next(n, Route.NEXT);
        add(n, Text.quote(n.label()) + ": the requester would get this in-app message: " + Text.quote(n.message()), d,
                null, t);
        return t;
    }

    private Transition wait(Node.Wait n) {
        ObjectNode d = F.objectNode();
        String sentence;
        if (n.untilField() != null) {
            Object value = resolve(n.untilField());
            d.put("untilField", n.untilField());
            d.put("atTime", n.time());
            try {
                LocalDate date = LocalDate.parse(String.valueOf(value));
                d.put("until", date + " " + n.time() + " UTC");
                sentence = Text.quote(n.label()) + ": would wait until " + Text.date(date) + ", " + n.time()
                        + " UTC. The test skips the wait.";
                if (date.isBefore(context.today())) {
                    sentence = Text.quote(n.label()) + ": the date " + Text.date(date) + " has already passed, so"
                            + " the request would not wait.";
                }
            } catch (DateTimeParseException e) {
                sentence = Text.quote(n.label()) + ": would wait until a date that the test does not have. The test"
                        + " skips the wait.";
            }
        } else {
            long minutes = Durations.minutes(n.duration());
            d.put("duration", n.duration());
            sentence = Text.quote(n.label()) + ": would wait " + (minutes > 0 ? Durations.describe(minutes)
                    : String.valueOf(n.duration())) + ". The test skips the wait.";
        }
        Transition t = next(n, Route.NEXT);
        add(n, sentence, d, null, t);
        return t;
    }

    // ------------------------------------------------------------------ message wait

    /** A worker is not simulated: the test supplies its result in the settings, otherwise declared fields get sample values. */
    private Transition externalTask(Node.ExternalTask n) {
        ObjectNode inputs = F.objectNode();
        for (var entry : new TreeMap<>(n.inputMapping()).entrySet()) {
            Object value = resolve(entry.getValue());
            if (value == null) {
                stall(n, "TEST_EXTERNAL_INPUT_MISSING", "A value sent to the worker is missing from this sample request.");
                return null;
            }
            inputs.set(entry.getKey(), DefaultWorkflowSimulator.json(value));
        }
        JsonNode given = scenario.path("workers").path(n.id()).path("result");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Node.PayloadField field : n.result()) {
            JsonNode supplied = given.path(field.name());
            if (!supplied.isMissingNode() && !supplied.isNull()) {
                out.put(field.name(), supplied.isNumber() ? supplied.decimalValue() : supplied.isBoolean() ? (Object) supplied.booleanValue()
                        : supplied.asString());
                continue;
            }
            out.put(field.name(), switch (field.type()) {
                case NUMBER, INTEGER -> BigDecimal.ONE;
                case BOOLEAN -> Boolean.FALSE;
                case DATE -> "2026-01-15";
                default -> "sample-" + field.name();
            });
        }
        ObjectNode d = F.objectNode();
        d.put("topic", n.topic());
        d.set("input", inputs);
        d.set("result", DefaultWorkflowSimulator.json(out));
        Transition t = next(n, Route.NEXT);
        add(n, Text.quote(n.label()) + ": a worker for the topic " + Text.quote(n.topic())
                + " takes the job and returns its result. This test calls no worker; the result "
                + (given.isObject() ? "comes from the test settings." : "uses sample values."), d, null, t);
        return t;
    }

    /**
     * An adapter step is not executed by a test (S73): the values it would use are checked and the message is described,
     * but nothing is sent. Like the local stub of this build, a database read returns no rows.
     */
    private Transition sendAdapter(Node.SendAdapter n) {
        Map<String, String> values = new TreeMap<>();
        for (var entry : new TreeMap<>(n.inputMapping()).entrySet()) {
            Object value = resolve(entry.getValue());
            if (value == null) {
                stall(n, "TEST_ADAPTER_INPUT_MISSING", "A value this step sends is missing from this sample request.");
                return null;
            }
            values.put(entry.getKey(), value instanceof BigDecimal b ? b.toPlainString() : String.valueOf(value));
        }
        ObjectNode d = F.objectNode();
        d.put("adapter", n.adapterRef());
        d.put("adapterKind", n.adapterKind());
        String what = switch (n.adapterKind()) {
            case "email" -> {
                d.put("recipientCount", n.to().size());
                d.put("subject", com.orqix.compiler.AdapterMessages.render(n.subject(), values));
                yield "an email to " + n.to().size() + (n.to().size() == 1 ? " fixed recipient" : " fixed recipients");
            }
            case "teams" -> "a Teams message";
            case "kafka" -> {
                d.put("topic", n.topic());
                yield "a message to the topic " + Text.quote(n.topic());
            }
            default -> {
                d.put("parameterCount", values.size());
                yield "a database read";
            }
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", "stubbed");
        if ("jdbc".equals(n.adapterKind())) {
            out.put("rowCount", BigDecimal.ZERO);
        }
        steps.put(n.id(), out);
        d.set("result", DefaultWorkflowSimulator.json(out));
        Transition t = next(n, Route.NEXT);
        add(n, Text.quote(n.label()) + " would send " + what + " through the adapter " + Text.quote(n.adapterRef())
                + ". This test sends nothing; in this build adapters deliver only to a local test stub.", d, out, t);
        return t;
    }

    private Transition waitForMessage(Node.WaitForMessage n) {
        String choice = scenario.path("messages").path(n.id()).asString("arrives");
        if (!Set.of("arrives", "timeout").contains(choice)) {
            throw new IllegalArgumentException("messages." + n.id() + " must be arrives or timeout");
        }
        FieldInfo f = info(n.correlationField());
        Object key = resolve(n.correlationField());
        ObjectNode d = F.objectNode();
        d.put("message", n.message());
        d.put("correlationField", n.correlationField());
        d.put("correlation", f.label() + " (" + (key == null ? "no value" : Text.value(key, f.money())) + ")");
        String note = null;
        if ("timeout".equals(choice) && next(n, Route.TIMED_OUT) == null) {
            note = "This step has no time limit, so the test treats the message as arrived.";
            choice = "arrives";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Transition t;
        String sentence;
        if ("timeout".equals(choice)) {
            long minutes = Durations.minutes(n.timeoutAfter());
            d.put("arrived", false);
            t = next(n, Route.TIMED_OUT);
            sentence = Text.quote(n.label()) + ": the message " + Text.quote(n.message()) + " does not arrive within "
                    + (minutes > 0 ? Durations.describe(minutes) : "the time limit")
                    + ", so the time limit fires and the request takes the timed-out route.";
        } else {
            for (Node.PayloadField field : n.payload()) {
                out.put(field.name(), switch (field.type()) {
                    case NUMBER, INTEGER -> BigDecimal.ONE;
                    case BOOLEAN -> Boolean.FALSE;
                    case DATE -> "2026-01-15";
                    default -> "sample-" + field.name();
                });
            }
            d.put("arrived", true);
            d.set("payload", DefaultWorkflowSimulator.json(out));
            t = next(n, Route.NEXT);
            sentence = Text.quote(n.label()) + ": the message " + Text.quote(n.message()) + " arrives for "
                    + d.get("correlation").asString() + "."
                    + (out.isEmpty() ? "" : " In this test it carries " + outputsText(out) + ".");
        }
        if (note != null) {
            d.put("note", note);
            sentence += " " + note;
        }
        steps.put(n.id(), out);
        add(n, sentence, d, out, t);
        return t;
    }

    // ------------------------------------------------------------------ parallel

    private Transition split(Node.ParallelSplit n) {
        List<Transition> branches = graph.outgoing(n);
        ObjectNode d = F.objectNode();
        d.put("joinRef", n.joinRef());
        d.put("branches", branches.size());
        if (n.inclusive()) {
            branches = inclusiveSelection(n, d, branches);
            if (branches == null) {
                return null;
            }
        } else {
            add(n, Text.quote(n.label()) + ": starts " + (branches.size() == 1 ? "1 branch" : branches.size()
                    + " branches") + " at the same time.", d, null, null);
        }
        String saved = lane;
        boolean ended = false;
        boolean stalled = false;
        int index = 0;
        for (Transition t : branches) {
            index++;
            if (ended) {
                d.put("remainingBranchesStopped", true);
                break;
            }
            taken.add(t.id());
            lane = saved == null ? String.valueOf(index) : saved + "." + index;
            Flow f = walk(graph.node(t.to()), n.joinRef());
            if (f == Flow.ENDED) {
                ended = true;
            } else if (f == Flow.STALLED) {
                stalled = true;
            }
        }
        lane = saved;
        if (ended || stalled) {
            return null;
        }
        Node join = graph.node(n.joinRef());
        if (join == null) {
            return null;
        }
        visited.add(join.id());
        Transition t = next(join, Route.NEXT);
        add(join, Text.quote(join.label()) + ": all " + branches.size() + (n.inclusive() ? " started" : "")
                + " branches have finished, so the request continues.", F.objectNode(), null, t);
        return t;
    }

    /**
     * Inclusive split (S51): every path whose condition holds starts, in canonical order; the default path starts only
     * when none does. Records the evaluation like a choice does.
     *
     * @return the paths that start, or null when the test stalled here
     */
    private List<Transition> inclusiveSelection(Node.ParallelSplit n, ObjectNode d, List<Transition> all) {
        ArrayNode routes = d.putArray("routes");
        List<Transition> started = new ArrayList<>();
        String error = null;
        for (Transition t : all) {
            if (t.on() != Route.CONDITION || t.condition() == null) {
                continue;
            }
            ObjectNode r = routes.addObject();
            r.put("transitionId", t.id());
            r.put("label", routeName(t));
            r.put("condition", conditionText(t.condition(), false, null));
            try {
                ArrayNode terms = F.arrayNode();
                boolean matched = evaluate(t.condition(), terms);
                r.put("matched", matched);
                if (t.condition().compound()) {
                    r.set("terms", terms);
                }
                if (matched) {
                    started.add(t);
                }
            } catch (IllegalArgumentException e) {
                r.putNull("matched");
                error = e.getMessage();
                break;
            }
        }
        if (error != null) {
            String message = Text.quote(n.label()) + " cannot choose its paths: " + error;
            stallWith(n, message, d, "INTERNAL_ERROR", message);
            return null;
        }
        String sentence;
        if (started.isEmpty()) {
            Transition fallback = next(n, Route.DEFAULT);
            if (fallback == null) {
                String message = Text.quote(n.label()) + " has no default path for this case.";
                stallWith(n, message, d, "INTERNAL_ERROR", message);
                return null;
            }
            started.add(fallback);
            sentence = Text.quote(n.label()) + ": no condition applies, so only the default path "
                    + Text.quote(routeName(fallback)) + " starts.";
        } else {
            sentence = Text.quote(n.label()) + ": starts " + started.size() + " of " + all.size() + " paths ("
                    + String.join(", ", started.stream().map(t -> Text.quote(routeName(t))).toList())
                    + "), because their conditions apply.";
        }
        ArrayNode chosen = d.putArray("startedPaths");
        started.forEach(t -> chosen.add(t.id()));
        d.put("started", started.size());
        add(n, sentence, d, null, null);
        return started;
    }

    // ------------------------------------------------------------------ child workflows

    private record ChildOutcome(String label, String kind) {
    }

    private Transition startWorkflow(Node.StartWorkflow n) {
        JsonNode s = scenario.path("children").path(n.id());
        String childDefinition = context.childDefinitions().apply(n.workflowRef());
        JsonNode childRoot = null;
        if (childDefinition != null) {
            try {
                childRoot = StrictJson.parse(childDefinition);
            } catch (RuntimeException e) {
                childRoot = null;
            }
        }
        String childName = childRoot == null ? n.workflowRef() : childRoot.path("name").asString(n.workflowRef());
        List<ChildOutcome> outcomes = new ArrayList<>();
        if (childRoot != null) {
            for (JsonNode node : childRoot.path("nodes")) {
                if ("end".equals(node.path("type").asString(""))) {
                    ChildOutcome o = new ChildOutcome(node.path("config").path("businessOutcome").asString(),
                            node.path("config").path("outcomeKind").asString());
                    if (!outcomes.contains(o)) {
                        outcomes.add(o);
                    }
                }
            }
        }
        String untilOutcome = n.repeat() == null ? null : n.repeat().untilOutcome();
        ChildOutcome defaultOutcome = new ChildOutcome("Fulfilled", "success");
        if (!outcomes.isEmpty()) {
            defaultOutcome = outcomes.get(0);
            for (ChildOutcome o : outcomes) {
                if ("success".equals(o.kind())) {
                    defaultOutcome = o;
                    break;
                }
            }
            if (untilOutcome != null) {
                for (ChildOutcome o : outcomes) {
                    if (o.label().equals(untilOutcome)) {
                        defaultOutcome = o;
                    }
                }
            }
        }
        List<Object> items = null;
        int planned = 1;
        if (n.forEach() != null) {
            Object list = resolve(n.forEach().itemsRef());
            items = list instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
            planned = Math.min(items.size(), n.forEach().maxItems());
        } else if (n.repeat() != null) {
            planned = n.repeat().maxIterations();
        }
        JsonNode outcomeList = s.path("outcomes");
        boolean simulate = scenario.path("simulateChildren").asBoolean(false) && childDefinition != null
                && depth < DefaultWorkflowSimulator.MAX_CHILD_DEPTH;
        ObjectNode d = F.objectNode();
        d.put("workflow", n.workflowRef());
        d.put("workflowName", childName);
        d.put("simulated", simulate);
        ArrayNode occurrences = d.putArray("occurrences");
        List<Map<String, Object>> results = new ArrayList<>();
        List<ChildOutcome> reached = new ArrayList<>();
        boolean allSucceeded = true;
        for (int i = 0; i < planned; i++) {
            ChildOutcome chosen = defaultOutcome;
            if (outcomeList.isArray() && i < outcomeList.size()) {
                String label = outcomeList.get(i).asString();
                if (outcomes.isEmpty()) {
                    chosen = new ChildOutcome(label, "success");
                }
                for (ChildOutcome o : outcomes) {
                    if (o.label().equals(label)) {
                        chosen = o;
                    }
                }
            }
            Map<String, Object> childOutputs = new LinkedHashMap<>();
            ObjectNode occ = occurrences.addObject();
            occ.put("occurrence", i + 1);
            if (simulate) {
                if (!budget.child()) {
                    stallWith(n, "The test reached its 25 child-run budget.", d, "TEST_LIMIT", "Nested child tests are bounded to 25 occurrences.");
                    return null;
                }
                ObjectNode childInput = F.objectNode();
                new TreeMap<>(n.inputMapping()).forEach((field, ref) -> {
                    Object v = resolve(ref);
                    if (v != null) {
                        childInput.set(field, DefaultWorkflowSimulator.json(v));
                    }
                });
                if (n.forEach() != null) {
                    childInput.set(n.forEach().itemInput(), DefaultWorkflowSimulator.json(items.get(i)));
                }
                SimulationResult r = owner.run(childDefinition, StrictJson.write(childInput),
                        StrictJson.write(scenario.path("childScenarios").path(n.id()).isObject() ? scenario.path("childScenarios").path(n.id()) : F.objectNode()), context, depth + 1, budget);
                if (!r.hasTrace()) {
                    String message = Text.quote(n.label()) + ": " + Text.quote(childName) + " could not be tested"
                            + (r.inputErrors().isEmpty() ? "." : ": " + r.inputErrors().get(0).message());
                    stallWith(n, message, d, "INTERNAL_ERROR", message);
                    return null;
                }
                JsonNode childResult = StrictJson.parse(r.json());
                JsonNode childOutcome = childResult.path("outcome");
                occ.set("trace", childResult.path("trace"));
                if (!"finished".equals(childOutcome.path("status").asString())) {
                    occ.put("outcome", "Needs attention");
                    String message = Text.quote(n.label()) + ": " + Text.quote(childName) + " would stop and need"
                            + " attention, so this request keeps waiting for it.";
                    stallWith(n, message, d, "CHILD_NEEDS_ATTENTION", "The other workflow stops and needs attention.");
                    return null;
                }
                chosen = new ChildOutcome(childOutcome.path("businessOutcome").asString(),
                        childOutcome.path("outcomeKind").asString());
                childResult.path("outputs").properties().forEach(e -> childOutputs.put(e.getKey(),
                        DefaultWorkflowSimulator.plain(e.getValue())));
            } else if (childRoot != null) {
                JsonNode props = childRoot.path("outputSchema").path("properties");
                List<String> names = new ArrayList<>(props.propertyNames());
                Collections.sort(names);
                for (String name : names) {
                    childOutputs.put(name, DefaultWorkflowSimulator.plain(sample(props.get(name), name)));
                }
            }
            JsonNode given = s.path("results");
            if (given.isObject()) {
                given.properties().forEach(e -> childOutputs.put(e.getKey(),
                        DefaultWorkflowSimulator.plain(e.getValue())));
            }
            childOutputs.put("outcome", chosen.label());
            childOutputs.put("outcomeKind", chosen.kind());
            childOutputs.put("reference", "ORQ-TEST-" + (i + 1));
            occ.put("outcome", chosen.label());
            occ.put("outcomeKind", chosen.kind());
            results.add(childOutputs);
            reached.add(chosen);
            allSucceeded &= "success".equals(chosen.kind());
            if (n.repeat() != null && chosen.label().equals(untilOutcome)) {
                break;
            }
        }
        int iterations = results.size();
        boolean limitReached = n.repeat() != null && (reached.isEmpty()
                || !reached.get(reached.size() - 1).label().equals(untilOutcome));
        Map<String, Object> out = new LinkedHashMap<>();
        if (!results.isEmpty()) {
            Map<String, Object> last = results.get(results.size() - 1);
            out.put("outcome", last.get("outcome"));
            out.put("outcomeKind", last.get("outcomeKind"));
            out.put("reference", last.get("reference"));
            new TreeMap<>(n.resultMapping()).forEach((name, childField) -> {
                Object v = last.get(childField);
                if (v != null) {
                    out.put(name, v);
                }
            });
        }
        out.put("iterations", BigDecimal.valueOf(iterations));
        out.put("limitReached", limitReached);
        out.put("allSucceeded", allSucceeded);
        new TreeMap<>(n.collectionMapping()).forEach((name, childField) -> {
            List<Object> collected = new ArrayList<>();
            results.forEach(r -> collected.add(r.get(childField)));
            out.put(name, collected);
        });
        d.put("iterations", iterations);
        d.put("limitReached", limitReached);
        d.put("allSucceeded", allSucceeded);
        steps.put(n.id(), out);
        String last = reached.isEmpty() ? "nothing" : Text.quote(reached.get(reached.size() - 1).label());
        String sentence = Text.quote(n.label()) + ": starts " + Text.quote(childName)
                + (iterations == 1 ? "" : " " + Text.count(iterations, "time")) + ". "
                + (simulate ? "In this test it runs to its end and finishes with " : "In this test it ends with ")
                + last + (n.repeat() != null && limitReached ? ", and the limit of "
                + Text.count(n.repeat().maxIterations(), "attempt") + " is reached." : ".");
        Transition t = next(n, Route.NEXT);
        add(n, sentence, d, out, t);
        return t;
    }

    // ------------------------------------------------------------------ trace plumbing

    private void add(Node n, String summary, ObjectNode details, Map<String, Object> wrote, Transition taken) {
        ObjectNode e = F.objectNode();
        e.put("seq", trace.size() + 1);
        e.put("stepId", n.id());
        e.put("stepLabel", n.label());
        e.put("kind", n.kind().json());
        e.put("kindLabel", n.kind().businessLabel());
        if (lane != null) {
            e.put("branch", lane);
        }
        e.put("summary", summary);
        e.set("details", details);
        if (wrote != null && !wrote.isEmpty()) {
            e.set("wrote", DefaultWorkflowSimulator.json(wrote));
        }
        if (taken == null) {
            e.putNull("transitionId");
            e.putNull("nextStepId");
        } else {
            e.put("transitionId", taken.id());
            e.put("nextStepId", taken.to());
        }
        trace.add(e);
    }

    private void stallWith(Node n, String summary, ObjectNode details, String reason, String message) {
        ObjectNode a = details.putObject("attention");
        a.put("reason", reason);
        a.put("message", message);
        add(n, summary, details, null, null);
        stall(n, reason, message);
    }

    private void stall(Node n, String reason, String message) {
        if (attention == null) {
            attention = F.objectNode();
            attention.put("stepId", n.id());
            attention.put("stepLabel", n.label());
            attention.put("reason", reason);
            attention.put("message", message);
        }
    }

    // ------------------------------------------------------------------ values

    private record FieldInfo(String label, boolean money) {
    }

    private FieldInfo info(String ref) {
        List<String> tokens;
        try {
            tokens = JsonPointers.parse(ref);
        } catch (IllegalArgumentException e) {
            return new FieldInfo(ref, false);
        }
        if (tokens.size() == 2 && "input".equals(tokens.get(0))) {
            var field = def.inputSchema().properties().get(tokens.get(1));
            boolean money = def.inputUi() != null && "money".equals(def.inputUi().widgets().get(tokens.get(1)));
            String title = field == null ? tokens.get(1) : field.title();
            return new FieldInfo(Text.fieldLabel(title, money), money);
        }
        if (tokens.size() == 3 && "steps".equals(tokens.get(0))) {
            Node step = graph.node(tokens.get(1));
            return new FieldInfo((step == null ? tokens.get(1) : step.label()) + " / " + tokens.get(2), false);
        }
        return new FieldInfo(ref, false);
    }

    private Object resolve(String ref) {
        List<String> tokens;
        try {
            tokens = JsonPointers.parse(ref);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (tokens.size() >= 2 && "input".equals(tokens.get(0))) {
            return DefaultWorkflowSimulator.plain(com.orqix.compiler.InputValues.resolve(inputValues, ref));
        }
        if (tokens.size() == 3 && "steps".equals(tokens.get(0))) {
            Map<String, Object> step = steps.get(tokens.get(1));
            return step == null ? null : step.get(tokens.get(2));
        }
        return null;
    }

    /**
     * Wording of a condition. With values it reads "Amount (USD 5,000) is at least USD 5,000"; {@code known} is the
     * value to show when it is already known (otherwise it is looked up).
     */
    private String conditionText(Condition c, boolean withValue, Object known) {
        if (c.compound()) {
            List<String> parts = new ArrayList<>();
            c.terms().forEach(t -> parts.add(conditionText(t, withValue, null)));
            return ("all".equals(c.group()) ? "all of these are true: " : "at least one of these is true: ")
                    + String.join("; ", parts);
        }
        FieldInfo f = info(c.fieldRef());
        Object actual = known != null ? known : withValue ? resolve(c.fieldRef()) : null;
        String left = withValue && actual != null ? f.label() + " (" + Text.value(actual, f.money()) + ")" : f.label();
        return left + " " + c.operator().phrase() + " " + Text.value(c.value().value(), f.money());
    }

    // ------------------------------------------------------------------ warnings and coverage

    private void warn(String code, String stepId, String message) {
        ObjectNode w = F.objectNode();
        w.put("code", code);
        if (stepId == null) {
            w.putNull("stepId");
        } else {
            w.put("stepId", stepId);
        }
        w.put("message", message);
        warnings.add(w);
    }

    private void collectWarnings() {
        for (ValidationIssue issue : tolerated) {
            warn(issue.code(), issue.nodeId(), issue.message());
        }
        for (ValidationIssue issue : report.issues()) {
            if (issue.severity() == Severity.WARNING) {
                warn(issue.code(), issue.nodeId(), issue.message());
            }
        }
        for (DecisionTable table : def.decisions()) {
            for (DecisionTable.Row row : table.rows()) {
                String why = impossible(row.when());
                if (why != null) {
                    warn("RULE_NEVER_MATCHES", decisionStep(table.key()), "Rule " + (row.index() + 1) + " of "
                            + Text.quote(table.key()) + " can never apply: " + why);
                }
            }
        }
    }

    private String decisionStep(String key) {
        for (Node n : def.nodes()) {
            if (n instanceof Node.Decision d && d.decisionRef().equals(key)) {
                return n.id();
            }
        }
        return null;
    }

    /** @return why a condition on a request answer can never be true, or null */
    private String impossible(Condition c) {
        if (c.compound()) {
            return null;
        }
        List<String> tokens;
        try {
            tokens = JsonPointers.parse(c.fieldRef());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (tokens.size() != 2 || !"input".equals(tokens.get(0))) {
            return null;
        }
        var field = def.inputSchema().properties().get(tokens.get(1));
        if (field == null) {
            return null;
        }
        FieldInfo f = info(c.fieldRef());
        if (c.value() instanceof Literal.Num num) {
            BigDecimal v = num.number();
            BigDecimal min = field.minimum();
            BigDecimal max = field.maximum();
            boolean never = switch (c.operator()) {
                case EQ -> (min != null && v.compareTo(min) < 0) || (max != null && v.compareTo(max) > 0);
                case GTE -> max != null && v.compareTo(max) > 0;
                case GT -> max != null && v.compareTo(max) >= 0;
                case LT -> min != null && v.compareTo(min) <= 0;
                case LTE -> min != null && v.compareTo(min) < 0;
                default -> false;
            };
            if (never) {
                return f.label() + " " + c.operator().phrase() + " " + Text.value(v, f.money())
                        + ", but the request form only allows "
                        + (min == null ? "" : "at least " + Text.value(min, f.money()))
                        + (min != null && max != null ? " and " : "")
                        + (max == null ? "" : "at most " + Text.value(max, f.money())) + ".";
            }
        }
        if (c.value() instanceof Literal.Str str && c.operator() == Operator.EQ && field.enumValues() != null
                && !field.enumValues().contains(str.string())) {
            return f.label() + " is never " + Text.quote(str.string()) + ": the request form only offers "
                    + String.join(", ", field.enumValues()) + ".";
        }
        return null;
    }

    private void scenarioWarnings() {
        for (String group : List.of("approvals", "calls", "children", "messages", "workers")) {
            for (String id : scenario.path(group).propertyNames()) {
                Node n = graph.node(id);
                boolean right = n != null && switch (group) {
                    case "approvals" -> n.kind() == NodeKind.APPROVAL;
                    case "calls" -> n.kind() == NodeKind.HTTP;
                    case "messages" -> n.kind() == NodeKind.WAIT_FOR_MESSAGE;
                    case "workers" -> n.kind() == NodeKind.EXTERNAL_TASK;
                    default -> n.kind() == NodeKind.START_WORKFLOW;
                };
                if (!right) {
                    warn("SCENARIO_UNKNOWN_STEP", null, "The test settings mention the step " + Text.quote(id)
                            + ", which is not a matching step of this workflow. They were ignored.");
                }
            }
        }
    }

    private ObjectNode coverage() {
        ObjectNode c = F.objectNode();
        List<Node> real = def.nodes().stream().filter(n -> !graph.isShadowed(n)).toList();
        ObjectNode stepsNode = c.putObject("steps");
        long seen = real.stream().filter(n -> visited.contains(n.id())).count();
        stepsNode.put("visited", seen);
        stepsNode.put("total", real.size());
        ArrayNode missed = stepsNode.putArray("notVisited");
        real.stream().filter(n -> !visited.contains(n.id())).forEach(n -> {
            ObjectNode o = missed.addObject();
            o.put("id", n.id());
            o.put("label", n.label());
        });
        // Routes: transitions that leave a step with a choice (more than one way out), not parallel branches.
        ObjectNode routes = c.putObject("routes");
        int total = 0;
        int tested = 0;
        ArrayNode notTaken = routes.putArray("notTaken");
        for (Transition t : graph.resolvedTransitions()) {
            Node from = graph.node(t.from());
            if (graph.outgoing(from).size() < 2 || (from instanceof Node.ParallelSplit ps && !ps.inclusive())) {
                continue;
            }
            total++;
            if (taken.contains(t.id())) {
                tested++;
            } else {
                ObjectNode o = notTaken.addObject();
                o.put("transitionId", t.id());
                o.put("from", t.from());
                o.put("to", t.to());
                o.put("description", routeDescription(t));
            }
        }
        routes.put("taken", tested);
        routes.put("total", total);
        ObjectNode rules = c.putObject("rules");
        int ruleTotal = 0;
        int ruleHit = 0;
        ArrayNode notMatched = rules.putArray("notMatched");
        for (DecisionTable table : def.decisions()) {
            if (decisionStep(table.key()) == null) {
                continue;
            }
            for (DecisionTable.Row row : table.rows()) {
                ruleTotal++;
                if (matchedRules.contains(table.key() + "#" + row.index())) {
                    ruleHit++;
                } else {
                    ObjectNode o = notMatched.addObject();
                    o.put("decision", table.key());
                    o.put("row", "Rule " + (row.index() + 1));
                    o.put("condition", conditionText(row.when(), false, null));
                }
            }
            ruleTotal++;
            if (matchedRules.contains(table.key() + "#default")) {
                ruleHit++;
            } else {
                ObjectNode o = notMatched.addObject();
                o.put("decision", table.key());
                o.put("row", "Otherwise");
                o.putNull("condition");
            }
        }
        rules.put("matched", ruleHit);
        rules.put("total", ruleTotal);
        c.put("summary", "Tested " + seen + " of " + real.size() + " steps, " + tested + " of " + total + " routes and "
                + ruleHit + " of " + ruleTotal + " decision rules.");
        return c;
    }

    private String routeDescription(Transition t) {
        Node from = graph.node(t.from());
        Node to = graph.node(t.to());
        String when = switch (t.on()) {
            case APPROVED -> "when approved";
            case REJECTED -> "when rejected";
            case TIMED_OUT -> "when the time limit fires";
            case DEFAULT -> "otherwise";
            case CONDITION -> "when " + routeName(t);
            default -> "next";
        };
        return from.label() + ", " + when + ", goes to " + to.label();
    }
}
