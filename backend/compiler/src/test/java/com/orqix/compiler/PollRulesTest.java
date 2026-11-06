package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Keep checking until..." (CG-W01) and the safety-net check of a message wait (CG-W03), docs/build/S129: schema,
 * every WF_POLL_* rule, BPMN shape and round trip, the shared schedule and the simulator with answer sequences.
 */
class PollRulesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String INPUT = "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":100}";

    static CompileContext context() {
        CompileContext base = TestSupport.defaultContext();
        List<CompileContext.ConnectionVersion> versions = new ArrayList<>(base.connectionVersions());
        String definition = TestSupport.read("examples/connections/order-status.connection.json");
        versions.add(new CompileContext.ConnectionVersion("order-status", 1, definition, CanonicalJson.sha256Hex(definition)));
        Set<String> enabled = new HashSet<>(base.enabledConnectionBindings());
        enabled.add("order-status");
        return new CompileContext(base.selfApprovalAllowed(), base.groups(), versions, enabled);
    }

    static SimulationContext simulation() {
        return new SimulationContext(context(), key -> null, LocalDate.parse("2026-01-15"));
    }

    static String pollJson() {
        return """
                {"every":"PT5M","backoff":{"multiplier":2,"maxInterval":"PT1H"},"jitterPercent":10,
                 "until":{"fieldRef":"/steps/track/state","operator":"eq","value":"shipped"},
                 "failWhen":{"fieldRef":"/steps/track/state","operator":"eq","value":"cancelled"},
                 "giveUpAfter":"P2D","onCheckError":{"maxConsecutive":3},"showWhileWaiting":["state"]}""";
    }

    /** purchase ... procure -> track (polls order-status) -> notify; failed and timedOut go to notifyRejected. */
    static ObjectNode definition(String poll, String operation, boolean failedRoute, boolean timedOutRoute) throws Exception {
        ObjectNode d = TestSupport.purchase();
        ((ArrayNode) d.path("dependencies").path("connections"))
                .add(MAPPER.readTree("{\"key\":\"order-status\",\"definitionVersion\":1}"));
        ((ArrayNode) d.path("nodes")).add(MAPPER.readTree("""
                {"id":"track","type":"http","label":"Track the order","config":{"connectionRef":"order-status",
                 "operation":"%s","inputMapping":{"orderId":"/steps/procure/procurementId"},
                 "resultMapping":{"state":"/state"},"timeoutSeconds":10,"poll":%s}}""".formatted(operation, poll)));
        ArrayNode transitions = (ArrayNode) d.path("transitions");
        for (JsonNode t : transitions) {
            if ("t9".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "track");
            }
        }
        transitions.add(MAPPER.readTree("{\"id\":\"p1\",\"from\":\"track\",\"to\":\"notify\",\"on\":\"next\"}"));
        if (failedRoute) {
            transitions.add(MAPPER.readTree("{\"id\":\"p2\",\"from\":\"track\",\"to\":\"notifyRejected\",\"on\":\"failed\"}"));
        }
        if (timedOutRoute) {
            transitions.add(MAPPER.readTree("{\"id\":\"p3\",\"from\":\"track\",\"to\":\"notifyRejected\",\"on\":\"timedOut\"}"));
        }
        d.remove("uiLayout");
        return d;
    }

    static ObjectNode definition() throws Exception {
        return definition(pollJson(), "getStatus", true, true);
    }

    private static List<String> issues(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), context()).issues().stream()
                .map(i -> (i.severity() == Severity.BLOCKER ? "B " : "W ") + i.code() + " " + i.path()).toList();
    }

    private static List<String> blockers(ObjectNode d) {
        return issues(d).stream().filter(i -> i.startsWith("B ")).toList();
    }

    private static ObjectNode poll(ObjectNode d) {
        for (JsonNode n : d.path("nodes")) {
            if ("track".equals(n.path("id").asString())) {
                return (ObjectNode) n.path("config").path("poll");
            }
        }
        throw new AssertionError();
    }

    @Test
    void aValidCheckUntilStepCompilesToATriggerableTaskAndAnOutcomeGateway() throws Exception {
        ObjectNode d = definition();
        assertThat(blockers(d)).isEmpty();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), context()).bpmnXml();
        assertThat(bpmn).contains("delegateExpression=\"${orqixPollTask}\"", "flowable:triggerable=\"true\"",
                "<orqix:poll configBase64=", "id=\"track__pollRoute\"", "id=\"track__toPollRoute\"",
                "${orqixRoute.polled(execution, 'track', 'met')}", "${orqixRoute.polled(execution, 'track', 'failed')}",
                "${orqixRoute.polled(execution, 'track', 'timedOut')}");
        assertThat(bpmn).doesNotContain("track__failed");
    }

    @Test
    void theBpmnRoundTripKeepsThePollSettings() throws Exception {
        ObjectNode d = definition();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), context()).bpmnXml();
        var compiled = Compilers.workflowCompiler().compile(d.toString(), context());
        assertThat(BpmnSource.export(d.toString(), compiled).differences()).isEmpty();
        BpmnSource.Check read = BpmnSource.check(bpmn, d.toString(), Compilers.workflowCompiler(), json -> context());
        assertThat(read.problems()).isEmpty();
        JsonNode definition = com.orqix.compiler.internal.json.StrictJson.parse(read.definitionJson());
        JsonNode track = null;
        for (JsonNode n : definition.path("nodes")) {
            if ("track".equals(n.path("id").asString())) {
                track = n;
            }
        }
        assertThat(track).isNotNull();
        assertThat(track.path("config").path("poll").path("every").asString()).isEqualTo("PT5M");
        assertThat(track.path("config").path("poll").path("failWhen").path("value").asString()).isEqualTo("cancelled");
        List<String> routes = new ArrayList<>();
        for (JsonNode t : definition.path("transitions")) {
            if ("track".equals(t.path("from").asString())) {
                routes.add(t.path("on").asString());
            }
        }
        assertThat(routes).containsExactlyInAnyOrder("next", "failed", "timedOut");
    }

    @Test
    void withoutPollTheCallIsUnchanged() throws Exception {
        ObjectNode d = TestSupport.purchase();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), context()).bpmnXml();
        assertThat(bpmn).doesNotContain("orqixPollTask", "__pollRoute");
    }

    @Test
    void theOperationMustOnlyRead() throws Exception {
        assertThat(blockers(definition(pollJson(), "markShipped", true, true)))
                .contains("B WF_POLL_READONLY /nodes/10/config/operation");
    }

    @Test
    void intervalBackoffAndDeadlineAreBounded() throws Exception {
        ObjectNode d = definition();
        poll(d).put("every", "PT5S");
        assertThat(blockers(d)).contains("B WF_POLL_INTERVAL /nodes/10/config/poll/every");
        d = definition();
        poll(d).put("every", "P2D");
        assertThat(blockers(d)).contains("B WF_POLL_INTERVAL /nodes/10/config/poll/every");
        d = definition();
        ((ObjectNode) poll(d).path("backoff")).put("maxInterval", "PT1M");
        assertThat(blockers(d)).contains("B WF_POLL_BACKOFF /nodes/10/config/poll/backoff/maxInterval");
        d = definition();
        poll(d).put("giveUpAfter", "P91D");
        assertThat(blockers(d)).contains("B WF_POLL_DEADLINE /nodes/10/config/poll/giveUpAfter");
        d = definition();
        poll(d).put("giveUpAfter", "PT2M");
        assertThat(blockers(d)).contains("B WF_POLL_DEADLINE /nodes/10/config/poll/giveUpAfter");
        d = definition();
        poll(d).put("jitterPercent", 31);
        assertThat(blockers(d)).isNotEmpty();
    }

    @Test
    void theWorstCaseNumberOfChecksIsCapped() throws Exception {
        ObjectNode d = definition();
        poll(d).remove("backoff");
        poll(d).put("every", "PT1M");
        poll(d).put("giveUpAfter", "P2D");
        assertThat(blockers(d)).contains("B WF_POLL_TOO_MANY_CHECKS /nodes/10/config/poll");
        poll(d).put("maxChecks", 200);
        assertThat(blockers(d)).isEmpty();
    }

    @Test
    void timedOutIsRequiredAndFailedIsRequiredWithAFailedCondition() throws Exception {
        assertThat(blockers(definition(pollJson(), "getStatus", true, false))).contains("B WF_POLL_ROUTES /nodes/10");
        assertThat(blockers(definition(pollJson(), "getStatus", false, true))).contains("B WF_POLL_ROUTES /nodes/10");
        ObjectNode d = definition(pollJson(), "getStatus", false, true);
        poll(d).remove("failWhen");
        assertThat(blockers(d)).isEmpty();
    }

    @Test
    void aTimedOutPathNeedsPolling() throws Exception {
        ObjectNode d = definition();
        for (JsonNode n : d.path("nodes")) {
            if ("track".equals(n.path("id").asString())) {
                ((ObjectNode) n.path("config")).remove("poll");
            }
        }
        assertThat(blockers(d)).contains("B WF_OUTGOING_INVALID /transitions/13/on");
    }

    @Test
    void conditionsUseTheCheckedValuesAndMustNotOverlap() throws Exception {
        ObjectNode d = definition();
        poll(d).set("until", MAPPER.readTree("{\"fieldRef\":\"/steps/track/missing\",\"operator\":\"eq\",\"value\":\"x\"}"));
        assertThat(blockers(d)).contains("B WF_POLL_CONDITION /nodes/10/config/poll/until/fieldRef");
        d = definition();
        poll(d).set("failWhen", MAPPER.readTree("{\"fieldRef\":\"/steps/track/state\",\"operator\":\"in\",\"value\":[\"shipped\",\"cancelled\"]}"));
        assertThat(blockers(d)).contains("B WF_POLL_OVERLAP /nodes/10/config/poll/failWhen");
        d = definition();
        poll(d).set("until", MAPPER.readTree("{\"fieldRef\":\"/steps/track/state\",\"operator\":\"eq\",\"value\":\"lost\"}"));
        assertThat(blockers(d)).contains("B WF_LITERAL_NOT_IN_DOMAIN /nodes/10/config/poll/until/value");
        d = definition();
        poll(d).set("until", MAPPER.readTree("{\"all\":[{\"fieldRef\":\"/steps/track/state\",\"operator\":\"eq\",\"value\":\"shipped\"},"
                + "{\"fieldRef\":\"/input/amount\",\"operator\":\"gt\",\"value\":5000}]}"));
        assertThat(blockers(d)).isEmpty();
    }

    @Test
    void shownFieldsMustBeResultsAndCannotBeCombinedWithUndoOrRaise() throws Exception {
        ObjectNode d = definition();
        ((ArrayNode) poll(d).path("showWhileWaiting")).add("progress");
        assertThat(blockers(d)).contains("B WF_POLL_SHOW /nodes/10/config/poll/showWhileWaiting/1");
        d = definition();
        for (JsonNode n : d.path("nodes")) {
            if ("track".equals(n.path("id").asString())) {
                ((ObjectNode) n.path("config")).put("raiseErrorOnFailure", "TRACKING_FAILED");
            }
        }
        assertThat(blockers(d)).contains("B WF_POLL_COMBINATION /nodes/10/config/poll");
    }

    @Test
    void resultsExistOnlyAfterTheWorkIsDone() throws Exception {
        ObjectNode d = definition();
        // notifyRejected is reached on failed and timedOut too, so it cannot use the result.
        for (JsonNode n : d.path("nodes")) {
            if ("notifyRejected".equals(n.path("id").asString())) {
                ObjectNode replaced = (ObjectNode) MAPPER.readTree("""
                        {"id":"notifyRejected","type":"http","label":"Report","config":{"connectionRef":"order-status",
                         "operation":"getStatus","inputMapping":{"orderId":"/steps/track/state"},"resultMapping":{},
                         "timeoutSeconds":5}}""");
                ((ObjectNode) n).setAll(replaced);
            }
        }
        assertThat(blockers(d)).anyMatch(i -> i.startsWith("B WF_FIELDREF_NOT_GUARANTEED"));
    }

    // ------------------------------------------------------------------ schedule

    @Test
    void theScheduleMatchesTheWorkedTimingOfTheDesign() {
        PollSchedule s = new PollSchedule(300, BigDecimal.valueOf(2), 3600, 172_800, true, true, 1000);
        List<Long> offsets = s.offsets(1000);
        assertThat(offsets.subList(0, 6)).containsExactly(0L, 300L, 900L, 2100L, 4500L, 8100L);
        assertThat(offsets).hasSize(52);
        assertThat(offsets.get(50)).isEqualTo(47 * 3600 + 15 * 60L);
        assertThat(offsets.get(51)).isEqualTo(172_800L);
        assertThat(s.summary("ServiceNow", 500)).isEqualTo("Checks up to 52 times over 2 days; from 1 hour 15 minutes"
                + " on, once an hour. With 500 requests waiting this is about 500 calls an hour to ServiceNow.");
        PollSchedule constant = new PollSchedule(900, BigDecimal.ONE, 900, 3600, false, false, 1000);
        assertThat(constant.offsets(100)).containsExactly(900L, 1800L, 2700L);
        assertThat(PollSchedule.seconds("PT30S")).isEqualTo(30);
        assertThat(PollSchedule.seconds("P1DT2H")).isEqualTo(93_600);
        assertThat(PollSchedule.seconds("PT1W")).isEqualTo(-1);
    }

    // ------------------------------------------------------------------ simulator

    private static JsonNode simulate(ObjectNode d, String scenario) {
        SimulationResult result = Compilers.workflowSimulator().simulate(d.toString(), INPUT, scenario, simulation());
        assertThat(result.hasTrace()).as(result.status() + " " + result.blockers()).isTrue();
        return com.orqix.compiler.internal.json.StrictJson.parse(result.json());
    }

    private static JsonNode step(JsonNode trace, String id) {
        for (JsonNode e : trace.path("trace")) {
            if (id.equals(e.path("stepId").asString())) {
                return e;
            }
        }
        throw new AssertionError("no " + id + " in " + trace);
    }

    @Test
    void theSimulatorPlaysASequenceOfAnswersWithTheirSimulatedTimes() throws Exception {
        JsonNode trace = simulate(definition(), """
                {"calls":{"track":{"checks":[
                  {"result":{"state":"pending"}},{"result":{"state":"pending"}},{"result":{"state":"pending"}},
                  {"result":{"state":"shipped"}}]}}}""");
        JsonNode track = step(trace, "track");
        assertThat(track.path("nextStepId").asString()).isEqualTo("notify");
        assertThat(track.path("details").path("checksMade").asInt()).isEqualTo(4);
        assertThat(track.path("details").path("checks").get(3).path("atSeconds").asLong()).isEqualTo(2100);
        assertThat(track.path("summary").asString()).contains("4 times", "0:35", "shipped");
    }

    @Test
    void theSimulatorTakesTheFailedAndTimedOutPaths() throws Exception {
        JsonNode failed = step(simulate(definition(), """
                {"calls":{"track":{"checks":[
                  {"result":{"state":"pending"}},{"result":{"state":"cancelled"}}]}}}"""), "track");
        assertThat(failed.path("transitionId").asString()).isEqualTo("p2");
        assertThat(failed.path("wrote").path("failureKind").asString()).isEqualTo("remoteFailed");
        JsonNode never = step(simulate(definition(), """
                {"calls":{"track":{"result":{"state":"pending"}}}}"""), "track");
        assertThat(never.path("transitionId").asString()).isEqualTo("p3");
        assertThat(never.path("details").path("checksMade").asInt()).isEqualTo(52);
        JsonNode errors = step(simulate(definition(), """
                {"calls":{"track":{"checks":[{"failure":"unavailable"}]}}}"""), "track");
        assertThat(errors.path("details").path("attention").path("reason").asString()).isEqualTo("POLL_CHECKS_FAILING");
        assertThat(errors.path("details").path("checksMade").asInt()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ CG-W03

    /** A message wait with a safety-net check, after the purchase order is created. */
    static ObjectNode fallbackDefinition(String fallback) throws Exception {
        ObjectNode d = TestSupport.purchase();
        ((ArrayNode) d.path("dependencies").path("connections"))
                .add(MAPPER.readTree("{\"key\":\"order-status\",\"definitionVersion\":1}"));
        ((ArrayNode) d.path("nodes")).add(MAPPER.readTree("""
                {"id":"shipped","type":"waitForMessage","label":"Wait for shipping","config":{"message":"order-shipped",
                 "correlationField":"/steps/procure/procurementId","timeoutAfter":"P5D",
                 "payload":[{"name":"state","type":"string","required":true}],"fallbackCheck":%s}}""".formatted(fallback)));
        ArrayNode transitions = (ArrayNode) d.path("transitions");
        for (JsonNode t : transitions) {
            if ("t9".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "shipped");
            }
        }
        transitions.add(MAPPER.readTree("{\"id\":\"m1\",\"from\":\"shipped\",\"to\":\"notify\",\"on\":\"next\"}"));
        transitions.add(MAPPER.readTree("{\"id\":\"m2\",\"from\":\"shipped\",\"to\":\"notifyRejected\",\"on\":\"timedOut\"}"));
        d.remove("uiLayout");
        return d;
    }

    static String fallbackJson() {
        return """
                {"connectionRef":"order-status","operation":"getStatus","inputMapping":{"orderId":"/steps/procure/procurementId"},
                 "resultMapping":{"state":"/state"},"timeoutSeconds":10,"every":"PT2H",
                 "until":{"fieldRef":"/steps/shipped/state","operator":"eq","value":"shipped"}}""";
    }

    @Test
    void aSafetyNetCheckCompilesAndRoundTrips() throws Exception {
        ObjectNode d = fallbackDefinition(fallbackJson());
        assertThat(blockers(d)).isEmpty();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), context()).bpmnXml();
        assertThat(bpmn).contains("fallbackCheckBase64=", "fallbackConnectionVersion=\"1\"");
        var compiled = Compilers.workflowCompiler().compile(d.toString(), context());
        assertThat(BpmnSource.export(d.toString(), compiled).differences()).isEmpty();
        BpmnSource.Check read = BpmnSource.check(bpmn, d.toString(), Compilers.workflowCompiler(), json -> context());
        assertThat(read.problems()).isEmpty();
        assertThat(read.definitionJson()).contains("\"fallbackCheck\"", "PT2H");
    }

    @Test
    void theSafetyNetCheckRules() throws Exception {
        ObjectNode d = fallbackDefinition(fallbackJson().replace("PT2H", "PT30S"));
        assertThat(blockers(d)).contains("B WF_POLL_INTERVAL /nodes/10/config/fallbackCheck/every");
        d = fallbackDefinition(fallbackJson().replace("getStatus", "markShipped"));
        assertThat(blockers(d)).contains("B WF_POLL_READONLY /nodes/10/config/fallbackCheck/operation");
        d = fallbackDefinition(fallbackJson().replace("\"resultMapping\":{\"state\":\"/state\"}",
                "\"resultMapping\":{\"other\":\"/state\"}"));
        assertThat(blockers(d)).contains("B WF_POLL_FALLBACK_MAPPING /nodes/10/config/fallbackCheck/resultMapping/other",
                "B WF_POLL_FALLBACK_MAPPING /nodes/10/config/fallbackCheck/resultMapping");
        d = fallbackDefinition(fallbackJson().replace("\"timeoutSeconds\":10,", "\"timeoutSeconds\":10,\"maxChecks\":5,")
                .replace("PT2H", "PT1M"));
        assertThat(blockers(d)).isEmpty();
        d = fallbackDefinition(fallbackJson().replace("PT2H", "PT1M"));
        assertThat(blockers(d)).contains("B WF_POLL_TOO_MANY_CHECKS /nodes/10/config/fallbackCheck/every");
    }

    @Test
    void theSimulatorCanEndTheWaitByTheSafetyNetCheck() throws Exception {
        JsonNode wait = step(simulate(fallbackDefinition(fallbackJson()), """
                {"messages":{"shipped":{"outcome":"fallback","payload":{"state":"shipped"}}}}"""),
                "shipped");
        assertThat(wait.path("details").path("resumedBy").asString()).isEqualTo("fallbackCheck");
        assertThat(wait.path("summary").asString()).contains("safety-net check every 2 hours");
    }
}
