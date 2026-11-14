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
 * docs/build/S139: the BPMN round trip of working-time waits, wait-for-the-first branches of every kind and message
 * handlers, and the remaining rule paths (wrong settings per branch kind, reminder texts and recipients, handler
 * identifying fields). Indexes as in {@link WaitsAndStartsCompilerTest}.
 */
class WaitsAndStartsRoundTripTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();
    private static final String WAIT = "examples/order-payment-wait.json";
    private static final String FIRST = "examples/order-payment-first.json";

    private static CompileContext context() {
        CompileContext base = PollRulesTest.context();
        return base.withAdapters(Map.of(
                "vendor-mail", new CompileContext.AdapterInfo("vendor-mail", "email", "Vendor mail", true,
                        "{\"allowedRecipientDomains\":[\"supplier.example\"],\"maxRecipients\":5}"),
                "off-mail", new CompileContext.AdapterInfo("off-mail", "email", "Old mail", false,
                        "{\"allowedRecipientDomains\":[\"supplier.example\"]}"),
                "ops-teams", new CompileContext.AdapterInfo("ops-teams", "teams", "Operations channel", true, "{}")));
    }

    private static ObjectNode doc(String file) {
        return (ObjectNode) TestSupport.json(file);
    }

    private static ObjectNode config(ObjectNode d, int node) {
        return (ObjectNode) d.get("nodes").get(node).get("config");
    }

    private static JsonNode parse(String json) {
        return StrictJson.parse(json);
    }

    private static void pin(ObjectNode d, String key, int version) {
        ObjectNode deps = (ObjectNode) d.get("dependencies");
        ArrayNode assets = deps.has("assets") ? (ArrayNode) deps.get("assets") : deps.putArray("assets");
        assets.add(parse("{\"kind\":\"calendar\",\"key\":\"" + key + "\",\"version\":" + version
                + ",\"target\":\"workflow\"}"));
    }

    private static List<String> codes(ObjectNode d) {
        return COMPILER.validate(TestSupport.text(d), context()).issues().stream()
                .map(i -> (i.severity() == Severity.BLOCKER ? "B " : "W ") + i.code() + " " + i.path()).toList();
    }

    private static List<String> issues(String file, Consumer<ObjectNode> patch) {
        ObjectNode d = doc(file);
        patch.accept(d);
        return codes(d);
    }

    /** Compiles, exports and reads the BPMN back: nothing may be lost. */
    private static void roundTrips(ObjectNode d) throws Exception {
        assertThat(codes(d)).noneMatch(c -> c.startsWith("B "));
        CompiledWorkflow compiled = COMPILER.compile(TestSupport.text(d), context());
        assertThat(BpmnSource.export(TestSupport.text(d), compiled).differences()).isEmpty();
        BpmnSource.Check read = BpmnSource.check(compiled.bpmnXml(), TestSupport.text(d), COMPILER, json -> context());
        assertThat(read.problems()).isEmpty();
    }

    // ------------------------------------------------------------------ round trips

    @Test
    void theWaitForTheFirstSampleRoundTrips() throws Exception {
        roundTrips(doc(FIRST));
    }

    @Test
    void everyBranchKindRoundTrips() throws Exception {
        ObjectNode d = doc(FIRST);
        d.set("inputSchema", parse("""
                {"type":"object","additionalProperties":false,"required":["orderNumber","customerName","promised"],
                 "properties":{"orderNumber":{"type":"string","title":"Order number","minLength":3,"maxLength":40},
                 "customerName":{"type":"string","title":"Customer","minLength":1,"maxLength":100},
                 "promised":{"type":"string","format":"date","title":"Promised by"}}}"""));
        ((ObjectNode) d.get("inputUi")).set("order", parse("[\"orderNumber\",\"customerName\",\"promised\"]"));
        ArrayNode branches = (ArrayNode) config(d, 1).get("branches");
        branches.set(1, parse("""
                {"id":"withdrawn","label":"Vendor confirmed","kind":"callback","tokenValidFor":"P2D",
                 "payload":[{"name":"confirmation","type":"string","required":true}]}"""));
        branches.set(2, parse("""
                {"id":"tooOld","label":"Promised date passed","kind":"timer","untilField":"/input/promised","atTime":"17:30"}"""));
        roundTrips(d);
        branches.set(2, parse("""
                {"id":"tooOld","label":"Three working days","kind":"timer","workingDuration":"P3D","calendarRef":"office"}"""));
        pin(d, "office", 2);
        roundTrips(d);
        String xml = COMPILER.compile(TestSupport.text(d), context()).bpmnXml();
        assertThat(xml).contains("${orqixWait.working(execution, 'waitFirst__tooOld')}", "calendarVersion=\"2\"");
    }

    @Test
    void aCheckBranchRoundTripsAndCountsItsConnectionAsUsed() throws Exception {
        ObjectNode d = PollRulesTest.fallbackDefinition(PollRulesTest.fallbackJson());
        // Turn the message wait with a safety net into a wait for the first: the message, or a check.
        ObjectNode shipped = null;
        for (JsonNode n : d.get("nodes")) {
            if ("shipped".equals(n.path("id").asString())) {
                shipped = (ObjectNode) n;
            }
        }
        JsonNode check = parse(PollRulesTest.fallbackJson().replace("\"every\":\"PT2H\"",
                "\"every\":\"PT2H\",\"maxChecks\":500"));
        shipped.put("type", "waitForFirst");
        shipped.set("config", parse("""
                {"branches":[{"id":"msg","label":"Shipping message","kind":"message","message":"order-shipped",
                  "correlationField":"/steps/procure/procurementId"},
                 {"id":"poll","label":"Tracking says shipped","kind":"check","check":%s,
                  "payload":[{"name":"state","type":"string","required":true}]}]}""".formatted(check)));
        for (JsonNode t : d.get("transitions")) {
            if ("m1".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("on", "branch").put("branch", "msg");
            } else if ("m2".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("on", "branch").put("branch", "poll");
            }
        }
        assertThat(codes(d)).noneMatch(c -> c.startsWith("B ") || c.contains("WF_DEPENDENCY_UNUSED"));
        CompiledWorkflow compiled = COMPILER.compile(TestSupport.text(d), context());
        assertThat(compiled.bpmnXml()).contains("fallbackCheckBase64=", "correlation=\"run\"");
        assertThat(BpmnSource.export(TestSupport.text(d), compiled).differences()).isEmpty();
        // A check branch is checked like a safety net: read-only operation and a known value in its condition.
        ((ObjectNode) shipped.get("config").get("branches").get(1).get("check")).put("operation", "cancelOrder");
        assertThat(codes(d)).anyMatch(c -> c.contains("/config/branches/1/check"));
    }

    @Test
    void workingTimeWaitsAndMessageLimitsRoundTrip() throws Exception {
        ObjectNode wait = doc(WAIT);
        config(wait, 1).put("timeoutCalendarRef", "office");
        pin(wait, "office", 1);
        roundTrips(wait);
        ObjectNode working = (ObjectNode) TestSupport.json("examples/start-date-onboarding.json");
        for (JsonNode n : working.get("nodes")) {
            if ("untilStart".equals(n.path("id").asString())) {
                ((ObjectNode) n).set("config", parse("{\"workingDuration\":\"PT4H\",\"calendarRef\":\"office\"}"));
            }
        }
        pin(working, "office", 3);
        CompileContext ctx = TestSupport.onboardingContext();
        assertThat(COMPILER.validate(TestSupport.text(working), ctx).issues()).noneMatch(i -> i.severity() == Severity.BLOCKER);
        CompiledWorkflow compiled = COMPILER.compile(TestSupport.text(working), ctx);
        assertThat(BpmnSource.export(TestSupport.text(working), compiled).differences()).isEmpty();
    }

    @Test
    void messageHandlersRoundTrip() throws Exception {
        ObjectNode d = doc(WAIT);
        d.set("subprocesses", parse("""
                [{"id":"onCancel","label":"Customer cancelled","type":"event","nodes":["noteCancel"],"interrupting":false,
                  "trigger":{"type":"message","message":"customer-cancelled","correlationField":"/input/orderNumber",
                  "payload":[{"name":"reason","type":"string","required":true}]}}]"""));
        ((ArrayNode) d.get("nodes")).add(parse("""
                {"id":"noteCancel","type":"notification","label":"Tell requester","config":{"channel":"inApp",
                 "recipient":"initiator","message":"The customer cancelled the order."}}"""));
        ((ObjectNode) d.get("uiLayout").get("positions")).set("noteCancel", parse("{\"x\":600,\"y\":640}"));
        roundTrips(d);
    }

    // ------------------------------------------------------------------ remaining rule paths

    @Test
    void branchSettingsOfTheWrongKindOrMissingAreNamed() {
        assertThat(issues(FIRST, d -> ((ArrayNode) config(d, 1).get("branches")).set(1,
                parse("{\"id\":\"withdrawn\",\"label\":\"Cancelled\",\"kind\":\"signal\"}"))))
                .containsExactly("B WF_FIRST_BRANCH_SETTING /nodes/1/config/branches/1");
        assertThat(issues(FIRST, d -> ((ArrayNode) config(d, 1).get("branches")).set(0,
                parse("{\"id\":\"paid\",\"label\":\"Paid\",\"kind\":\"message\",\"message\":\"payment-received\"}"))))
                .contains("B WF_FIRST_BRANCH_SETTING /nodes/1/config/branches/0");
        assertThat(issues(FIRST, d -> ((ArrayNode) config(d, 1).get("branches")).set(1,
                parse("{\"id\":\"withdrawn\",\"label\":\"Callback\",\"kind\":\"callback\"}"))))
                .containsExactly("B WF_FIRST_BRANCH_SETTING /nodes/1/config/branches/1");
        assertThat(issues(FIRST, d -> ((ArrayNode) config(d, 1).get("branches")).set(1,
                parse("{\"id\":\"withdrawn\",\"label\":\"Check\",\"kind\":\"check\"}"))))
                .containsExactly("B WF_FIRST_BRANCH_SETTING /nodes/1/config/branches/1");
        assertThat(issues(FIRST, d -> ((ObjectNode) config(d, 1).get("branches").get(2)).put("atTime", "10:00")))
                .contains("B WF_FIRST_BRANCH_SETTING /nodes/1/config/branches/2/atTime");
        assertThat(issues(FIRST, d -> {
            ObjectNode timer = (ObjectNode) config(d, 1).get("branches").get(2);
            timer.remove("after");
            timer.put("workingDuration", "P1D");
        })).contains("B WF_FIRST_BRANCH_SETTING /nodes/1/config/branches/2/calendarRef");
        assertThat(issues(FIRST, d -> {
            ObjectNode timer = (ObjectNode) config(d, 1).get("branches").get(2);
            timer.remove("after");
            timer.put("untilField", "/input/customerName");
        })).containsExactly("B WF_WAIT_FIELD_TYPE /nodes/1/config/branches/2/untilField");
        assertThat(issues(FIRST, d -> ((ObjectNode) config(d, 1).get("branches").get(2)).put("after", "P100D")))
                .containsExactly("B WF_DURATION_TOO_LONG /nodes/1/config/branches/2/after");
        // Two time branches: only the earliest can ever win.
        assertThat(issues(FIRST, d -> ((ArrayNode) config(d, 1).get("branches")).set(1,
                parse("{\"id\":\"withdrawn\",\"label\":\"Two days\",\"kind\":\"timer\",\"after\":\"P2D\"}"))))
                .containsExactly("W WF_FIRST_TIMERS /nodes/1/config/branches");
        // The same message in two branches.
        assertThat(issues(FIRST, d -> ((ArrayNode) config(d, 1).get("branches")).set(1, parse(
                "{\"id\":\"withdrawn\",\"label\":\"Again\",\"kind\":\"message\",\"message\":\"payment-received\","
                        + "\"correlationField\":\"/input/customerName\"}"))))
                .contains("B WF_FIRST_MESSAGE_DUPLICATE /nodes/1/config/branches/1/message");
        // The identifying field of a message branch.
        assertThat(issues(FIRST, d -> {
            ((ObjectNode) d.get("inputSchema").get("properties")).set("amount", parse("{\"type\":\"number\",\"title\":\"Amount\"}"));
            ((ObjectNode) config(d, 1).get("branches").get(0)).put("correlationField", "/input/amount");
        })).contains("B WF_MESSAGE_CORRELATION_TYPE /nodes/1/config/branches/0/correlationField");
        assertThat(issues(FIRST, d -> {
            ((ObjectNode) d.get("inputSchema").get("properties")).set("note", parse("{\"type\":\"string\",\"title\":\"Note\"}"));
            ((ObjectNode) config(d, 1).get("branches").get(0)).put("correlationField", "/input/note");
        })).contains("B WF_MESSAGE_CORRELATION_OPTIONAL /nodes/1/config/branches/0/correlationField",
                "B WF_MESSAGE_CORRELATION_BOUNDS /nodes/1/config/branches/0/correlationField");
    }

    @Test
    void reminderTextsRecipientsAndStepsAreChecked() {
        String teams = "{\"every\":\"P1D\",\"adapter\":\"ops-teams\",\"template\":{\"subject\":\"x\",\"body\":\"y\"}}";
        assertThat(issues(WAIT, d -> config(d, 1).set("whileWaiting", parse("{\"remind\":" + teams + "}"))))
                .containsExactly("B WF_REMIND_TEMPLATE /nodes/1/config/whileWaiting/remind/template");
        String email = """
                {"every":"P1D","adapter":"vendor-mail","to":["not an address"],"template":{"text":"x"}}""";
        assertThat(issues(WAIT, d -> config(d, 1).set("whileWaiting", parse("{\"remind\":" + email + "}"))))
                .containsExactlyInAnyOrder("B WF_REMIND_RECIPIENT /nodes/1/config/whileWaiting/remind/to/0",
                        "B WF_REMIND_TEMPLATE /nodes/1/config/whileWaiting/remind/template");
        String multiline = """
                {"every":"P1D","adapter":"vendor-mail","to":["a@supplier.example"],"template":{"subject":"a\\nb","body":"y"},
                 "inputMapping":{"reference":"/input/customerName"}}""";
        assertThat(issues(WAIT, d -> config(d, 1).set("whileWaiting", parse("{\"remind\":" + multiline + "}"))))
                .containsExactlyInAnyOrder("B WF_REMIND_TEMPLATE /nodes/1/config/whileWaiting/remind/template/subject",
                        "B WF_REMIND_INPUT /nodes/1/config/whileWaiting/remind/inputMapping/reference");
        String disabled = "{\"every\":\"P1D\",\"adapter\":\"off-mail\",\"to\":[\"a@supplier.example\"],"
                + "\"template\":{\"subject\":\"x\",\"body\":\"y\"},\"inputMapping\":{\"who\":\"/input/missing\"}}";
        assertThat(issues(WAIT, d -> config(d, 1).set("whileWaiting", parse("{\"remind\":" + disabled + "}"))))
                .containsExactlyInAnyOrder("B WF_REMIND_ADAPTER /nodes/1/config/whileWaiting/remind/adapter",
                        "B WF_REMIND_INPUT /nodes/1/config/whileWaiting/remind/inputMapping/who");
        // A request field that is not text cannot hold the address; a missing field is named.
        String field = "{\"every\":\"P1D\",\"adapter\":\"vendor-mail\",\"toField\":\"/input/%s\","
                + "\"template\":{\"subject\":\"x\",\"body\":\"y\"}}";
        assertThat(issues(WAIT, d -> {
            ((ObjectNode) d.get("inputSchema").get("properties")).set("count", parse("{\"type\":\"integer\",\"title\":\"Count\"}"));
            config(d, 1).set("whileWaiting", parse("{\"remind\":" + field.formatted("count") + "}"));
        })).containsExactly("B WF_REMIND_RECIPIENT_FIELD /nodes/1/config/whileWaiting/remind/toField");
        assertThat(issues(WAIT, d -> config(d, 1).set("whileWaiting", parse("{\"remind\":" + field.formatted("nobody") + "}"))))
                .containsExactly("B WF_REMIND_RECIPIENT_FIELD /nodes/1/config/whileWaiting/remind/toField");
    }

    @Test
    void remindersOnACallThatDoesNotCheckAreRefused() throws Exception {
        ObjectNode d = PollRulesTest.definition();
        for (JsonNode n : d.get("nodes")) {
            if ("track".equals(n.path("id").asString())) {
                ((ObjectNode) n.get("config")).set("whileWaiting", parse("""
                        {"remind":{"every":"P1D","adapter":"ops-teams","template":{"text":"Still checking {{reference}}"}}}"""));
            }
        }
        assertThat(codes(d)).noneMatch(c -> c.contains("WF_REMIND"));
        for (JsonNode n : d.get("nodes")) {
            if ("procure".equals(n.path("id").asString())) {
                ((ObjectNode) n.get("config")).set("whileWaiting", parse("""
                        {"remind":{"every":"P1D","adapter":"ops-teams","template":{"text":"x"}}}"""));
            }
        }
        assertThat(codes(d)).anyMatch(c -> c.startsWith("B WF_REMIND_STEP"));
        // A check-until call can count its deadline in working time.
        for (JsonNode n : d.get("nodes")) {
            if ("track".equals(n.path("id").asString())) {
                ((ObjectNode) n.get("config").get("poll")).put("timeoutCalendarRef", "office");
            }
        }
        assertThat(codes(d)).anyMatch(c -> c.equals("B WF_CALENDAR_NOT_PINNED /nodes/10/config/poll/timeoutCalendarRef"));
    }

    @Test
    void aMessageHandlerIsIdentifiedByARequiredRequestField() {
        Consumer<ObjectNode> handler = d -> {
            d.set("subprocesses", parse("""
                    [{"id":"onNote","label":"Customer note","type":"event","nodes":["noteCancel"],"interrupting":false,
                      "trigger":{"type":"message","message":"customer-note","correlationField":"/input/orderNumber",
                      "payload":[{"name":"note","type":"string"},{"name":"note","type":"string"}]}}]"""));
            ((ArrayNode) d.get("nodes")).add(parse("""
                    {"id":"noteCancel","type":"notification","label":"Tell requester","config":{"channel":"inApp",
                     "recipient":"initiator","message":"A note arrived."}}"""));
            d.remove("uiLayout");
        };
        assertThat(issues(WAIT, handler)).containsExactly("B WF_MESSAGE_PAYLOAD_DUPLICATE /subprocesses/0/trigger/payload/1/name");
        assertThat(issues(WAIT, handler.andThen(d -> ((ObjectNode) d.get("subprocesses").get(0).get("trigger"))
                .put("correlationField", "/steps/waitPayment/paymentReference"))))
                .contains("B WF_HANDLER_CORRELATION /subprocesses/0/trigger/correlationField");
        assertThat(issues(WAIT, handler.andThen(d -> {
            ((ObjectNode) d.get("inputSchema").get("properties")).set("amount", parse("{\"type\":\"number\",\"title\":\"Amount\"}"));
            ((ObjectNode) d.get("subprocesses").get(0).get("trigger")).put("correlationField", "/input/amount");
        }))).contains("B WF_MESSAGE_CORRELATION_TYPE /subprocesses/0/trigger/correlationField");
        assertThat(issues(WAIT, handler.andThen(d -> {
            ((ObjectNode) d.get("inputSchema").get("properties")).set("ref", parse("{\"type\":\"string\",\"title\":\"Ref\"}"));
            ((ObjectNode) d.get("subprocesses").get(0).get("trigger")).put("correlationField", "/input/ref");
        }))).contains("B WF_MESSAGE_CORRELATION_OPTIONAL /subprocesses/0/trigger/correlationField",
                "B WF_MESSAGE_CORRELATION_BOUNDS /subprocesses/0/trigger/correlationField");
        // Two handlers on the same message.
        assertThat(issues(WAIT, handler.andThen(d -> {
            ((ArrayNode) d.get("subprocesses").get(0).get("trigger").get("payload")).remove(1);
            ((ArrayNode) d.get("subprocesses")).add(parse("""
                    {"id":"onNote2","label":"Note again","type":"event","nodes":["noteTwo"],"interrupting":false,
                     "trigger":{"type":"message","message":"customer-note","correlationField":"/input/orderNumber"}}"""));
            ((ArrayNode) d.get("nodes")).add(parse("""
                    {"id":"noteTwo","type":"notification","label":"Tell again","config":{"channel":"inApp",
                     "recipient":"initiator","message":"Another note."}}"""));
        }))).contains("B WF_SUBPROCESS_TRIGGER_DUPLICATE /subprocesses/1/trigger");
    }

    @Test
    void theSimulatorRefusesAnUnknownBranchOrValuesThatDoNotFit() {
        SimulationContext sim = new SimulationContext(context(), key -> null, LocalDate.parse("2026-01-15"));
        String input = "{\"orderNumber\":\"SO-1\",\"customerName\":\"Ada\"}";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Compilers.workflowSimulator().simulate(
                TestSupport.read(FIRST), input, "{\"messages\":{\"waitFirst\":\"nope\"}}", sim))
                .hasMessageContaining("must name a branch: paid, withdrawn, tooOld");
        SimulationResult bad = Compilers.workflowSimulator().simulate(TestSupport.read(FIRST), input,
                "{\"messages\":{\"waitFirst\":{\"branch\":\"paid\",\"payload\":{\"paymentReference\":5}}}}", sim);
        assertThat(bad.json()).contains("TEST_MOCK_INVALID");
    }
}
