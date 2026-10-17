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
            node(x, map, definition, n, branchDefault, decisionResource, children);
        }
        for (Transition t : definition.transitions()) {
            flow(x, map, nodes, t);
        }
        x.close();
        Diagram.write(x, definition, nodes);
        x.close();
        return new Result(x.finish(), map);
    }

    private static void node(XmlOut x, SourceMap map, WorkflowDefinition d, Node n,
                             Map<String, String> branchDefault, Map<String, String> decisionResource,
                             Map<String, WorkflowInfo> children) {
        map.node(n.id(), n.id());
        switch (n) {
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
                if (a.timeoutAfter() != null) {
                    x.attr("timeoutAfter", a.timeoutAfter());
                }
                if (!a.requiredSkills().isEmpty()) x.attr("requiredSkills", String.join(",", a.requiredSkills()));
                if (a.calendar() != null) x.attr("calendar", a.calendar().toString());
                if (a.escalation() != null) x.attr("escalation", a.escalation().toString());
                x.close();
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
            case Node.ParallelSplit p -> x.leaf("", "parallelGateway", BPMN_NS).attr("id", p.id())
                    .attr("name", p.label());
            case Node.ParallelJoin j -> x.leaf("", "parallelGateway", BPMN_NS)
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
                if (("rejected".equals(e.outcomeKind()) || "failed".equals(e.outcomeKind()))
                        && d.nodes().stream().anyMatch(other -> other instanceof Node.ParallelSplit)) {
                    // A rejection ends the whole request, including parallel branches that are still open (S12).
                    x.leaf("", "terminateEventDefinition", BPMN_NS);
                }
                x.close();
            }
        }
    }

    private static void flow(XmlOut x, SourceMap map, Map<String, Node> nodes, Transition t) {
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
            flowAttributes(x, t, source);
            return;
        }
        x.open("", "sequenceFlow", BPMN_NS);
        flowAttributes(x, t, source);
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

    private static void flowAttributes(XmlOut x, Transition t, String source) {
        x.attr("id", t.id());
        if (t.label() != null) {
            x.attr("name", t.label());
        }
        x.attr("sourceRef", source).attr("targetRef", t.to());
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

        static void write(XmlOut x, WorkflowDefinition d, Map<String, Node> nodes) {
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
            String key = d.workflowKey();
            x.open("bpmndi", "BPMNDiagram", BPMNDI_NS).attr("id", key + "__diagram");
            x.open("bpmndi", "BPMNPlane", BPMNDI_NS).attr("id", key + "__plane").attr("bpmnElement", key);
            for (Node n : d.nodes()) {
                int[] size = switch (n.kind()) {
                    case START, END, WAIT -> new int[] {36, 36};
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
                edge(x, t.id(), centre.get(flowSource(from, t)), centre.get(t.to()));
            }
            x.close();
            x.close();
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
