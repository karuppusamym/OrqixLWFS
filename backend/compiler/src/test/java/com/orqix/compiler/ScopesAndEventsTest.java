package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * EX06/EX08 slice (docs/build/S55): sections (embedded subprocesses) with a time limit, error route and several exits;
 * handlers (event subprocesses) started by an error, a signal or a timer; raised errors; signals within a request;
 * automatic undo calls run by an "Undo completed calls" step.
 */
class ScopesAndEventsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String INPUT = "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"%s\",\"amount\":%s}";

    private static JsonNode n(String json) {
        return MAPPER.readTree(json);
    }

    private static void add(ObjectNode d, String node, int x, int y) {
        JsonNode parsed = n(node);
        ((ArrayNode) d.path("nodes")).add(parsed);
        if (d.has("uiLayout")) {
            ((ObjectNode) d.path("uiLayout").path("positions")).set(parsed.path("id").asString(),
                    n("{\"x\":" + x + ",\"y\":" + y + "}"));
        }
    }

    private static void path(ObjectNode d, String json) {
        ((ArrayNode) d.path("transitions")).add(n(json));
    }

    private static void retarget(ObjectNode d, String id, String to) {
        for (JsonNode t : d.path("transitions")) {
            if (id.equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", to);
            }
        }
    }

    private static void removePath(ObjectNode d, String id) {
        ArrayNode ts = (ArrayNode) d.path("transitions");
        for (int i = 0; i < ts.size(); i++) {
            if (id.equals(ts.get(i).path("id").asString())) {
                ts.remove(i);
                return;
            }
        }
    }

    private static ObjectNode scope(ObjectNode d, String json) {
        if (!d.has("subprocesses")) {
            d.putArray("subprocesses");
        }
        ObjectNode s = (ObjectNode) n(json);
        ((ArrayNode) d.path("subprocesses")).add(s);
        return s;
    }

    private static ObjectNode config(ObjectNode d, String id) {
        for (JsonNode x : d.path("nodes")) {
            if (id.equals(x.path("id").asString())) {
                return (ObjectNode) x.path("config");
            }
        }
        throw new AssertionError(id);
    }

    private static List<String> blockers(ObjectNode d) {
        return issues(d, Severity.BLOCKER);
    }

    private static List<String> issues(ObjectNode d, Severity severity) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues()
                .stream().filter(i -> i.severity() == severity).map(ValidationIssue::code).toList();
    }

    private static String bpmn(ObjectNode d) throws Exception {
        String xml = Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace()).bpmnXml();
        GeneratedXmlTest.onlyAllowedExpressions(xml);
        return xml;
    }

    /**
     * Purchase approval whose review (a limit check, the manager, the policy, the routing and finance) is a section:
     * entered at the limit check, left to the rejection notice or to the purchase order, with a 5-day time limit
     * (continue at the rejection notice) and an error route for TOO_EXPENSIVE (raised for 100,000 or more).
     */
    static ObjectNode section() {
        ObjectNode d = TestSupport.purchase();
        add(d, "{\"id\":\"limit\",\"type\":\"branch\",\"label\":\"Within the limit?\",\"config\":{}}", 100, 100);
        add(d, "{\"id\":\"tooBig\",\"type\":\"raiseError\",\"label\":\"Over the limit\",\"config\":{\"errorCode\":\"TOO_EXPENSIVE\"}}", 100, 300);
        add(d, "{\"id\":\"limitNotice\",\"type\":\"notification\",\"label\":\"Tell the requester\",\"config\":{\"channel\":\"inApp\",\"recipient\":\"initiator\",\"message\":\"Over the limit\"}}", 100, 500);
        retarget(d, "t1", "limit");
        path(d, "{\"id\":\"l1\",\"from\":\"limit\",\"to\":\"tooBig\",\"on\":\"condition\",\"label\":\"100,000 or more\","
                + "\"condition\":{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":100000}}");
        path(d, "{\"id\":\"l2\",\"from\":\"limit\",\"to\":\"manager\",\"on\":\"default\"}");
        path(d, "{\"id\":\"l3\",\"from\":\"limitNotice\",\"to\":\"rejected\",\"on\":\"next\"}");
        scope(d, "{\"id\":\"review\",\"label\":\"Review\",\"type\":\"embedded\",\"nodes\":[\"limit\",\"tooBig\",\"manager\","
                + "\"amountPolicy\",\"route\",\"finance\"],\"timeoutAfter\":\"P5D\",\"onTimeout\":\"notifyRejected\","
                + "\"onError\":{\"to\":\"limitNotice\",\"errorCode\":\"TOO_EXPENSIVE\"}}");
        return d;
    }

    @Test
    void aSectionCompilesToAnEmbeddedSubprocessWithBoundariesAndAnExitGateway() throws Exception {
        ObjectNode d = section();
        assertThat(blockers(d)).isEmpty();
        String xml = bpmn(d);
        assertThat(xml).contains("<subProcess id=\"review\" name=\"Review\">", "<startEvent id=\"review__start\"",
                "<sequenceFlow id=\"review__begin\" sourceRef=\"review__start\" targetRef=\"limit\"/>",
                // the start step leads to the section, not into it
                "<sequenceFlow id=\"t1\" sourceRef=\"start\" targetRef=\"review\"/>",
                // leaving the section ends at a generated end event per target, then a gateway continues there
                "<endEvent id=\"review__end__notifyRejected\"", "<endEvent id=\"review__end__procure\"",
                "orqixRoute.markExit(execution, 'review', 'procure')",
                "<exclusiveGateway id=\"review__exit\"", "orqixRoute.exited(execution, 'review', 'notifyRejected')",
                "<boundaryEvent id=\"review__timeout\" name=\"Review: time limit passed\" attachedToRef=\"review\" cancelActivity=\"true\">",
                "<timeDuration>P5D</timeDuration>",
                "<boundaryEvent id=\"review__error\" name=\"Review: error\" attachedToRef=\"review\" cancelActivity=\"true\">",
                "<errorEventDefinition errorRef=\"error__TOO_EXPENSIVE\"/>",
                "<error id=\"error__TOO_EXPENSIVE\" errorCode=\"TOO_EXPENSIVE\"/>",
                "<endEvent id=\"tooBig\" name=\"Over the limit\">",
                "<sequenceFlow id=\"review__onError\" sourceRef=\"review__error\" targetRef=\"limitNotice\"/>",
                "<bpmndi:BPMNShape id=\"review__di\" bpmnElement=\"review\" isExpanded=\"true\">");
        // the members are inside the subprocess element
        int open = xml.indexOf("<subProcess id=\"review\"");
        int close = xml.indexOf("</subProcess>", open);
        assertThat(xml.indexOf("<userTask id=\"manager\"")).isBetween(open, close);
        assertThat(xml.indexOf("<userTask id=\"finance\"")).isBetween(open, close);
        int procure = xml.indexOf("<serviceTask id=\"procure\""); assertThat(procure > 0 && (procure < open || procure > close)).isTrue();
    }

    @Test
    void aSectionWithOneExitContinuesWithoutAGateway() throws Exception {
        ObjectNode d = TestSupport.purchase();
        scope(d, "{\"id\":\"order\",\"label\":\"Order\",\"type\":\"embedded\",\"nodes\":[\"procure\",\"notify\"]}");
        assertThat(blockers(d)).isEmpty();
        String xml = bpmn(d);
        assertThat(xml).contains("<sequenceFlow id=\"order__exit__fulfilled\" sourceRef=\"order\" targetRef=\"fulfilled\"/>")
                .doesNotContain("order__exit\"", "markExit");
    }

    @Test
    void sectionStructureIsChecked() {
        // Paths from outside must all enter at one step.
        ObjectNode twoEntries = section();
        path(twoEntries, "{\"id\":\"x1\",\"from\":\"limitNotice\",\"to\":\"finance\",\"on\":\"next\"}");
        removePath(twoEntries, "l3");
        assertThat(blockers(twoEntries)).contains("WF_SUBPROCESS_ENTRY");
        // A Finish step cannot be inside a section, nor a step in two subprocesses.
        ObjectNode end = section();
        ((ArrayNode) end.path("subprocesses").get(0).path("nodes")).add("rejected");
        assertThat(blockers(end)).contains("WF_SUBPROCESS_STEP_KIND");
        ObjectNode twice = section();
        scope(twice, "{\"id\":\"again\",\"label\":\"Again\",\"type\":\"embedded\",\"nodes\":[\"manager\"]}");
        assertThat(blockers(twice)).contains("WF_SUBPROCESS_NODE_DUPLICATE");
        // Its ID must be its own.
        ObjectNode clash = section();
        ((ObjectNode) clash.path("subprocesses").get(0)).put("id", "manager");
        assertThat(blockers(clash)).contains("WF_SUBPROCESS_DUPLICATE_ID");
        // A time limit needs a target outside the section that does not lead back into it.
        ObjectNode inside = section();
        ((ObjectNode) inside.path("subprocesses").get(0)).put("onTimeout", "finance");
        assertThat(blockers(inside)).contains("WF_SUBPROCESS_ROUTE_TARGET");
        ObjectNode half = section();
        ((ObjectNode) half.path("subprocesses").get(0)).remove("onTimeout");
        assertThat(blockers(half)).contains("WF_SUBPROCESS_TIMEOUT");
        ObjectNode tooLong = section();
        ((ObjectNode) tooLong.path("subprocesses").get(0)).put("timeoutAfter", "P200D");
        assertThat(blockers(tooLong)).contains("WF_DURATION_TOO_LONG");
    }

    @Test
    void dataProducedInsideASectionIsNotGuaranteedOnItsTimeLimitOrErrorRoute() {
        ObjectNode d = section();
        d.set("outputSchema", n("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"decision\":"
                + "{\"type\":\"string\",\"title\":\"Decision\"}}}"));
        d.set("outputMapping", n("{\"decision\":{\"fieldRef\":\"/steps/manager/decision\",\"required\":true}}"));
        // The time limit or the error can end the section before the manager decided.
        assertThat(blockers(d)).contains("WF_OUTPUT_NOT_GUARANTEED");
        ObjectNode noRoutes = d.deepCopy();
        ((ObjectNode) noRoutes.path("subprocesses").get(0)).remove(List.of("timeoutAfter", "onTimeout", "onError"));
        // Without them (and without the error step) every path passes the manager.
        ArrayNode members = (ArrayNode) noRoutes.path("subprocesses").get(0).path("nodes");
        members.remove(1);
        removePath(noRoutes, "l1");
        retarget(noRoutes, "t1", "manager");
        removePath(noRoutes, "l2");
        ((ArrayNode) noRoutes.path("subprocesses").get(0).path("nodes")).remove(0);
        ArrayNode nodes = (ArrayNode) noRoutes.path("nodes");
        for (int i = nodes.size() - 1; i >= 0; i--) {
            String id = nodes.get(i).path("id").asString();
            if (List.of("limit", "tooBig", "limitNotice").contains(id)) {
                nodes.remove(i);
                ((ObjectNode) noRoutes.path("uiLayout").path("positions")).remove(id);
            }
        }
        removePath(noRoutes, "l3");
        assertThat(blockers(noRoutes)).isEmpty();
    }

    @Test
    void raisedErrorsMustBeCaughtAndPlatformCodesAreReserved() {
        ObjectNode uncaught = section();
        ((ObjectNode) uncaught.path("subprocesses").get(0)).remove("onError");
        assertThat(blockers(uncaught)).contains("WF_ERROR_NOT_CAUGHT");
        // A different code is not caught either...
        ObjectNode other = section();
        ((ObjectNode) other.path("subprocesses").get(0).path("onError")).put("errorCode", "SOMETHING_ELSE");
        assertThat(blockers(other)).contains("WF_ERROR_NOT_CAUGHT");
        // ...unless an error handler for any code exists.
        ObjectNode handled = other.deepCopy();
        add(handled, "{\"id\":\"sorry\",\"type\":\"end\",\"label\":\"Stopped\",\"config\":{\"businessOutcome\":\"Stopped\","
                + "\"outcomeKind\":\"failed\"}}", 900, 900);
        scope(handled, "{\"id\":\"onAnyError\",\"label\":\"Any error\",\"type\":\"event\",\"nodes\":[\"sorry\"],"
                + "\"trigger\":{\"type\":\"error\"},\"interrupting\":true}");
        assertThat(blockers(handled)).doesNotContain("WF_ERROR_NOT_CAUGHT");
        ObjectNode reserved = section();
        config(reserved, "tooBig").put("errorCode", "ORQIX_STEP_FAILED");
        assertThat(blockers(reserved)).contains("WF_ERROR_CODE_RESERVED");
        ObjectNode leaves = section();
        path(leaves, "{\"id\":\"x2\",\"from\":\"tooBig\",\"to\":\"manager\",\"on\":\"next\"}");
        assertThat(blockers(leaves)).contains("WF_OUTGOING_INVALID");
    }

    /** Purchase approval plus a withdraw handler (interrupting, on a signal) and a reminder (alongside, on a timer). */
    static ObjectNode handlers() {
        ObjectNode d = TestSupport.purchase();
        add(d, "{\"id\":\"withdrawn\",\"type\":\"end\",\"label\":\"Withdrawn\",\"config\":{\"businessOutcome\":\"Withdrawn\","
                + "\"outcomeKind\":\"rejected\"}}", 900, 700);
        add(d, "{\"id\":\"sayWithdrawn\",\"type\":\"notification\",\"label\":\"Confirm withdrawal\",\"config\":{\"channel\":\"inApp\","
                + "\"recipient\":\"initiator\",\"message\":\"Your request was withdrawn\"}}", 600, 700);
        add(d, "{\"id\":\"remind\",\"type\":\"notification\",\"label\":\"Still open\",\"config\":{\"channel\":\"inApp\","
                + "\"recipient\":\"initiator\",\"message\":\"Your request is still open\"}}", 600, 900);
        path(d, "{\"id\":\"w1\",\"from\":\"sayWithdrawn\",\"to\":\"withdrawn\",\"on\":\"next\"}");
        scope(d, "{\"id\":\"withdraw\",\"label\":\"Withdraw\",\"type\":\"event\",\"nodes\":[\"sayWithdrawn\",\"withdrawn\"],"
                + "\"trigger\":{\"type\":\"signal\",\"signal\":\"withdraw\"},\"interrupting\":true}");
        scope(d, "{\"id\":\"reminder\",\"label\":\"Reminder\",\"type\":\"event\",\"nodes\":[\"remind\"],"
                + "\"trigger\":{\"type\":\"timer\",\"after\":\"P3D\"},\"interrupting\":false}");
        return d;
    }

    @Test
    void handlersCompileToEventSubprocesses() throws Exception {
        ObjectNode d = handlers();
        assertThat(blockers(d)).isEmpty();
        String xml = bpmn(d);
        assertThat(xml).contains("<subProcess id=\"withdraw\" name=\"Withdraw\" triggeredByEvent=\"true\">",
                "<startEvent id=\"withdraw__start\" name=\"Withdraw: start\" isInterrupting=\"true\">",
                "<signalEventDefinition signalRef=\"signal__withdraw\"/>",
                "<signal id=\"signal__withdraw\" name=\"withdraw\" flowable:scope=\"processInstance\"/>",
                "<startEvent id=\"reminder__start\" name=\"Reminder: start\" isInterrupting=\"false\">",
                "<timeDuration>P3D</timeDuration>",
                // the reminder's last step simply ends; finishing the request stops a handler still running
                "<sequenceFlow id=\"reminder__done__remind\" sourceRef=\"remind\" targetRef=\"reminder__end\"/>",
                "<terminateEventDefinition/>");
        // The handler's own Finish step is not a terminate end (the handler already stopped everything else).
        int open = xml.indexOf("<endEvent id=\"withdrawn\"");
        assertThat(xml.substring(open, xml.indexOf("</endEvent>", open))).doesNotContain("terminate");
    }

    @Test
    void handlerStructureIsChecked() {
        ObjectNode leaves = handlers();
        path(leaves, "{\"id\":\"x3\",\"from\":\"remind\",\"to\":\"fulfilled\",\"on\":\"next\"}");
        assertThat(blockers(leaves)).contains("WF_SUBPROCESS_HANDLER_PATH");
        ObjectNode errorAlongside = handlers();
        ((ObjectNode) errorAlongside.path("subprocesses").get(1)).set("trigger", n("{\"type\":\"error\"}"));
        assertThat(blockers(errorAlongside)).contains("WF_SUBPROCESS_SETTING");
        ObjectNode endAlongside = handlers();
        ((ObjectNode) endAlongside.path("subprocesses").get(0)).put("interrupting", false);
        assertThat(blockers(endAlongside)).contains("WF_SUBPROCESS_STEP_KIND");
        ObjectNode sameSignal = handlers();
        ((ObjectNode) sameSignal.path("subprocesses").get(1)).set("trigger", n("{\"type\":\"signal\",\"signal\":\"withdraw\"}"));
        assertThat(blockers(sameSignal)).contains("WF_SUBPROCESS_TRIGGER_DUPLICATE");
        // A handler cannot use what the request produced on the way, only what the request form guarantees.
        ObjectNode data = handlers();
        config(data, "remind").put("message", "Still open");
        assertThat(blockers(data)).isEmpty();
    }

    @Test
    void signalsStayInsideTheRequest() throws Exception {
        ObjectNode d = TestSupport.purchase();
        add(d, "{\"id\":\"announce\",\"type\":\"sendSignal\",\"label\":\"Announce\",\"config\":{\"signal\":\"order-placed\"}}", 900, 300);
        retarget(d, "t9", "announce");
        path(d, "{\"id\":\"s1\",\"from\":\"announce\",\"to\":\"notify\",\"on\":\"next\"}");
        assertThat(issues(d, Severity.WARNING)).contains("WF_SIGNAL_NOT_WAITED");
        String xml = bpmn(d);
        assertThat(xml).contains("<intermediateThrowEvent id=\"announce\" name=\"Announce\">",
                "<signal id=\"signal__order-placed\" name=\"order-placed\" flowable:scope=\"processInstance\"/>");
        ObjectNode waits = TestSupport.purchase();
        add(waits, "{\"id\":\"await\",\"type\":\"waitForSignal\",\"label\":\"Wait for goods\",\"config\":{\"signal\":\"goods-received\"}}", 900, 300);
        retarget(waits, "t9", "await");
        path(waits, "{\"id\":\"s2\",\"from\":\"await\",\"to\":\"notify\",\"on\":\"next\"}");
        assertThat(blockers(waits)).isEmpty();
        assertThat(bpmn(waits)).contains("<intermediateCatchEvent id=\"await\" name=\"Wait for goods\">",
                "<signalEventDefinition signalRef=\"signal__goods-received\"/>");
    }

    /**
     * Purchase approval where the order is followed by a confirmation call that may fail; when it fails, an "Undo
     * completed calls" step runs the automatic undo of the order and the request finishes as failed.
     */
    static ObjectNode undo() {
        ObjectNode d = TestSupport.purchase();
        add(d, "{\"id\":\"confirm\",\"type\":\"http\",\"label\":\"Confirm the order\",\"config\":{\"connectionRef\":\"procurement\","
                + "\"operation\":\"createOrder\",\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\","
                + "\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{\"confirmationId\":\"/id\"},\"timeoutSeconds\":20}}", 900, 100);
        add(d, "{\"id\":\"cancelOrder\",\"type\":\"http\",\"label\":\"Cancel the order\",\"config\":{\"connectionRef\":\"procurement\","
                + "\"operation\":\"createOrder\",\"inputMapping\":{\"title\":\"/steps/procure/procurementId\",\"amount\":\"/input/amount\","
                + "\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{},\"timeoutSeconds\":20,"
                + "\"compensates\":\"procure\",\"compensation\":\"automatic\"}}", 900, 500);
        add(d, "{\"id\":\"undoAll\",\"type\":\"compensate\",\"label\":\"Undo the order\",\"config\":{}}", 1200, 300);
        add(d, "{\"id\":\"failedEnd\",\"type\":\"end\",\"label\":\"Not ordered\",\"config\":{\"businessOutcome\":\"Not ordered\","
                + "\"outcomeKind\":\"failed\"}}", 1500, 300);
        retarget(d, "t9", "confirm");
        path(d, "{\"id\":\"c1\",\"from\":\"confirm\",\"to\":\"notify\",\"on\":\"next\"}");
        path(d, "{\"id\":\"c2\",\"from\":\"confirm\",\"to\":\"undoAll\",\"on\":\"failed\"}");
        path(d, "{\"id\":\"c3\",\"from\":\"undoAll\",\"to\":\"failedEnd\",\"on\":\"next\"}");
        return d;
    }

    @Test
    void anUndoStepRunsTheAutomaticUndoCalls() throws Exception {
        ObjectNode d = undo();
        assertThat(blockers(d)).isEmpty();
        String xml = bpmn(d);
        assertThat(xml).contains("delegateExpression=\"${orqixCompensationTask}\"",
                "<orqix:undo target=\"procure\" handler=\"cancelOrder\"/>",
                "isForCompensation=\"true\"");
        // The undo call is never reached by a path.
        assertThat(xml).doesNotContain("targetRef=\"cancelOrder\"", "sourceRef=\"cancelOrder\"");
    }

    @Test
    void undoCallsAreChecked() {
        ObjectNode routed = undo();
        path(routed, "{\"id\":\"x4\",\"from\":\"cancelOrder\",\"to\":\"notify\",\"on\":\"next\"}");
        assertThat(blockers(routed)).contains("WF_UNDO_HANDLER_ROUTES");
        ObjectNode noTarget = undo();
        config(noTarget, "cancelOrder").remove("compensates");
        assertThat(blockers(noTarget)).contains("WF_UNDO_TARGET_REQUIRED");
        ObjectNode twice = undo();
        add(twice, "{\"id\":\"cancelAgain\",\"type\":\"http\",\"label\":\"Cancel again\",\"config\":{\"connectionRef\":\"procurement\","
                + "\"operation\":\"createOrder\",\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\","
                + "\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{},\"timeoutSeconds\":20,"
                + "\"compensates\":\"procure\",\"compensation\":\"automatic\"}}", 900, 700);
        assertThat(blockers(twice)).contains("WF_UNDO_DUPLICATE");
        // The undo call can use what the call it undoes produced, but not what a later step produced.
        ObjectNode later = undo();
        config(later, "cancelOrder").set("inputMapping", n("{\"title\":\"/steps/confirm/confirmationId\","
                + "\"amount\":\"/input/amount\",\"costCenter\":\"/input/costCenter\"}"));
        assertThat(blockers(later)).contains("WF_FIELDREF_NOT_GUARANTEED");
        // An Undo step without automatic undo calls has nothing to do.
        ObjectNode nothing = undo();
        config(nothing, "cancelOrder").put("compensation", "manual");
        assertThat(blockers(nothing)).contains("WF_UNDO_NOTHING");
        // Automatic undo calls without an Undo step never run.
        ObjectNode never = TestSupport.purchase();
        add(never, "{\"id\":\"cancelOrder\",\"type\":\"http\",\"label\":\"Cancel the order\",\"config\":{\"connectionRef\":\"procurement\","
                + "\"operation\":\"createOrder\",\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\","
                + "\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{},\"timeoutSeconds\":20,"
                + "\"compensates\":\"procure\",\"compensation\":\"automatic\"}}", 900, 500);
        assertThat(blockers(never)).isEmpty();
        assertThat(issues(never, Severity.WARNING)).contains("WF_UNDO_NEVER_RUN");
    }

    /**
     * S66: the purchase order and the confirmation are a section "Order" whose error route (ORDER_REJECTED) continues at
     * the rejection notice; the purchase order call opts in to raise ORDER_REJECTED when it fails for good.
     */
    static ObjectNode callFailure() {
        ObjectNode d = TestSupport.purchase();
        scope(d, "{\"id\":\"order\",\"label\":\"Order\",\"type\":\"embedded\",\"nodes\":[\"procure\",\"notify\"],"
                + "\"onError\":{\"to\":\"notifyRejected\",\"errorCode\":\"ORDER_REJECTED\"}}");
        config(d, "procure").put("raiseErrorOnFailure", "ORDER_REJECTED");
        return d;
    }

    @Test
    void aCallCanRaiseAnErrorForItsSectionsErrorRouteWhenItFails() throws Exception {
        ObjectNode d = callFailure();
        assertThat(blockers(d)).isEmpty();
        assertThat(issues(d, Severity.WARNING)).doesNotContain("WF_SUBPROCESS_ERROR_UNUSED");
        String xml = bpmn(d);
        assertThat(xml).contains("raiseOnFailure=\"ORDER_REJECTED\"",
                "<error id=\"error__ORDER_REJECTED\" errorCode=\"ORDER_REJECTED\"/>",
                "<boundaryEvent id=\"order__error\" name=\"Order: error\" attachedToRef=\"order\" cancelActivity=\"true\">",
                "<errorEventDefinition errorRef=\"error__ORDER_REJECTED\"/>",
                "<sequenceFlow id=\"order__onError\" sourceRef=\"order__error\" targetRef=\"notifyRejected\"/>")
                // the call has no failure path of its own, so no step-level failure boundary
                .doesNotContain("procure__failed", "error__stepFailed");
        // An error route for any code catches it too.
        ObjectNode anyCode = callFailure();
        ((ObjectNode) anyCode.path("subprocesses").get(0).path("onError")).remove("errorCode");
        assertThat(blockers(anyCode)).isEmpty();
    }

    @Test
    void callFailuresDoNotRaiseErrorsUnlessTheCallOptsIn() throws Exception {
        // The same section without the opt-in: the call keeps the S32 behavior and the route is reported unused.
        ObjectNode d = callFailure();
        config(d, "procure").remove("raiseErrorOnFailure");
        assertThat(blockers(d)).isEmpty();
        assertThat(issues(d, Severity.WARNING)).contains("WF_SUBPROCESS_ERROR_UNUSED");
        assertThat(bpmn(d)).doesNotContain("raiseOnFailure");
        // The unchanged example workflow compiles exactly as before (no new attribute, no new error definition).
        assertThat(bpmn(TestSupport.purchase())).doesNotContain("raiseOnFailure", "<error ");
    }

    @Test
    void aCallThatRaisesAnErrorOnFailureIsChecked() {
        // Outside any section: nothing would catch it.
        ObjectNode outside = TestSupport.purchase();
        config(outside, "procure").put("raiseErrorOnFailure", "ORDER_REJECTED");
        assertThat(blockers(outside)).contains("WF_CALL_FAILURE_ERROR_NOT_CAUGHT");
        // The section's error route catches another code, or the section has no error route.
        ObjectNode other = callFailure();
        ((ObjectNode) other.path("subprocesses").get(0).path("onError")).put("errorCode", "SOMETHING_ELSE");
        assertThat(blockers(other)).contains("WF_CALL_FAILURE_ERROR_NOT_CAUGHT");
        ObjectNode noRoute = callFailure();
        ((ObjectNode) noRoute.path("subprocesses").get(0)).remove("onError");
        assertThat(blockers(noRoute)).contains("WF_CALL_FAILURE_ERROR_NOT_CAUGHT");
        // An error handler does not count: the opt-in is for the section's error route only.
        ObjectNode handled = outside.deepCopy();
        add(handled, "{\"id\":\"sorry\",\"type\":\"end\",\"label\":\"Stopped\",\"config\":{\"businessOutcome\":\"Stopped\","
                + "\"outcomeKind\":\"failed\"}}", 900, 900);
        scope(handled, "{\"id\":\"onAnyError\",\"label\":\"Any error\",\"type\":\"event\",\"nodes\":[\"sorry\"],"
                + "\"trigger\":{\"type\":\"error\"},\"interrupting\":true}");
        assertThat(blockers(handled)).contains("WF_CALL_FAILURE_ERROR_NOT_CAUGHT");
        // Its own failure path and the opt-in are exclusive.
        ObjectNode both = callFailure();
        add(both, "{\"id\":\"orderFailed\",\"type\":\"notification\",\"label\":\"Order failed\",\"config\":{\"channel\":\"inApp\","
                + "\"recipient\":\"initiator\",\"message\":\"The order failed\"}}", 900, 900);
        path(both, "{\"id\":\"f1\",\"from\":\"procure\",\"to\":\"orderFailed\",\"on\":\"failed\"}");
        path(both, "{\"id\":\"f2\",\"from\":\"orderFailed\",\"to\":\"rejected\",\"on\":\"next\"}");
        assertThat(blockers(both)).contains("WF_CALL_FAILURE_ERROR_CONFLICT");
        // Platform codes stay reserved; the code format is checked by the schema.
        ObjectNode reserved = callFailure();
        config(reserved, "procure").put("raiseErrorOnFailure", "ORQIX_STEP_FAILED");
        assertThat(blockers(reserved)).contains("WF_ERROR_CODE_RESERVED");
        ObjectNode badFormat = callFailure();
        config(badFormat, "procure").put("raiseErrorOnFailure", "order rejected");
        assertThat(blockers(badFormat)).isNotEmpty();
        // An automatic undo call cannot raise an error on failure.
        ObjectNode undoCall = undo();
        config(undoCall, "cancelOrder").put("raiseErrorOnFailure", "ORDER_REJECTED");
        assertThat(blockers(undoCall)).contains("WF_CALL_FAILURE_ERROR_CONFLICT");
    }

    @Test
    void theSimulatorRaisesTheErrorOfACallThatFailsForGood() {
        JsonNode rejected = SimulatorFixtures.run(callFailure().toString(), INPUT.formatted("IT", 100),
                "{\"calls\":{\"procure\":{\"failure\":\"rejected\"}}}");
        assertThat(rejected.toString()).contains("raises the error \\\"ORDER_REJECTED\\\"", "the section \\\"Order\\\" stops",
                "\"endStepId\":\"rejected\"");
        // An unknown outcome is never turned into a business error: the request needs attention.
        JsonNode unknown = SimulatorFixtures.run(callFailure().toString(), INPUT.formatted("IT", 100),
                "{\"calls\":{\"procure\":{\"failure\":\"unknownOutcome\"}}}");
        assertThat(unknown.path("outcome").path("status").asString()).isEqualTo("needsAttention");
        assertThat(unknown.toString()).doesNotContain("raises the error");
    }

    @Test
    void theSimulatorFollowsRaisedErrorsAndListsTheCallsToUndo() {
        JsonNode raised = SimulatorFixtures.run(section().toString(), INPUT.formatted("IT", 150000), "{}");
        assertThat(raised.toString()).contains("raises the error \\\"TOO_EXPENSIVE\\\"", "the section \\\"Review\\\" stops",
                "\"endStepId\":\"rejected\"");
        JsonNode normal = SimulatorFixtures.run(section().toString(), INPUT.formatted("IT", 100), "{}");
        assertThat(normal.toString()).contains("\"endStepId\":\"fulfilled\"");
        JsonNode undone = SimulatorFixtures.run(undo().toString(), INPUT.formatted("IT", 100),
                "{\"calls\":{\"confirm\":{\"failure\":\"rejected\"}}}");
        assertThat(undone.toString()).contains("would run \\\"Cancel the order\\\"", "\"endStepId\":\"failedEnd\"");
    }
}
