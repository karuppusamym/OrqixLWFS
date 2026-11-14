package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * docs/build/S139: working-time waits and calendar pins (CG-W05), reminders while waiting (CG-W06), "Wait for the
 * first" (CG-W07) and message handlers (CG-E08): schema, rules, generated BPMN and the simulator. Node indexes of
 * {@code examples/order-payment-wait.json}: 0 start, 1 waitPayment, 2 notifyPaid, 3 fulfilled, 4 notifyCancelled,
 * 5 cancelled. {@code examples/order-payment-first.json} has the same nodes with 1 = waitFirst.
 */
class WaitsAndStartsCompilerTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();
    private static final String WAIT = "examples/order-payment-wait.json";
    private static final String FIRST = "examples/order-payment-first.json";

    private static ObjectNode doc(String file) {
        return (ObjectNode) TestSupport.json(file);
    }

    private static ObjectNode config(ObjectNode doc, int node) {
        return (ObjectNode) doc.get("nodes").get(node).get("config");
    }

    private static JsonNode parse(String json) {
        return StrictJson.parse(json);
    }

    private static CompileContext context() {
        return TestSupport.defaultContext().withAdapters(Map.of(
                "vendor-mail", new CompileContext.AdapterInfo("vendor-mail", "email", "Vendor mail", true,
                        "{\"allowedRecipientDomains\":[\"supplier.example\"],\"maxRecipients\":5}"),
                "no-domains", new CompileContext.AdapterInfo("no-domains", "email", "Closed mail", true,
                        "{\"allowedRecipientDomains\":[],\"maxRecipients\":5}"),
                "ops-teams", new CompileContext.AdapterInfo("ops-teams", "teams", "Operations channel", true, "{}"),
                "orders", new CompileContext.AdapterInfo("orders", "kafka", "Orders topic", true,
                        "{\"allowedTopics\":[\"orders\"]}")));
    }

    private static List<String> codes(ObjectNode doc) {
        ValidationReport report = COMPILER.validate(TestSupport.text(doc), context());
        return report.issues().stream()
                .map(i -> (i.severity() == Severity.BLOCKER ? "B " : "W ") + i.code() + " " + i.path()).toList();
    }

    private static List<String> issues(String file, Consumer<ObjectNode> patch) {
        ObjectNode d = doc(file);
        patch.accept(d);
        return codes(d);
    }

    private static void pin(ObjectNode d, String key, int version) {
        ObjectNode deps = (ObjectNode) d.get("dependencies");
        ArrayNode assets = deps.has("assets") ? (ArrayNode) deps.get("assets") : deps.putArray("assets");
        assets.add(parse("{\"kind\":\"calendar\",\"key\":\"" + key + "\",\"version\":" + version
                + ",\"target\":\"workflow\"}"));
    }

    private static String bpmn(ObjectNode d) {
        try {
            return COMPILER.compile(TestSupport.text(d), context()).bpmnXml();
        } catch (CompilationException e) {
            throw new AssertionError(e.report().issues().toString(), e);
        }
    }

    // ------------------------------------------------------------------ CG-W05

    /** The message wait becomes a working-time wait of three days in the "office" calendar. */
    private static void workingWait(ObjectNode d) {
        ObjectNode node = (ObjectNode) d.get("nodes").get(1);
        node.put("type", "wait");
        node.set("config", parse("{\"workingDuration\":\"P3D\",\"calendarRef\":\"office\"}"));
        ArrayNode transitions = (ArrayNode) d.get("transitions");
        transitions.remove(2);
        ((ArrayNode) d.get("nodes")).remove(5);
        ((ArrayNode) d.get("nodes")).remove(4);
        transitions.remove(3);
        ((ObjectNode) d.get("uiLayout").get("positions")).remove("cancelled");
        ((ObjectNode) d.get("uiLayout").get("positions")).remove("notifyCancelled");
        d.set("outputMapping", parse("{}"));
        ((ObjectNode) d.get("outputSchema")).set("properties", parse("{}"));
    }

    @Test
    void aWorkingTimeWaitNeedsAPinnedCalendar() {
        assertThat(issues(WAIT, d -> {
            workingWait(d);
            pin(d, "office", 2);
        })).isEmpty();
        assertThat(issues(WAIT, WaitsAndStartsCompilerTest::workingWait))
                .containsExactly("B WF_CALENDAR_NOT_PINNED /nodes/1/config/calendarRef");
        // calendarRef and workingDuration come together (schema), and exclude a plain duration.
        assertThat(issues(WAIT, d -> {
            workingWait(d);
            pin(d, "office", 1);
            config(d, 1).remove("calendarRef");
        })).contains("B WF_SCHEMA_INVALID /nodes/1/config");
        assertThat(issues(WAIT, d -> {
            workingWait(d);
            pin(d, "office", 1);
            config(d, 1).put("duration", "P1D");
        })).containsExactly("B WF_WAIT_CHOICE /nodes/1/config/workingDuration");
        assertThat(issues(WAIT, d -> {
            workingWait(d);
            pin(d, "office", 1);
            config(d, 1).put("workingDuration", "P91D");
        })).containsExactly("B WF_DURATION_TOO_LONG /nodes/1/config/workingDuration");
        // A pin nobody uses is a warning.
        assertThat(issues(WAIT, d -> pin(d, "office", 1))).containsExactly("W WF_CALENDAR_UNUSED /dependencies/assets/0");
    }

    @Test
    void aWorkingTimeWaitPinsTheCalendarVersionInTheBpmn() {
        ObjectNode d = doc(WAIT);
        workingWait(d);
        pin(d, "office", 4);
        String xml = bpmn(d);
        assertThat(xml).contains("workingDuration=\"P3D\"", "calendarKey=\"office\"", "calendarVersion=\"4\"",
                "<timeDate><![CDATA[${orqixWait.working(execution, 'waitPayment')}]]></timeDate>");
    }

    @Test
    void aMessageTimeLimitCanCountWorkingTime() {
        assertThat(issues(WAIT, d -> {
            config(d, 1).put("timeoutCalendarRef", "office");
            pin(d, "office", 1);
        })).isEmpty();
        ObjectNode d = doc(WAIT);
        config(d, 1).put("timeoutCalendarRef", "office");
        pin(d, "office", 3);
        assertThat(bpmn(d)).contains("calendarVersion=\"3\"",
                "${orqixWait.working(execution, 'waitPayment__timeout')}");
        // Without a calendar the timer is the same wall-clock duration as before.
        assertThat(bpmn(doc(WAIT))).contains("<timeDuration>P2D</timeDuration>").doesNotContain("calendarKey");
        // A working-time limit needs a time limit (schema) and a pin.
        assertThat(issues(WAIT, d2 -> config(d2, 1).put("timeoutCalendarRef", "office")))
                .containsExactly("B WF_CALENDAR_NOT_PINNED /nodes/1/config/timeoutCalendarRef");
    }

    // ------------------------------------------------------------------ CG-W06

    private static void remind(ObjectNode d, String json) {
        config(d, 1).set("whileWaiting", parse("{\"remind\":" + json + "}"));
    }

    private static final String EMAIL = """
            {"every":"P2D","maxTimes":3,"adapter":"vendor-mail","to":["orders@supplier.example"],
             "template":{"subject":"Reminder {{count}} of {{maxTimes}}: {{reference}}","body":"We still wait for {{step}} since {{since}} ({{customer}})."},
             "inputMapping":{"customer":"/input/customerName"}}""";

    @Test
    void remindersAreCheckedAgainstTheAdapter() {
        assertThat(issues(WAIT, d -> remind(d, EMAIL))).isEmpty();
        assertThat(issues(WAIT, d -> remind(d, EMAIL.replace("P2D", "PT30M"))))
                .containsExactly("B WF_REMIND_INTERVAL /nodes/1/config/whileWaiting/remind/every");
        assertThat(issues(WAIT, d -> remind(d, EMAIL.replace("vendor-mail", "nobody"))))
                .containsExactly("B WF_REMIND_ADAPTER /nodes/1/config/whileWaiting/remind/adapter");
        assertThat(issues(WAIT, d -> remind(d, EMAIL.replace("vendor-mail", "orders"))))
                .containsExactly("B WF_REMIND_ADAPTER /nodes/1/config/whileWaiting/remind/adapter");
        assertThat(issues(WAIT, d -> remind(d, EMAIL.replace("supplier.example", "elsewhere.example"))))
                .containsExactly("B WF_REMIND_RECIPIENT /nodes/1/config/whileWaiting/remind/to/0");
        assertThat(issues(WAIT, d -> remind(d, EMAIL.replace("{{step}}", "{{amount}}"))))
                .containsExactly("B WF_REMIND_PLACEHOLDER /nodes/1/config/whileWaiting/remind/template/body");
        assertThat(issues(WAIT, d -> remind(d, EMAIL.replace("/input/customerName", "/steps/waitPayment/amountPaid"))))
                .containsExactly("B WF_REMIND_INPUT /nodes/1/config/whileWaiting/remind/inputMapping/customer");
        // A Teams reminder has a text and no recipients.
        assertThat(issues(WAIT, d -> remind(d, """
                {"every":"P1D","adapter":"ops-teams","template":{"text":"Still waiting for {{reference}}"}}""")))
                .isEmpty();
        assertThat(issues(WAIT, d -> remind(d, """
                {"every":"P1D","adapter":"ops-teams","to":["a@supplier.example"],"template":{"text":"x"}}""")))
                .containsExactly("B WF_REMIND_RECIPIENT /nodes/1/config/whileWaiting/remind/to");
    }

    @Test
    void aRecipientFromARequestFieldNeedsApprovedDomains() {
        String fromField = """
                {"every":"P2D","adapter":"vendor-mail","toField":"/input/customerName",
                 "template":{"subject":"Reminder","body":"Please answer."}}""";
        assertThat(issues(WAIT, d -> remind(d, fromField))).isEmpty();
        assertThat(issues(WAIT, d -> remind(d, fromField.replace("vendor-mail", "no-domains"))))
                .containsExactly("B WF_REMIND_RECIPIENT_DOMAINS /nodes/1/config/whileWaiting/remind/toField");
        assertThat(issues(WAIT, d -> remind(d, fromField.replace("/input/customerName", "/steps/waitPayment/paymentReference"))))
                .containsExactly("B WF_REMIND_RECIPIENT_FIELD /nodes/1/config/whileWaiting/remind/toField");
        assertThat(issues(WAIT, d -> remind(d, fromField.replace("\"toField\":\"/input/customerName\",",
                "\"toField\":\"/input/customerName\",\"to\":[\"x@supplier.example\"],"))))
                .containsExactly("B WF_REMIND_RECIPIENT /nodes/1/config/whileWaiting/remind/to");
    }

    @Test
    void remindersAreOnlyForStepsThatWaitForSomeoneOutside() {
        assertThat(issues(WAIT, d -> {
            workingWait(d);
            pin(d, "office", 1);
            remind(d, EMAIL);
        })).contains("B WF_SCHEMA_INVALID /nodes/1/config/whileWaiting");
    }

    // ------------------------------------------------------------------ CG-W07

    @Test
    void theWaitForTheFirstSampleIsValid() {
        ValidationReport report = COMPILER.validate(TestSupport.read(FIRST), context());
        assertThat(report.issues()).isEmpty();
        assertThat(report.publishable()).isTrue();
    }

    @Test
    void everyBranchHasExactlyOnePath() {
        assertThat(issues(FIRST, d -> ((ArrayNode) d.get("transitions")).remove(3)))
                .containsExactly("B WF_FIRST_ROUTE_MISSING /nodes/1/config/branches/2");
        assertThat(issues(FIRST, d -> ((ArrayNode) d.get("transitions")).add(parse(
                "{\"id\":\"t7\",\"from\":\"waitFirst\",\"to\":\"notifyPaid\",\"on\":\"branch\",\"branch\":\"tooOld\"}"))))
                .containsExactly("B WF_FIRST_ROUTE_DUPLICATE /nodes/1/config/branches/2");
        assertThat(issues(FIRST, d -> ((ObjectNode) d.get("transitions").get(3)).put("branch", "other")))
                .containsExactlyInAnyOrder("B WF_FIRST_ROUTE_UNKNOWN /transitions/3/branch",
                        "B WF_FIRST_ROUTE_MISSING /nodes/1/config/branches/2");
        assertThat(issues(FIRST, d -> {
            ObjectNode t = (ObjectNode) d.get("transitions").get(3);
            t.put("on", "next");
            t.remove("branch");
        })).contains("B WF_OUTGOING_INVALID /transitions/3/on");
        // branch is required with on: branch and refused otherwise (schema).
        assertThat(issues(FIRST, d -> ((ObjectNode) d.get("transitions").get(3)).remove("branch")))
                .anyMatch(i -> i.startsWith("B WF_SCHEMA_INVALID /transitions/3"));
    }

    @Test
    void branchesAreCheckedByKind() {
        assertThat(issues(FIRST, d -> ((ObjectNode) config(d, 1).get("branches").get(2)).put("untilField", "/input/x")))
                .contains("B WF_FIRST_TIMER_CHOICE /nodes/1/config/branches/2");
        assertThat(issues(FIRST, d -> ((ObjectNode) config(d, 1).get("branches").get(1)).put("after", "P1D")))
                .containsExactly("B WF_FIRST_BRANCH_SETTING /nodes/1/config/branches/1/after");
        assertThat(issues(FIRST, d -> ((ObjectNode) config(d, 1).get("branches").get(0))
                .put("correlationField", "/input/missing")))
                .containsExactly("B WF_MESSAGE_CORRELATION_UNKNOWN /nodes/1/config/branches/0/correlationField");
        assertThat(issues(FIRST, d -> ((ObjectNode) config(d, 1).get("branches").get(2)).put("id", "paid")))
                .contains("B WF_FIRST_BRANCH_DUPLICATE /nodes/1/config/branches/2/id");
        // A callback branch needs its token validity.
        assertThat(issues(FIRST, d -> ((ArrayNode) config(d, 1).get("branches")).set(1, parse(
                "{\"id\":\"withdrawn\",\"label\":\"Vendor answered\",\"kind\":\"callback\",\"tokenValidFor\":\"P40D\"}"))))
                .containsExactly("B WF_CALLBACK_TOKEN_VALIDITY /nodes/1/config/branches/1/tokenValidFor");
        // Values of two branches cannot share a name, and "branch" is the step's own output.
        assertThat(issues(FIRST, d -> ((ArrayNode) config(d, 1).get("branches")).set(1, parse(
                "{\"id\":\"withdrawn\",\"label\":\"Vendor answered\",\"kind\":\"callback\",\"tokenValidFor\":\"P2D\","
                        + "\"payload\":[{\"name\":\"branch\",\"type\":\"string\"}]}"))))
                .containsExactly("B WF_FIRST_FIELD_DUPLICATE /nodes/1/config/branches/1/payload/0/name");
    }

    @Test
    void aRequiredBranchValueExistsOnlyOnItsPath() {
        // notifyCancelled (reached by two other branches) cannot use the payment reference.
        assertThat(issues(FIRST, d -> d.set("outputMapping", parse(
                "{\"paymentReference\":{\"fieldRef\":\"/steps/waitFirst/paymentReference\",\"required\":true}}"))))
                .anyMatch(i -> i.startsWith("B WF_OUTPUT_NOT_GUARANTEED"));
        // "branch" always exists.
        assertThat(issues(FIRST, d -> {
            ((ObjectNode) d.get("outputSchema").get("properties")).set("how", parse("{\"type\":\"string\",\"title\":\"How\"}"));
            ((ObjectNode) d.get("outputMapping")).set("how", parse(
                    "{\"fieldRef\":\"/steps/waitFirst/branch\",\"required\":true}"));
        })).isEmpty();
    }

    @Test
    void waitForTheFirstCompilesToAnEventBasedGateway() {
        String xml = bpmn(doc(FIRST));
        assertThat(xml).contains("<eventBasedGateway id=\"waitFirst\"",
                "<intermediateCatchEvent id=\"waitFirst__paid\"", "<intermediateCatchEvent id=\"waitFirst__withdrawn\"",
                "<intermediateCatchEvent id=\"waitFirst__tooOld\"",
                "<message id=\"message__payment-received\" name=\"payment-received\"/>",
                "<signal id=\"signal__customer-cancelled\"", "<timeDuration>P7D</timeDuration>",
                "sourceRef=\"waitFirst__paid\" targetRef=\"notifyPaid\"",
                "sourceRef=\"waitFirst__tooOld\" targetRef=\"notifyCancelled\"",
                "orqix:first step=\"waitFirst\" branch=\"paid\" kind=\"message\"",
                "orqix:message node=\"waitFirst__paid\" name=\"payment-received\" correlationField=\"/input/orderNumber\"",
                "orqix:signal action=\"wait\" name=\"customer-cancelled\"");
        // Callback branches are delivered as an internal message for this request only.
        ObjectNode d = doc(FIRST);
        ((ArrayNode) config(d, 1).get("branches")).set(1, parse(
                "{\"id\":\"withdrawn\",\"label\":\"Vendor answered\",\"kind\":\"callback\",\"tokenValidFor\":\"P2D\"}"));
        String callback = bpmn(d);
        String internal = com.orqix.compiler.internal.model.Node.WaitForFirst.internalMessage("waitFirst", "withdrawn");
        assertThat(internal).startsWith("orqix-withdrawn-").hasSize("orqix-withdrawn-".length() + 10);
        assertThat(callback).contains("name=\"" + internal + "\" correlation=\"run\" callbackTokenValidFor=\"P2D\"");
    }

    @Test
    void theSimulatorTakesTheChosenBranch() {
        SimulationContext sim = new SimulationContext(context(), key -> null, LocalDate.parse("2026-01-15"));
        String input = "{\"orderNumber\":\"SO-1\",\"customerName\":\"Ada\"}";
        SimulationResult first = Compilers.workflowSimulator().simulate(TestSupport.read(FIRST), input, "{}", sim);
        assertThat(first.json()).contains("Order paid");
        SimulationResult timer = Compilers.workflowSimulator().simulate(TestSupport.read(FIRST), input,
                "{\"messages\":{\"waitFirst\":\"tooOld\"}}", sim);
        assertThat(timer.json()).contains("\"endStepId\":\"cancelled\"").doesNotContain("SCENARIO_UNKNOWN_STEP");
        SimulationResult paid = Compilers.workflowSimulator().simulate(TestSupport.read(FIRST), input,
                "{\"messages\":{\"waitFirst\":{\"branch\":\"paid\",\"payload\":{\"paymentReference\":\"PAY-9\"}}}}", sim);
        assertThat(paid.json()).contains("Order paid", "PAY-9");
    }

    // ------------------------------------------------------------------ CG-E08

    private static void handler(ObjectNode d, boolean interrupting, String message) {
        d.set("subprocesses", parse("""
                [{"id":"onCancel","label":"Customer cancelled","type":"event","nodes":["noteCancel","endCancel"],
                  "interrupting":%s,"trigger":{"type":"message","message":"%s","correlationField":"/input/orderNumber",
                  "payload":[{"name":"reason","type":"string"}]}}]""".formatted(interrupting, message)));
        ((ArrayNode) d.get("nodes")).add(parse("""
                {"id":"noteCancel","type":"notification","label":"Tell requester","config":{"channel":"inApp",
                 "recipient":"initiator","message":"The customer cancelled the order."}}"""));
        ((ArrayNode) d.get("nodes")).add(parse("""
                {"id":"endCancel","type":"end","label":"Cancelled by customer","config":{"businessOutcome":"Cancelled",
                 "outcomeKind":"rejected"}}"""));
        ((ArrayNode) d.get("transitions")).add(parse(
                "{\"id\":\"h1\",\"from\":\"noteCancel\",\"to\":\"endCancel\",\"on\":\"next\"}"));
        d.remove("uiLayout");
    }

    @Test
    void aMessageHandlerInterruptsAndMayCoexistWithMessageWaits() {
        assertThat(issues(WAIT, d -> handler(d, true, "customer-cancelled"))).isEmpty();
        assertThat(issues(WAIT, d -> handler(d, true, "payment-received")))
                .containsExactly("B WF_HANDLER_MESSAGE_CLASH /subprocesses/0/trigger/message");
        assertThat(issues(WAIT, d -> {
            handler(d, true, "customer-cancelled");
            ((ObjectNode) d.get("subprocesses").get(0).get("trigger")).put("correlationField", "/input/missing");
        })).containsExactly("B WF_MESSAGE_CORRELATION_UNKNOWN /subprocesses/0/trigger/correlationField");
        assertThat(issues(WAIT, d -> {
            handler(d, true, "customer-cancelled");
            ((ObjectNode) d.get("subprocesses").get(0).get("trigger")).remove("message");
        })).containsExactly("B WF_SUBPROCESS_SETTING /subprocesses/0/trigger");
        ObjectNode d = doc(WAIT);
        handler(d, true, "customer-cancelled");
        assertThat(bpmn(d)).contains("<message id=\"message__customer-cancelled\" name=\"customer-cancelled\"/>",
                "triggeredByEvent=\"true\"", "isInterrupting=\"true\"",
                "orqix:message node=\"onCancel\" name=\"customer-cancelled\" correlationField=\"/input/orderNumber\""
                        + " handler=\"true\" interrupting=\"true\"",
                "<messageEventDefinition messageRef=\"message__customer-cancelled\"/>");
    }

    @Test
    void aSideMessageHandlerRunsAlongside() {
        ObjectNode d = doc(WAIT);
        handler(d, false, "customer-note");
        ((ArrayNode) d.get("subprocesses").get(0).get("nodes")).remove(1);
        ((ArrayNode) d.get("nodes")).remove(7);
        ((ArrayNode) d.get("transitions")).remove(5);
        assertThat(codes(d)).isEmpty();
        assertThat(bpmn(d)).contains("isInterrupting=\"false\"", "handler=\"true\" interrupting=\"false\"");
    }

    @Test
    void stepsWithoutS139SettingsCompileExactlyAsBefore() {
        // Golden files cover the examples; here the message wait sample has no new attributes.
        String xml = bpmn(doc(WAIT));
        assertThat(xml).doesNotContain("orqix:first", "calendarKey", "handler=\"true\"", "orqixWait.working");
    }
}
