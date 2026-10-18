package com.orqix.compiler.internal.bpmn;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import com.orqix.compiler.CompileContext.WorkflowInfo;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Position;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/**
 * BPMN 2.0 generation (S4 section 6). Element order: process extension, then every node in canonical order followed
 * immediately by its generated helpers, then one sequence flow per canonical transition in canonical order. The only
 * expressions written are the allowlisted forms of S4 section 6; user text appears only in {@code name} attributes and
 * {@code orqix:*} extension attributes, never inside an expression.
 */
public final class BpmnGenerator {

    public static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    public static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    public static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    public static final String ORQIX_NS = "https://orqix.example/bpmn/v0";
    public static final String BPMNDI_NS = "http://www.omg.org/spec/BPMN/20100524/DI";
    public static final String DC_NS = "http://www.omg.org/spec/DD/20100524/DC";
    public static final String DI_NS = "http://www.omg.org/spec/DD/20100524/DI";
    public static final String TARGET_NAMESPACE = "https://orqix.example/workflows";

    /** Suffix of the helper flow from an approval task to its outcome gateway. */
    public static final String APPROVAL_OUT = "__out";
    /** Suffix of the outcome gateway generated after an approval task. */
    public static final String APPROVAL_ROUTE = "__route";
    /** Suffix of the interrupting timer boundary event generated for an approval with a time limit. */
    public static final String APPROVAL_TIMEOUT = "__timeout";

    /** Suffix of the message catch event generated for a "Wait for a message" step that has a time limit. */
    public static final String MESSAGE_CATCH = "__message";
    /** Suffix of the timer catch event generated for the time limit of a "Wait for a message" step. */
    public static final String MESSAGE_TIMEOUT = "__timeout";
    /** Prefix of the BPMN message definition ID generated for a message name. */
    public static final String MESSAGE_DEFINITION = "message__";

    /** Suffix of the error boundary event generated for a step that has a {@code failed} path (S32). */
    public static final String FAILED_BOUNDARY = "__failed";
    /** ID of the one BPMN error definition used by every error boundary event (S32). */
    public static final String FAILED_ERROR = "error__stepFailed";
    /** Error code a delegate raises when a step fails for good and the workflow has a {@code failed} path. */
    public static final String FAILED_ERROR_CODE = "ORQIX_STEP_FAILED";

    /** Prefix of the BPMN error definition generated for a business error code (S55). */
    public static final String ERROR_PREFIX = "error__";
    /** Prefix of the BPMN signal definition generated for a signal name (S55). */
    public static final String SIGNAL_PREFIX = "signal__";
    /** Suffix of the start event generated inside a subprocess (S55). */
    public static final String SCOPE_START = "__start";
    /** Infix of the end event generated inside a section for each step it can be left to (S55). */
    public static final String SCOPE_END = "__end__";
    /** Suffix of the end event generated inside a handler that runs alongside the request (S55). */
    public static final String SCOPE_DONE = "__end";
    /** Suffix of the gateway after a section that can be left to several steps (S55). */
    public static final String SCOPE_EXIT = "__exit";
    /** Suffix of the interrupting timer boundary of a section with a time limit (S55). */
    public static final String SCOPE_TIMEOUT = "__timeout";
    /** Suffix of the interrupting error boundary of a section with an error route (S55). */
    public static final String SCOPE_ERROR = "__error";
    /** Process variable prefix holding the step a section was left to (S55). */
    public static final String EXIT_VARIABLE = "orqix_exit_";

    /** Generated process plus its source map. */
    public record Result(String xml, SourceMap sourceMap) {
    }

    private BpmnGenerator() {
    }

    /**
     * @param definition       a definition without blockers
     * @param decisionResource decision table key to DMN resource name
     * @param children         "Start another workflow" node id to the pinned child release facts
     */
    public static Result generate(WorkflowDefinition definition, String compilerVersion, String semanticHash,
                                  Map<String, String> decisionResource, Map<String, WorkflowInfo> children) {
        Map<String, Node> nodes = new HashMap<>();
        definition.nodes().forEach(n -> nodes.put(n.id(), n));
        Map<String, String> branchDefault = new HashMap<>();
        // Joins that wait only for the paths that started (inclusive), as opposed to every path (S51).
        java.util.Set<String> inclusiveJoins = new java.util.HashSet<>();
        for (Node n : definition.nodes()) {
            if (n instanceof Node.ParallelSplit p && p.inclusive()) {
                inclusiveJoins.add(p.joinRef());
            }
        }
        for (Transition t : definition.transitions()) {
            if (t.on() == Route.DEFAULT) {
                branchDefault.putIfAbsent(t.from(), t.id());
            }
        }

        SourceMap map = new SourceMap();
        XmlOut x = new XmlOut();
        x.open("", "definitions", BPMN_NS)
                .defaultNamespace(BPMN_NS)
                .namespace("xsi", XSI_NS)
                .namespace("flowable", FLOWABLE_NS)
                .namespace("orqix", ORQIX_NS)
                .namespace("bpmndi", BPMNDI_NS)
                .namespace("omgdc", DC_NS)
                .namespace("omgdi", DI_NS)
                .attr("targetNamespace", TARGET_NAMESPACE);
        // Message definitions come first, one per message name (sorted), so ids and order are deterministic.
        new java.util.TreeSet<>(definition.nodes().stream().filter(n -> n instanceof Node.WaitForMessage)
                .map(n -> ((Node.WaitForMessage) n).message()).toList()).forEach(name ->
                x.leaf("", "message", BPMN_NS).attr("id", MESSAGE_DEFINITION + name).attr("name", name));
        if (definition.transitions().stream().anyMatch(t -> t.on() == Route.FAILED)) {
            // One error definition for every failure route; each error boundary event sits on its own step (S32).
            x.leaf("", "error", BPMN_NS).attr("id", FAILED_ERROR).attr("errorCode", FAILED_ERROR_CODE);
        }
        Scopes scopes = new Scopes(definition);
        // Business errors and signals (S55): one definition per code or name, sorted. Signals reach only the steps of
        // the request that sends them (flowable:scope processInstance), never other requests.
        scopes.errorCodes().forEach(code ->
                x.leaf("", "error", BPMN_NS).attr("id", ERROR_PREFIX + code).attr("errorCode", code));
        scopes.signals().forEach(name -> x.leaf("", "signal", BPMN_NS).attr("id", SIGNAL_PREFIX + name)
                .attr("name", name).attr("flowable", FLOWABLE_NS, "scope", "processInstance"));
        x.open("", "process", BPMN_NS)
                .attr("id", definition.workflowKey())
                .attr("name", definition.name())
                .attr("isExecutable", "true");
        x.open("", "extensionElements", BPMN_NS);
        x.leaf("orqix", "release", ORQIX_NS)
                .attr("schemaVersion", definition.schemaVersion())
                .attr("compilerVersion", compilerVersion)
                .attr("semanticHash", semanticHash);
        x.close();

        for (Node n : definition.nodes()) {
            if (scopes.scopeOf(n.id()) == null) {
                node(x, map, definition, n, branchDefault, inclusiveJoins, decisionResource, children, scopes);
            }
        }
        for (Transition t : definition.transitions()) {
            if (scopes.container(t) == null) {
                flow(x, map, nodes, t, scopes.target(t));
            }
        }
        for (WorkflowDefinition.Subprocess s : definition.subprocesses()) {
            scope(x, map, definition, nodes, s, branchDefault, inclusiveJoins, decisionResource, children, scopes);
        }
        x.close();
        Diagram.write(x, definition, nodes, scopes);
        x.close();
        return new Result(x.finish(), map);
    }

    private static void node(XmlOut x, SourceMap map, WorkflowDefinition d, Node n,
                             Map<String, String> branchDefault, java.util.Set<String> inclusiveJoins,
                             Map<String, String> decisionResource, Map<String, WorkflowInfo> children,
                             Scopes scopes) {
        map.node(n.id(), n.id());
        switch (n) {
            case Node.RaiseError r -> {
                // An error end event: the section's error boundary or an error handler catches it (S55).
                x.open("", "endEvent", BPMN_NS).attr("id", r.id()).attr("name", r.label());
                x.open("", "extensionElements", BPMN_NS);
                x.leaf("orqix", "raise", ORQIX_NS).attr("errorCode", r.errorCode());
                x.close();
                x.leaf("", "errorEventDefinition", BPMN_NS).attr("errorRef", ERROR_PREFIX + r.errorCode());
                x.close();
            }
            case Node.SendSignal s -> {
                x.open("", "intermediateThrowEvent", BPMN_NS).attr("id", s.id()).attr("name", s.label());
                x.open("", "extensionElements", BPMN_NS);
                x.leaf("orqix", "signal", ORQIX_NS).attr("action", "send").attr("name", s.signal());
                x.close();
                x.leaf("", "signalEventDefinition", BPMN_NS).attr("signalRef", SIGNAL_PREFIX + s.signal());
                x.close();
            }
            case Node.WaitForSignal w -> {
                x.open("", "intermediateCatchEvent", BPMN_NS).attr("id", w.id()).attr("name", w.label());
                x.open("", "extensionElements", BPMN_NS);
                x.leaf("orqix", "signal", ORQIX_NS).attr("action", "wait").attr("name", w.signal());
                x.close();
                x.leaf("", "signalEventDefinition", BPMN_NS).attr("signalRef", SIGNAL_PREFIX + w.signal());
                x.close();
            }
            case Node.Compensate c -> {
                // Orqix runs the undo calls itself, one after the other and journaled, most recent first; Flowable's
                // compensation events do not wait for the handlers to finish (S55).
                x.open("", "serviceTask", BPMN_NS)
                        .attr("id", c.id())
                        .attr("name", c.label())
                        .attr("flowable", FLOWABLE_NS, "async", "true")
                        .attr("flowable", FLOWABLE_NS, "exclusive", "true")
                        .attr("flowable", FLOWABLE_NS, "delegateExpression", "${orqixCompensationTask}");
                x.open("", "extensionElements", BPMN_NS);
                x.inline("flowable", "failedJobRetryTimeCycle", FLOWABLE_NS, null,
                        RetryCycle.forAttempts(d.execution().maxAttempts(), d.execution().retryDelaySeconds()), false);
                x.open("orqix", "compensation", ORQIX_NS);
                for (Node other : d.nodes()) {
                    if (other instanceof Node.Http h && h.undoHandler()) {
                        x.leaf("orqix", "undo", ORQIX_NS).attr("target", h.compensates()).attr("handler", h.id());
                    }
                }
                x.close();
                x.close();
                x.close();
            }
            case Node.Start s -> x.leaf("", "startEvent", BPMN_NS).attr("id", s.id()).attr("name", s.label());
            case Node.Approval a -> {
                x.open("", "userTask", BPMN_NS)
                        .attr("id", a.id())
                        .attr("name", a.label())
                        .attr("flowable", FLOWABLE_NS, "candidateGroups", a.candidateGroupRef());
                x.open("", "extensionElements", BPMN_NS);
                x.leaf("orqix", "approval", ORQIX_NS)
                        .attr("rejectReasonRequired", Boolean.toString(a.rejectReasonRequired()));
                if (a.dueAfterHours() != null) {
                    x.attr("dueAfterHours", a.dueAfterHours().toString());
                }
                if (a.dueAfterMinutes() != null) x.attr("dueAfterMinutes", a.dueAfterMinutes().toString());
                if (a.timeoutAfter() != null) {
                    x.attr("timeoutAfter", a.timeoutAfter());
                }
                if (!a.requiredSkills().isEmpty()) x.attr("requiredSkills", String.join(",", a.requiredSkills()));
                if (a.calendar() != null) x.attr("calendar", a.calendar().toString());
                if (a.escalation() != null) x.attr("escalation", a.escalation().toString());
                x.close();
                if (a.multiInstance()) {
                    // One task per required approver; the step ends when all approved or at the first rejection (S51).
                    x.open("", "multiInstanceLoopCharacteristics", BPMN_NS).attr("isSequential", "false");
                    x.inline("", "loopCardinality", BPMN_NS, null, a.approvers().toString(), false);
                    x.openInline("", "completionCondition", BPMN_NS)
                            .attr("xsi", XSI_NS, "type", "tFormalExpression")
                            .cdata("${nrOfCompletedInstances == nrOfInstances || orqixRoute.rejected(execution, '"
                                    + a.id() + "')}")
                            .closeInline();
                    x.close();
                }
                x.close();
                String out = a.id() + APPROVAL_OUT;
                String gateway = a.id() + APPROVAL_ROUTE;
                x.leaf("", "sequenceFlow", BPMN_NS).attr("id", out).attr("sourceRef", a.id())
                        .attr("targetRef", gateway);
                map.node(a.id(), out);
                x.leaf("", "exclusiveGateway", BPMN_NS).attr("id", gateway).attr("name", a.label() + ": outcome");
                map.node(a.id(), gateway);
                if (a.timeoutAfter() != null) {
                    String timeout = a.id() + APPROVAL_TIMEOUT;
                    x.open("", "boundaryEvent", BPMN_NS)
                            .attr("id", timeout)
                            .attr("name", a.label() + ": no decision in time")
                            .attr("attachedToRef", a.id())
                            .attr("cancelActivity", "true");
                    x.open("", "extensionElements", BPMN_NS);
                    x.leaf("orqix", "timeout", ORQIX_NS).attr("after", a.timeoutAfter());
                    x.close();
                    x.open("", "timerEventDefinition", BPMN_NS);
                    x.inline("", "timeDuration", BPMN_NS, null, a.timeoutAfter(), false);
                    x.close();
                    x.close();
                    map.node(a.id(), timeout);
                }
            }
            case Node.Decision dn -> {
                x.open("", "serviceTask", BPMN_NS)
                        .attr("id", dn.id())
                        .attr("name", dn.label())
                        .attr("flowable", FLOWABLE_NS, "delegateExpression", "${orqixDecisionTask}");
                WorkflowDefinition.DecisionTable table = d.decisions().stream()
                        .filter(t -> t.key().equals(dn.decisionRef())).findFirst().orElseThrow();
                x.open("", "extensionElements", BPMN_NS);
                x.leaf("orqix", "decision", ORQIX_NS)
                        .attr("ref", table.key())
                        .attr("version", Integer.toString(table.version()))
                        .attr("resource", decisionResource.get(table.key()))
                        .attr("fieldRef", table.rows().get(0).when().compound()
                            ? table.rows().get(0).when().terms().get(0).fieldRef() : table.rows().get(0).when().fieldRef());
                x.close();
                x.close();
            }
            case Node.Branch b -> {
                x.leaf("", "exclusiveGateway", BPMN_NS).attr("id", b.id()).attr("name", b.label());
                if (branchDefault.containsKey(b.id())) {
                    x.attr("default", branchDefault.get(b.id()));
                }
            }
            case Node.Http h -> {
                x.open("", "serviceTask", BPMN_NS)
                        .attr("id", h.id())
                        .attr("name", h.label())
                        .attr("flowable", FLOWABLE_NS, "async", "true")
                        .attr("flowable", FLOWABLE_NS, "exclusive", "true")
                        .attr("flowable", FLOWABLE_NS, "delegateExpression", "${orqixHttpTask}");
                if (h.undoHandler()) {
                    // Never reached by a path: the Undo step reads this call's settings and runs it (S55).
                    x.attr("isForCompensation", "true");
                }
                x.open("", "extensionElements", BPMN_NS);
                x.inline("flowable", "failedJobRetryTimeCycle", FLOWABLE_NS, null,
                        RetryCycle.forAttempts(d.execution().maxAttempts(), d.execution().retryDelaySeconds()),
                        false);
                String version = Integer.toString(d.dependencies().stream()
                        .filter(dep -> dep.key().equals(h.connectionRef())).findFirst().orElseThrow()
                        .definitionVersion());
                x.open("orqix", "http", ORQIX_NS)
                        .attr("connection", h.connectionRef())
                        .attr("connectionVersion", version)
                        .attr("operation", h.operation())
                        .attr("timeoutSeconds", Integer.toString(h.timeoutSeconds()));
                if (h.compensates() != null) {
                    x.attr("compensates", h.compensates());
                }
                if (h.raiseOnFailure() != null) {
                    // Opt-in (S66): a known failure becomes this business error for the section's error boundary.
                    x.attr("raiseOnFailure", h.raiseOnFailure());
                }
                h.inputMapping().forEach((name, fieldRef) ->
                        x.leaf("orqix", "input", ORQIX_NS).attr("name", name).attr("fieldRef", fieldRef));
                h.resultMapping().forEach((name, pointer) ->
                        x.leaf("orqix", "result", ORQIX_NS).attr("name", name).attr("pointer", pointer));
                x.close();
                x.close();
                x.close();
                failureBoundary(x, map, d, h);
            }
            case Node.GenerateDocument g -> {
                x.open("", "serviceTask", BPMN_NS).attr("id", g.id()).attr("name", g.label())
                        .attr("flowable", FLOWABLE_NS, "async", "true")
                        .attr("flowable", FLOWABLE_NS, "exclusive", "true")
                        .attr("flowable", FLOWABLE_NS, "delegateExpression", "${orqixGenerateDocumentTask}");
                x.open("", "extensionElements", BPMN_NS);
                x.inline("flowable", "failedJobRetryTimeCycle", FLOWABLE_NS, null,
                        RetryCycle.forAttempts(d.execution().maxAttempts(), d.execution().retryDelaySeconds()), false);
                x.open("orqix", "generateDocument", ORQIX_NS).attr("format", g.format())
                        .attr("fileName", g.fileName()).attr("templateBase64", java.util.Base64.getEncoder()
                                .encodeToString(g.template().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                g.inputMapping().forEach((name, fieldRef) ->
                        x.leaf("orqix", "input", ORQIX_NS).attr("name", name).attr("fieldRef", fieldRef));
                x.close(); x.close(); x.close();
            }
            case Node.ExternalTask e -> {
                // Like a child workflow, the task is async and triggerable: the delegate publishes the job and the engine
                // waits until a worker's completion triggers it (S43).
                x.open("", "serviceTask", BPMN_NS)
                        .attr("id", e.id())
                        .attr("name", e.label())
                        .attr("flowable", FLOWABLE_NS, "async", "true")
                        .attr("flowable", FLOWABLE_NS, "exclusive", "true")
                        .attr("flowable", FLOWABLE_NS, "triggerable", "true")
                        .attr("flowable", FLOWABLE_NS, "delegateExpression", "${orqixExternalTask}");
                x.open("", "extensionElements", BPMN_NS);
                x.inline("flowable", "failedJobRetryTimeCycle", FLOWABLE_NS, null,
                        RetryCycle.forAttempts(d.execution().maxAttempts(), d.execution().retryDelaySeconds()), false);
                x.open("orqix", "external", ORQIX_NS).attr("topic", e.topic())
                        .attr("leaseSeconds", Integer.toString(e.leaseSeconds()))
                        .attr("maxAttempts", Integer.toString(d.execution().maxAttempts()))
                        .attr("retryDelaySeconds", Integer.toString(d.execution().retryDelaySeconds()));
                e.inputMapping().forEach((name, fieldRef) ->
                        x.leaf("orqix", "input", ORQIX_NS).attr("name", name).attr("fieldRef", fieldRef));
                for (Node.PayloadField f : e.result()) {
                    x.leaf("orqix", "field", ORQIX_NS).attr("name", f.name()).attr("type", f.type().json())
                            .attr("required", Boolean.toString(f.required()));
                }
                x.close();
                x.close();
                x.close();
            }
            case Node.Notification no -> {
                x.open("", "serviceTask", BPMN_NS)
                        .attr("id", no.id())
                        .attr("name", no.label())
                        .attr("flowable", FLOWABLE_NS, "delegateExpression", "${orqixNotificationTask}");
                x.open("", "extensionElements", BPMN_NS);
                x.leaf("orqix", "notification", ORQIX_NS)
                        .attr("channel", no.channel())
                        .attr("recipient", no.recipient())
                        .attr("message", no.message());
                x.close();
                x.close();
            }
            case Node.ParallelSplit p -> {
                x.leaf("", p.inclusive() ? "inclusiveGateway" : "parallelGateway", BPMN_NS).attr("id", p.id())
                        .attr("name", p.label());
                if (p.inclusive() && branchDefault.containsKey(p.id())) {
                    x.attr("default", branchDefault.get(p.id()));
                }
            }
            case Node.ParallelJoin j -> x.leaf("", inclusiveJoins.contains(j.id()) ? "inclusiveGateway" : "parallelGateway", BPMN_NS)
                    .attr("id", j.id())
                    .attr("name", j.label())
                    // Asynchronous and exclusive: branches finishing at the same time are joined one after the other,
                    // so exactly one continuation happens (S12 section 1).
                    .attr("flowable", FLOWABLE_NS, "async", "true")
                    .attr("flowable", FLOWABLE_NS, "exclusive", "true");
            case Node.Wait w -> {
                x.open("", "intermediateCatchEvent", BPMN_NS).attr("id", w.id()).attr("name", w.label());
                x.open("", "extensionElements", BPMN_NS);
                if (w.duration() != null) {
                    x.leaf("orqix", "wait", ORQIX_NS).attr("duration", w.duration());
                } else {
                    x.leaf("orqix", "wait", ORQIX_NS).attr("untilField", w.untilField()).attr("atTime", w.time());
                }
                x.close();
                x.open("", "timerEventDefinition", BPMN_NS);
                if (w.duration() != null) {
                    x.inline("", "timeDuration", BPMN_NS, null, w.duration(), false);
                } else {
                    // The due date is computed when the token arrives, from the date field (S12 section 10).
                    x.inline("", "timeDate", BPMN_NS, null, "${orqixWait.until(execution, '" + w.id() + "')}", true);
                }
                x.close();
                x.close();
            }
            case Node.WaitForMessage m -> {
                boolean limit = m.timeoutAfter() != null;
                String catchId = limit ? m.id() + MESSAGE_CATCH : m.id();
                if (limit) {
                    // An event-based gateway lets the message or the time limit win, whichever comes first.
                    x.leaf("", "eventBasedGateway", BPMN_NS).attr("id", m.id()).attr("name", m.label());
                }
                x.open("", "intermediateCatchEvent", BPMN_NS)
                        .attr("id", catchId)
                        .attr("name", limit ? m.label() + ": message" : m.label());
                x.open("", "extensionElements", BPMN_NS);
                x.open("orqix", "message", ORQIX_NS)
                        .attr("node", m.id())
                        .attr("name", m.message())
                        .attr("correlationField", m.correlationField());
                for (Node.PayloadField f : m.payload()) {
                    x.leaf("orqix", "field", ORQIX_NS)
                            .attr("name", f.name())
                            .attr("type", f.type().json())
                            .attr("required", Boolean.toString(f.required()));
                }
                x.close();
                x.close();
                x.leaf("", "messageEventDefinition", BPMN_NS).attr("messageRef", MESSAGE_DEFINITION + m.message());
                x.close();
                if (limit) {
                    map.node(m.id(), catchId);
                    String timeout = m.id() + MESSAGE_TIMEOUT;
                    x.open("", "intermediateCatchEvent", BPMN_NS)
                            .attr("id", timeout)
                            .attr("name", m.label() + ": no message in time");
                    x.open("", "extensionElements", BPMN_NS);
                    x.leaf("orqix", "messageTimeout", ORQIX_NS).attr("node", m.id()).attr("after", m.timeoutAfter());
                    x.close();
                    x.open("", "timerEventDefinition", BPMN_NS);
                    x.inline("", "timeDuration", BPMN_NS, null, m.timeoutAfter(), false);
                    x.close();
                    x.close();
                    map.node(m.id(), timeout);
                    String toMessage = m.id() + "__toMessage";
                    String toTimeout = m.id() + "__toTimeout";
                    x.leaf("", "sequenceFlow", BPMN_NS).attr("id", toMessage).attr("sourceRef", m.id())
                            .attr("targetRef", catchId);
                    map.node(m.id(), toMessage);
                    x.leaf("", "sequenceFlow", BPMN_NS).attr("id", toTimeout).attr("sourceRef", m.id())
                            .attr("targetRef", timeout);
                    map.node(m.id(), toTimeout);
                }
            }
            case Node.StartWorkflow c -> {
                WorkflowInfo child = children.get(c.id());
                x.open("", "serviceTask", BPMN_NS)
                        .attr("id", c.id())
                        .attr("name", c.label())
                        .attr("flowable", FLOWABLE_NS, "async", "true")
                        .attr("flowable", FLOWABLE_NS, "exclusive", "true")
                        .attr("flowable", FLOWABLE_NS, "triggerable", "true")
                        .attr("flowable", FLOWABLE_NS, "delegateExpression", "${orqixChildTask}");
                x.open("", "extensionElements", BPMN_NS);
                x.inline("flowable", "failedJobRetryTimeCycle", FLOWABLE_NS, null,
                        RetryCycle.forAttempts(d.execution().maxAttempts(), d.execution().retryDelaySeconds()),
                        false);
                x.open("orqix", "child", ORQIX_NS)
                        .attr("workflow", c.workflowRef())
                        .attr("releaseNumber", Integer.toString(child.releaseNumber()))
                        .attr("semanticHash", child.semanticHash());
                c.inputMapping().forEach((name, fieldRef) ->
                        x.leaf("orqix", "input", ORQIX_NS).attr("name", name).attr("fieldRef", fieldRef));
                c.resultMapping().forEach((name, output) ->
                        x.leaf("orqix", "result", ORQIX_NS).attr("name", name).attr("output", output));
                x.close();
                x.close();
                x.close();
                failureBoundary(x, map, d, c);
            }
            case Node.End e -> {
                x.open("", "endEvent", BPMN_NS).attr("id", e.id()).attr("name", e.label());
                x.open("", "extensionElements", BPMN_NS);
                x.leaf("orqix", "outcome", ORQIX_NS).attr("label", e.businessOutcome()).attr("kind", e.outcomeKind());
                x.close();
                boolean inHandler = scopes.scopeOf(e.id()) != null;
                if (!inHandler && ((("rejected".equals(e.outcomeKind()) || "failed".equals(e.outcomeKind()))
                        && d.nodes().stream().anyMatch(other -> other instanceof Node.ParallelSplit))
                        || scopes.sideHandlers())) {
                    // A rejection ends the whole request, including parallel branches that are still open (S12); a
                    // finished request also stops handlers that run alongside it (S55).
                    x.leaf("", "terminateEventDefinition", BPMN_NS);
                }
                x.close();
            }
        }
    }

    /**
     * A subprocess (S55). A section becomes an embedded {@code subProcess}: a generated start event leads to its entry
     * step, every path leaving it ends at a generated end event named after the step it leads to, and after the
     * subprocess a generated gateway continues at that step (directly when there is only one). Its time limit and error
     * route are interrupting boundary events on the subprocess. A handler becomes an event subprocess whose start event
     * carries the trigger; the last steps of a handler that runs alongside the request end at a generated end event.
     */
    private static void scope(XmlOut x, SourceMap map, WorkflowDefinition d, Map<String, Node> nodes,
                              WorkflowDefinition.Subprocess s, Map<String, String> branchDefault,
                              java.util.Set<String> inclusiveJoins, Map<String, String> decisionResource,
                              Map<String, WorkflowInfo> children, Scopes scopes) {
        String id = s.id();
        String entry = scopes.entry(s);
        x.open("", "subProcess", BPMN_NS).attr("id", id).attr("name", s.label());
        if (s.event()) {
            x.attr("triggeredByEvent", "true");
        }
        map.node(id, id);
        x.open("", "extensionElements", BPMN_NS);
        x.leaf("orqix", "scope", ORQIX_NS).attr("kind", s.event() ? "event" : "embedded");
        x.close();
        String start = id + SCOPE_START;
        x.open("", "startEvent", BPMN_NS).attr("id", start).attr("name", s.label() + ": start");
        if (s.event()) {
            x.attr("isInterrupting", Boolean.toString(s.interrupting()));
            x.open("", "extensionElements", BPMN_NS);
            x.leaf("orqix", "scopeEvent", ORQIX_NS).attr("scope", id).attr("trigger", s.triggerType())
                    .attr("interrupting", Boolean.toString(s.interrupting()));
            x.close();
            switch (s.triggerType()) {
                case "error" -> {
                    x.leaf("", "errorEventDefinition", BPMN_NS);
                    if (s.triggerErrorCode() != null) {
                        x.attr("errorRef", ERROR_PREFIX + s.triggerErrorCode());
                    }
                }
                case "signal" -> x.leaf("", "signalEventDefinition", BPMN_NS).attr("signalRef", SIGNAL_PREFIX + s.signal());
                default -> {
                    x.open("", "timerEventDefinition", BPMN_NS);
                    x.inline("", "timeDuration", BPMN_NS, null, s.after(), false);
                    x.close();
                }
            }
        }
        x.close();
        map.node(id, start);
        for (Node n : d.nodes()) {
            if (scopes.scopeOf(n.id()) == s) {
                node(x, map, d, n, branchDefault, inclusiveJoins, decisionResource, children, scopes);
            }
        }
        String begin = id + "__begin";
        x.leaf("", "sequenceFlow", BPMN_NS).attr("id", begin).attr("sourceRef", start).attr("targetRef", entry);
        map.node(id, begin);
        for (Transition t : d.transitions()) {
            if (scopes.container(t) == s) {
                flow(x, map, nodes, t, scopes.target(t));
            }
        }
        java.util.List<String> exits = scopes.exits(s);
        for (String target : exits) {
            String end = id + SCOPE_END + target;
            x.open("", "endEvent", BPMN_NS).attr("id", end).attr("name", s.label() + ": done");
            x.open("", "extensionElements", BPMN_NS);
            x.leaf("orqix", "scopeEnd", ORQIX_NS).attr("scope", id).attr("exit", target);
            if (exits.size() > 1) {
                x.leaf("flowable", "executionListener", FLOWABLE_NS).attr("event", "start")
                        .attr("expression", "${orqixRoute.markExit(execution, '" + id + "', '" + target + "')}");
            }
            x.close();
            x.close();
            map.node(id, end);
        }
        java.util.List<String> done = scopes.sideEnds(s);
        if (!done.isEmpty()) {
            String end = id + SCOPE_DONE;
            x.open("", "endEvent", BPMN_NS).attr("id", end).attr("name", s.label() + ": done");
            x.open("", "extensionElements", BPMN_NS);
            x.leaf("orqix", "scopeEnd", ORQIX_NS).attr("scope", id);
            x.close();
            x.close();
            map.node(id, end);
            for (String last : done) {
                String flowId = id + "__done__" + last;
                x.leaf("", "sequenceFlow", BPMN_NS).attr("id", flowId).attr("sourceRef", last).attr("targetRef", end);
                map.node(id, flowId);
            }
        }
        x.close();
        if (s.event()) {
            return;
        }
        // After the section: continue at the step it was left to.
        if (exits.size() == 1) {
            String flowId = id + SCOPE_EXIT + "__" + exits.get(0);
            x.leaf("", "sequenceFlow", BPMN_NS).attr("id", flowId).attr("sourceRef", id)
                    .attr("targetRef", scopes.outerTarget(exits.get(0)));
            map.node(id, flowId);
        } else if (exits.size() > 1) {
            String gateway = id + SCOPE_EXIT;
            x.leaf("", "sequenceFlow", BPMN_NS).attr("id", id + "__out").attr("sourceRef", id).attr("targetRef", gateway);
            map.node(id, id + "__out");
            x.leaf("", "exclusiveGateway", BPMN_NS).attr("id", gateway).attr("name", s.label() + ": continue");
            map.node(id, gateway);
            for (String target : exits) {
                String flowId = id + SCOPE_EXIT + "__" + target;
                x.open("", "sequenceFlow", BPMN_NS).attr("id", flowId).attr("sourceRef", gateway)
                        .attr("targetRef", scopes.outerTarget(target));
                x.openInline("", "conditionExpression", BPMN_NS)
                        .attr("xsi", XSI_NS, "type", "tFormalExpression")
                        .cdata("${orqixRoute.exited(execution, '" + id + "', '" + target + "')}")
                        .closeInline();
                x.close();
                map.node(id, flowId);
            }
        }
        if (s.timeoutAfter() != null && s.onTimeout() != null) {
            String timeout = id + SCOPE_TIMEOUT;
            x.open("", "boundaryEvent", BPMN_NS).attr("id", timeout).attr("name", s.label() + ": time limit passed")
                    .attr("attachedToRef", id).attr("cancelActivity", "true");
            x.open("", "extensionElements", BPMN_NS);
            x.leaf("orqix", "scopeTimeout", ORQIX_NS).attr("scope", id).attr("after", s.timeoutAfter());
            x.close();
            x.open("", "timerEventDefinition", BPMN_NS);
            x.inline("", "timeDuration", BPMN_NS, null, s.timeoutAfter(), false);
            x.close();
            x.close();
            map.node(id, timeout);
            x.leaf("", "sequenceFlow", BPMN_NS).attr("id", id + "__onTimeout").attr("sourceRef", timeout)
                    .attr("targetRef", scopes.outerTarget(s.onTimeout()));
            map.node(id, id + "__onTimeout");
        }
        if (s.errorTo() != null) {
            String error = id + SCOPE_ERROR;
            x.open("", "boundaryEvent", BPMN_NS).attr("id", error).attr("name", s.label() + ": error")
                    .attr("attachedToRef", id).attr("cancelActivity", "true");
            x.open("", "extensionElements", BPMN_NS);
            x.leaf("orqix", "scopeError", ORQIX_NS).attr("scope", id);
            if (s.errorCode() != null) {
                x.attr("errorCode", s.errorCode());
            }
            x.close();
            x.leaf("", "errorEventDefinition", BPMN_NS);
            if (s.errorCode() != null) {
                x.attr("errorRef", ERROR_PREFIX + s.errorCode());
            }
            x.close();
            map.node(id, error);
            x.leaf("", "sequenceFlow", BPMN_NS).attr("id", id + "__onError").attr("sourceRef", error)
                    .attr("targetRef", scopes.outerTarget(s.errorTo()));
            map.node(id, id + "__onError");
        }
    }

    /** Subprocess membership, entries and exits of a definition without blockers (S55). */
    static final class Scopes {
        private final WorkflowDefinition d;
        private final Map<String, WorkflowDefinition.Subprocess> scopeOf = new HashMap<>();
        private final Map<String, String> entries = new HashMap<>();
        private final Map<String, WorkflowDefinition.Subprocess> entryOf = new HashMap<>();

        Scopes(WorkflowDefinition d) {
            this.d = d;
            for (WorkflowDefinition.Subprocess s : d.subprocesses()) {
                s.nodes().forEach(id -> scopeOf.putIfAbsent(id, s));
            }
            for (WorkflowDefinition.Subprocess s : d.subprocesses()) {
                for (String id : s.nodes()) {
                    boolean entered = s.embedded()
                            ? d.transitions().stream().anyMatch(t -> t.to().equals(id) && scopeOf.get(t.from()) != s)
                            : d.transitions().stream().noneMatch(t -> t.to().equals(id));
                    if (entered && !entries.containsKey(s.id())) {
                        entries.put(s.id(), id);
                        if (s.embedded()) {
                            entryOf.put(id, s);
                        }
                    }
                }
            }
        }

        WorkflowDefinition.Subprocess scopeOf(String nodeId) {
            return scopeOf.get(nodeId);
        }

        String entry(WorkflowDefinition.Subprocess s) {
            return entries.get(s.id());
        }

        /** @return the subprocess whose element holds the flow of this transition, or null for the process */
        WorkflowDefinition.Subprocess container(Transition t) {
            return scopeOf.get(t.from());
        }

        /** @return the BPMN target of a transition: the step, a section it enters, or a generated end event */
        String target(Transition t) {
            WorkflowDefinition.Subprocess from = scopeOf.get(t.from());
            WorkflowDefinition.Subprocess to = scopeOf.get(t.to());
            if (from == to) {
                return t.to();
            }
            if (from != null) {
                return from.id() + SCOPE_END + t.to();
            }
            return outerTarget(t.to());
        }

        /** @return the element a flow from outside every subprocess goes to for this step */
        String outerTarget(String nodeId) {
            WorkflowDefinition.Subprocess entered = entryOf.get(nodeId);
            return entered == null ? nodeId : entered.id();
        }

        /** @return the steps outside a section that its paths lead to, in canonical path order */
        java.util.List<String> exits(WorkflowDefinition.Subprocess s) {
            java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
            for (Transition t : d.transitions()) {
                if (scopeOf.get(t.from()) == s && scopeOf.get(t.to()) != s) {
                    targets.add(t.to());
                }
            }
            return java.util.List.copyOf(targets);
        }

        /** @return the steps of a handler that runs alongside the request that have no path: they end it */
        java.util.List<String> sideEnds(WorkflowDefinition.Subprocess s) {
            if (!s.event() || s.interrupting()) {
                return java.util.List.of();
            }
            java.util.List<String> ends = new java.util.ArrayList<>();
            for (String id : new java.util.LinkedHashSet<>(s.nodes())) {
                if (scopeOf.get(id) == s && d.transitions().stream().noneMatch(t -> t.from().equals(id))) {
                    ends.add(id);
                }
            }
            return ends;
        }

        /** @return true when a handler can run alongside the request (a finished request then stops it) */
        boolean sideHandlers() {
            return d.subprocesses().stream().anyMatch(s -> s.event() && !s.interrupting());
        }

        java.util.SortedSet<String> errorCodes() {
            java.util.SortedSet<String> codes = new java.util.TreeSet<>();
            for (Node n : d.nodes()) {
                if (n instanceof Node.RaiseError r) {
                    codes.add(r.errorCode());
                }
                if (n instanceof Node.Http h && h.raiseOnFailure() != null) {
                    codes.add(h.raiseOnFailure());
                }
            }
            for (WorkflowDefinition.Subprocess s : d.subprocesses()) {
                if (s.errorCode() != null) {
                    codes.add(s.errorCode());
                }
                if (s.triggerErrorCode() != null) {
                    codes.add(s.triggerErrorCode());
                }
            }
            return codes;
        }

        java.util.SortedSet<String> signals() {
            java.util.SortedSet<String> names = new java.util.TreeSet<>();
            for (Node n : d.nodes()) {
                if (n instanceof Node.SendSignal s) {
                    names.add(s.signal());
                } else if (n instanceof Node.WaitForSignal w) {
                    names.add(w.signal());
                }
            }
            d.subprocesses().stream().filter(s -> s.signal() != null).forEach(s -> names.add(s.signal()));
            return names;
        }
    }

    private static void flow(XmlOut x, SourceMap map, Map<String, Node> nodes, Transition t, String target) {
        map.transition(t.id(), t.id());
        Node from = nodes.get(t.from());
        String source = flowSource(from, t);
        String expression = switch (t.on()) {
            case APPROVED -> "${orqixRoute.approved(execution, '" + from.id() + "')}";
            case REJECTED -> "${orqixRoute.rejected(execution, '" + from.id() + "')}";
            case CONDITION -> "${orqixRoute.condition(execution, '" + t.id() + "')}";
            case NEXT, DEFAULT, TIMED_OUT, FAILED -> null;
        };
        if (expression == null) {
            x.leaf("", "sequenceFlow", BPMN_NS);
            flowAttributes(x, t, source, target);
            return;
        }
        x.open("", "sequenceFlow", BPMN_NS);
        flowAttributes(x, t, source, target);
        if (t.condition() != null) {
            Condition c = t.condition();
            x.open("", "extensionElements", BPMN_NS);
            if (c.compound()) {
                x.open("orqix", "condition", ORQIX_NS).attr("group", c.group());
                for (Condition term : c.terms()) x.leaf("orqix", "term", ORQIX_NS)
                        .attr("fieldRef", term.fieldRef()).attr("operator", term.operator().json())
                        .attr("valueType", term.value().typeName()).attr("value", term.value().text());
                x.close();
            } else x.leaf("orqix", "condition", ORQIX_NS)
                    .attr("fieldRef", c.fieldRef())
                    .attr("operator", c.operator().json())
                    .attr("valueType", c.value().typeName())
                    .attr("value", c.value().text());
            x.close();
        }
        x.openInline("", "conditionExpression", BPMN_NS)
                .attr("xsi", XSI_NS, "type", "tFormalExpression")
                .cdata(expression)
                .closeInline();
        x.close();
    }

    /**
     * Error boundary event for a step that has a {@code failed} path: the delegate raises {@link #FAILED_ERROR_CODE}
     * only for failures the designer chose to route (S32). Steps without that path get nothing, so existing workflows
     * generate exactly the same process as before.
     */
    private static void failureBoundary(XmlOut x, SourceMap map, WorkflowDefinition d, Node n) {
        if (d.transitions().stream().noneMatch(t -> t.on() == Route.FAILED && t.from().equals(n.id()))) {
            return;
        }
        String id = n.id() + FAILED_BOUNDARY;
        x.open("", "boundaryEvent", BPMN_NS)
                .attr("id", id)
                .attr("name", n.label() + ": failed")
                .attr("attachedToRef", n.id())
                .attr("cancelActivity", "true");
        x.leaf("", "errorEventDefinition", BPMN_NS).attr("errorRef", FAILED_ERROR);
        x.close();
        map.node(n.id(), id);
    }

    /** BPMN element a canonical transition leaves from (approvals leave through their generated helpers). */
    private static String flowSource(Node from, Transition t) {
        if (t.on() == Route.FAILED) {
            return from.id() + FAILED_BOUNDARY;
        }
        if (from instanceof Node.Approval) {
            return from.id() + (t.on() == Route.TIMED_OUT ? APPROVAL_TIMEOUT : APPROVAL_ROUTE);
        }
        if (from instanceof Node.WaitForMessage m && m.timeoutAfter() != null) {
            return from.id() + (t.on() == Route.TIMED_OUT ? MESSAGE_TIMEOUT : MESSAGE_CATCH);
        }
        return from.id();
    }

    private static void flowAttributes(XmlOut x, Transition t, String source, String target) {
        x.attr("id", t.id());
        if (t.label() != null) {
            x.attr("name", t.label());
        }
        x.attr("sourceRef", source).attr("targetRef", target);
    }

    /** BPMN diagram interchange from {@code uiLayout} (ticket B03.7, priority P2). */
    static final class Diagram {

        /** Canvas card size (S7): positions are the top-left corner of a 220 x 72 card. */
        private static final BigDecimal CARD_HALF_WIDTH = new BigDecimal(110);
        private static final BigDecimal CARD_HALF_HEIGHT = new BigDecimal(36);
        private static final BigDecimal HELPER_OFFSET = new BigDecimal(90);
        private static final BigDecimal BOUNDARY_OFFSET = new BigDecimal(40);

        private Diagram() {
        }

        static void write(XmlOut x, WorkflowDefinition d, Map<String, Node> nodes, Scopes scopes) {
            if (d.uiLayout() == null || !d.nodes().stream().allMatch(n ->
                    d.uiLayout().positions().containsKey(n.id()))) {
                return;
            }
            Map<String, BigDecimal[]> centre = new HashMap<>();
            for (Node n : d.nodes()) {
                Position p = d.uiLayout().positions().get(n.id());
                BigDecimal cx = p.x().add(CARD_HALF_WIDTH);
                BigDecimal cy = p.y().add(CARD_HALF_HEIGHT);
                centre.put(n.id(), new BigDecimal[] {cx, cy});
                if (n instanceof Node.Approval a) {
                    centre.put(n.id() + APPROVAL_ROUTE, new BigDecimal[] {cx.add(HELPER_OFFSET), cy});
                    if (a.timeoutAfter() != null) {
                        centre.put(n.id() + APPROVAL_TIMEOUT, new BigDecimal[] {cx, cy.add(BOUNDARY_OFFSET)});
                    }
                }
                if (d.transitions().stream().anyMatch(t -> t.on() == Route.FAILED && t.from().equals(n.id()))) {
                    centre.put(n.id() + FAILED_BOUNDARY, new BigDecimal[] {cx, cy.add(BOUNDARY_OFFSET)});
                }
                if (n instanceof Node.WaitForMessage m && m.timeoutAfter() != null) {
                    centre.put(n.id() + MESSAGE_CATCH, new BigDecimal[] {cx.add(HELPER_OFFSET), cy.subtract(BOUNDARY_OFFSET)});
                    centre.put(n.id() + MESSAGE_TIMEOUT, new BigDecimal[] {cx.add(HELPER_OFFSET), cy.add(BOUNDARY_OFFSET)});
                }
            }
            Map<String, BigDecimal[]> boxes = scopeCentres(d, scopes, centre);
            String key = d.workflowKey();
            x.open("bpmndi", "BPMNDiagram", BPMNDI_NS).attr("id", key + "__diagram");
            x.open("bpmndi", "BPMNPlane", BPMNDI_NS).attr("id", key + "__plane").attr("bpmnElement", key);
            for (Node n : d.nodes()) {
                int[] size = switch (n.kind()) {
                    case START, END, WAIT, RAISE_ERROR, SEND_SIGNAL, WAIT_FOR_SIGNAL -> new int[] {36, 36};
                    case WAIT_FOR_MESSAGE -> ((Node.WaitForMessage) n).timeoutAfter() != null
                            ? new int[] {40, 40} : new int[] {36, 36};
                    case BRANCH, PARALLEL_SPLIT, PARALLEL_JOIN -> new int[] {40, 40};
                    default -> new int[] {100, 80};
                };
                shape(x, n.id(), centre.get(n.id()), size[0], size[1]);
                if (centre.containsKey(n.id() + FAILED_BOUNDARY)) {
                    shape(x, n.id() + FAILED_BOUNDARY, centre.get(n.id() + FAILED_BOUNDARY), 36, 36);
                }
                if (n instanceof Node.WaitForMessage m && m.timeoutAfter() != null) {
                    shape(x, n.id() + MESSAGE_CATCH, centre.get(n.id() + MESSAGE_CATCH), 36, 36);
                    shape(x, n.id() + MESSAGE_TIMEOUT, centre.get(n.id() + MESSAGE_TIMEOUT), 36, 36);
                    edge(x, n.id() + "__toMessage", centre.get(n.id()), centre.get(n.id() + MESSAGE_CATCH));
                    edge(x, n.id() + "__toTimeout", centre.get(n.id()), centre.get(n.id() + MESSAGE_TIMEOUT));
                }
                if (n instanceof Node.Approval a) {
                    shape(x, n.id() + APPROVAL_ROUTE, centre.get(n.id() + APPROVAL_ROUTE), 40, 40);
                    edge(x, n.id() + APPROVAL_OUT, centre.get(n.id()), centre.get(n.id() + APPROVAL_ROUTE));
                    if (a.timeoutAfter() != null) {
                        shape(x, n.id() + APPROVAL_TIMEOUT, centre.get(n.id() + APPROVAL_TIMEOUT), 36, 36);
                    }
                }
            }
            for (Transition t : d.transitions()) {
                Node from = nodes.get(t.from());
                edge(x, t.id(), centre.get(flowSource(from, t)), centre.get(scopes.target(t)));
            }
            scopeDiagram(x, d, scopes, centre, boxes);
            x.close();
            x.close();
        }

        private static final BigDecimal SCOPE_PADDING = new BigDecimal(40);

        /** @return subprocess id to {left, top, right, bottom}; also places the generated subprocess helpers (S55) */
        private static Map<String, BigDecimal[]> scopeCentres(WorkflowDefinition d, Scopes scopes,
                Map<String, BigDecimal[]> centre) {
            Map<String, BigDecimal[]> boxes = new HashMap<>();
            for (WorkflowDefinition.Subprocess s : d.subprocesses()) {
                BigDecimal left = null, top = null, right = null, bottom = null;
                for (String id : s.nodes()) {
                    Position p = d.uiLayout().positions().get(id);
                    if (p == null) {
                        continue;
                    }
                    BigDecimal l = p.x(), t = p.y(), r = p.x().add(CARD_HALF_WIDTH.add(CARD_HALF_WIDTH)),
                            b = p.y().add(CARD_HALF_HEIGHT.add(CARD_HALF_HEIGHT));
                    left = left == null ? l : left.min(l);
                    top = top == null ? t : top.min(t);
                    right = right == null ? r : right.max(r);
                    bottom = bottom == null ? b : bottom.max(b);
                }
                if (left == null) {
                    continue;
                }
                left = left.subtract(SCOPE_PADDING);
                top = top.subtract(SCOPE_PADDING);
                right = right.add(SCOPE_PADDING);
                bottom = bottom.add(SCOPE_PADDING);
                boxes.put(s.id(), new BigDecimal[] {left, top, right, bottom});
                BigDecimal midY = top.add(bottom).divide(BigDecimal.valueOf(2));
                BigDecimal midX = left.add(right).divide(BigDecimal.valueOf(2));
                String id = s.id();
                centre.put(id, new BigDecimal[] {midX, midY});
                centre.put(id + SCOPE_START, new BigDecimal[] {left.add(BigDecimal.valueOf(20)), midY});
                BigDecimal y = top.add(BigDecimal.valueOf(20));
                for (String exit : scopes.exits(s)) {
                    centre.put(id + SCOPE_END + exit, new BigDecimal[] {right.subtract(BigDecimal.valueOf(20)), y});
                    y = y.add(BigDecimal.valueOf(40));
                }
                centre.put(id + SCOPE_DONE, new BigDecimal[] {right.subtract(BigDecimal.valueOf(20)), bottom.subtract(BigDecimal.valueOf(20))});
                centre.put(id + SCOPE_EXIT, new BigDecimal[] {right.add(BigDecimal.valueOf(60)), midY});
                centre.put(id + SCOPE_TIMEOUT, new BigDecimal[] {left.add(BigDecimal.valueOf(40)), bottom});
                centre.put(id + SCOPE_ERROR, new BigDecimal[] {left.add(BigDecimal.valueOf(90)), bottom});
            }
            return boxes;
        }

        private static void scopeDiagram(XmlOut x, WorkflowDefinition d, Scopes scopes, Map<String, BigDecimal[]> centre,
                Map<String, BigDecimal[]> boxes) {
            for (WorkflowDefinition.Subprocess s : d.subprocesses()) {
                BigDecimal[] box = boxes.get(s.id());
                if (box == null) {
                    continue;
                }
                String id = s.id();
                x.open("bpmndi", "BPMNShape", BPMNDI_NS).attr("id", id + "__di").attr("bpmnElement", id)
                        .attr("isExpanded", "true");
                x.leaf("omgdc", "Bounds", DC_NS).attr("x", num(box[0])).attr("y", num(box[1]))
                        .attr("width", num(box[2].subtract(box[0]))).attr("height", num(box[3].subtract(box[1])));
                x.close();
                shape(x, id + SCOPE_START, centre.get(id + SCOPE_START), 30, 30);
                edge(x, id + "__begin", centre.get(id + SCOPE_START), centre.get(scopes.entry(s)));
                java.util.List<String> exits = scopes.exits(s);
                for (String exit : exits) {
                    shape(x, id + SCOPE_END + exit, centre.get(id + SCOPE_END + exit), 30, 30);
                }
                java.util.List<String> done = scopes.sideEnds(s);
                if (!done.isEmpty()) {
                    shape(x, id + SCOPE_DONE, centre.get(id + SCOPE_DONE), 30, 30);
                    for (String last : done) {
                        edge(x, id + "__done__" + last, centre.get(last), centre.get(id + SCOPE_DONE));
                    }
                }
                if (s.event()) {
                    continue;
                }
                if (exits.size() == 1) {
                    edge(x, id + SCOPE_EXIT + "__" + exits.get(0), centre.get(id),
                            centre.get(scopes.outerTarget(exits.get(0))));
                } else if (exits.size() > 1) {
                    shape(x, id + SCOPE_EXIT, centre.get(id + SCOPE_EXIT), 40, 40);
                    edge(x, id + "__out", centre.get(id), centre.get(id + SCOPE_EXIT));
                    for (String exit : exits) {
                        edge(x, id + SCOPE_EXIT + "__" + exit, centre.get(id + SCOPE_EXIT),
                                centre.get(scopes.outerTarget(exit)));
                    }
                }
                if (s.timeoutAfter() != null && s.onTimeout() != null) {
                    shape(x, id + SCOPE_TIMEOUT, centre.get(id + SCOPE_TIMEOUT), 36, 36);
                    edge(x, id + "__onTimeout", centre.get(id + SCOPE_TIMEOUT), centre.get(scopes.outerTarget(s.onTimeout())));
                }
                if (s.errorTo() != null) {
                    shape(x, id + SCOPE_ERROR, centre.get(id + SCOPE_ERROR), 36, 36);
                    edge(x, id + "__onError", centre.get(id + SCOPE_ERROR), centre.get(scopes.outerTarget(s.errorTo())));
                }
            }
        }

        private static void shape(XmlOut x, String element, BigDecimal[] c, int width, int height) {
            x.open("bpmndi", "BPMNShape", BPMNDI_NS).attr("id", element + "__di").attr("bpmnElement", element);
            x.leaf("omgdc", "Bounds", DC_NS)
                    .attr("x", num(c[0].subtract(BigDecimal.valueOf(width / 2))))
                    .attr("y", num(c[1].subtract(BigDecimal.valueOf(height / 2))))
                    .attr("width", Integer.toString(width))
                    .attr("height", Integer.toString(height));
            x.close();
        }

        private static void edge(XmlOut x, String element, BigDecimal[] from, BigDecimal[] to) {
            x.open("bpmndi", "BPMNEdge", BPMNDI_NS).attr("id", element + "__di").attr("bpmnElement", element);
            x.leaf("omgdi", "waypoint", DI_NS).attr("x", num(from[0])).attr("y", num(from[1]));
            x.leaf("omgdi", "waypoint", DI_NS).attr("x", num(to[0])).attr("y", num(to[1]));
            x.close();
        }

        private static String num(BigDecimal value) {
            BigDecimal v = value.stripTrailingZeros();
            return v.signum() == 0 ? "0" : v.toPlainString();
        }
    }
}
