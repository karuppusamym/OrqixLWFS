package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-A01 (docs/build/S134): the AI task step. Routes per mode and autonomy, the pinned approved skill version, the
 * publish gate, the autonomy ceiling, input contract checks, AI answers that exist only when AI answered, BPMN with the
 * generated route gateway, the BPMN round trip and the simulator's mocked answers.
 */
class AiTaskCompilerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String INPUT = "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":100}";

    private static JsonNode n(String json) {
        return MAPPER.readTree(json);
    }

    static CompileContext.AiSkillVersion version(int number, String status, String evalRun, String ceiling) {
        return new CompileContext.AiSkillVersion(number, status, "classify", "hash-" + number, "economy",
                List.of(new CompileContext.AiLabel("routine", "low", false),
                        new CompileContext.AiLabel("unusual", "high", true)),
                Map.of("purpose", "string", "amount", "number"), Set.of("purpose"), Map.of(), Set.of(), null, null,
                List.of(), evalRun, ceiling);
    }

    static CompileContext context(CompileContext.AiSkillVersion... versions) {
        Map<Integer, CompileContext.AiSkillVersion> byNumber = new java.util.LinkedHashMap<>();
        for (CompileContext.AiSkillVersion v : versions) {
            byNumber.put(v.version(), v);
        }
        return SimulatorFixtures.context().workspace().withAiSkills(Map.of("purchase-triage",
                new CompileContext.AiSkillInfo("purchase-triage", "Purchase triage", byNumber)));
    }

    static CompileContext approved() {
        return context(version(1, "approved", "run-1", "auto"), version(2, "draft", null, "suggest"));
    }

    /** start -> triage (AI) -> manager for every route. */
    static ObjectNode workflow(String autonomy) {
        ObjectNode d = TestSupport.purchase();
        ((ArrayNode) d.path("nodes")).add(n("{\"id\":\"triage\",\"type\":\"aiTask\",\"label\":\"Triage the purchase\","
                + "\"config\":{\"skillRef\":\"purchase-triage\",\"mode\":\"classify\",\"autonomy\":\"" + autonomy + "\","
                + "\"minConfidence\":0.85,\"inputMapping\":{\"purpose\":\"/input/purpose\",\"amount\":\"/input/amount\"},"
                + "\"redaction\":{\"purpose\":\"maskCardNumbersAndEmails\"}}}"));
        ((ObjectNode) d.path("uiLayout").path("positions")).set("triage", n("{\"x\":50,\"y\":50}"));
        for (JsonNode t : d.path("transitions")) {
            if ("t1".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "triage");
            }
        }
        ArrayNode transitions = (ArrayNode) d.path("transitions");
        if (List.of("auto", "autoWithReview").contains(autonomy)) {
            transitions.add(n("{\"id\":\"a1\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"aiLabel\",\"aiLabel\":\"routine\"}"));
            transitions.add(n("{\"id\":\"a2\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"aiLabel\",\"aiLabel\":\"unusual\"}"));
        }
        if ("shadow".equals(autonomy)) {
            transitions.add(n("{\"id\":\"a0\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"next\"}"));
        } else {
            transitions.add(n("{\"id\":\"a3\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"lowConfidence\"}"));
            transitions.add(n("{\"id\":\"a4\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"aiUnavailable\"}"));
        }
        return d;
    }

    private static List<String> codes(ObjectNode d, CompileContext context, Severity severity) {
        return Compilers.workflowCompiler().validate(d.toString(), context).issues().stream()
                .filter(i -> i.severity() == severity).map(ValidationIssue::code).toList();
    }

    @Test
    void anAutomaticClassifyTaskCompilesToATriggerableTaskWithARouteGateway() throws Exception {
        ObjectNode d = workflow("auto");
        assertThat(codes(d, approved(), Severity.BLOCKER)).isEmpty();
        CompiledWorkflow compiled = Compilers.workflowCompiler().compile(d.toString(), approved());
        String xml = compiled.bpmnXml();
        GeneratedXmlTest.onlyAllowedExpressions(xml);
        assertThat(xml).contains("flowable:triggerable=\"true\" flowable:delegateExpression=\"${orqixAiTask}\"",
                "<orqix:aiTask skill=\"purchase-triage\" skillVersion=\"1\" contentHash=\"hash-1\" mode=\"classify\""
                        + " autonomy=\"auto\" minConfidence=\"0.85\" reviewSamplePercent=\"10\">",
                "<orqix:redaction name=\"purpose\" rule=\"maskCardNumbersAndEmails\"/>",
                "<exclusiveGateway id=\"triage__aiRoute\"",
                "${orqixRoute.ai(execution, 'triage', 'label:routine')}",
                "${orqixRoute.ai(execution, 'triage', 'lowConfidence')}",
                "${orqixRoute.ai(execution, 'triage', 'aiUnavailable')}")
                .doesNotContain("pinnedByDesigner");
        assertThat(compiled.aiSkills()).containsExactly(new CompiledWorkflow.AiSkillRef("triage", "purchase-triage", 1,
                "hash-1", "economy", "auto", "run-1"));
    }

    @Test
    void theSkillMustBeApprovedEvaluatedAndPromotedForTheAutonomy() {
        assertThat(codes(workflow("auto"), SimulatorFixtures.context().workspace(), Severity.BLOCKER))
                .contains("WF_AI_SKILL_UNKNOWN");
        assertThat(codes(workflow("auto"), context(version(1, "submitted", "run-1", "auto")), Severity.BLOCKER))
                .contains("WF_AI_SKILL_NOT_APPROVED");
        assertThat(codes(workflow("auto"), context(version(1, "approved", null, "auto")), Severity.BLOCKER))
                .contains("WF_AI_EVALUATION_REQUIRED");
        // Shadow mode collects evidence before an evaluation run exists, and never needs a promotion.
        assertThat(codes(workflow("shadow"), context(version(1, "approved", null, "suggest")), Severity.BLOCKER))
                .isEmpty();
        assertThat(codes(workflow("auto"), context(version(1, "approved", "run-1", "suggest")), Severity.BLOCKER))
                .contains("WF_AI_AUTONOMY_NOT_PROMOTED");
        // A designer pin on an unapproved version is refused; a pin on the approved one is kept in the BPMN.
        ObjectNode pinned = workflow("auto");
        config(pinned).put("skillVersion", 2);
        assertThat(codes(pinned, approved(), Severity.BLOCKER)).contains("WF_AI_SKILL_NOT_APPROVED");
        config(pinned).put("skillVersion", 1);
        assertThat(codes(pinned, approved(), Severity.BLOCKER)).isEmpty();
    }

    @Test
    void routesFollowTheModeAndAutonomy() {
        ObjectNode missing = workflow("auto");
        ((ArrayNode) missing.path("transitions")).removeIf(t -> "a2".equals(t.path("id").asString()));
        assertThat(codes(missing, approved(), Severity.BLOCKER)).contains("WF_AI_LABEL_ROUTE");

        ObjectNode unknown = workflow("auto");
        ((ObjectNode) unknown.path("transitions").get(unknown.path("transitions").size() - 3)).put("aiLabel", "nope");
        assertThat(codes(unknown, approved(), Severity.BLOCKER)).contains("WF_AI_LABEL_UNKNOWN");

        ObjectNode noFallback = workflow("suggest");
        ((ArrayNode) noFallback.path("transitions")).removeIf(t -> "a4".equals(t.path("id").asString()));
        assertThat(codes(noFallback, approved(), Severity.BLOCKER)).contains("WF_AI_ROUTES");

        // With suggest a person decides: label paths are refused.
        ObjectNode suggest = workflow("suggest");
        ((ArrayNode) suggest.path("transitions")).add(n(
                "{\"id\":\"a9\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"aiLabel\",\"aiLabel\":\"routine\"}"));
        assertThat(codes(suggest, approved(), Severity.BLOCKER)).contains("WF_AI_LABEL_ROUTE_SUGGEST");
        assertThat(codes(workflow("suggest"), approved(), Severity.BLOCKER)).isEmpty();

        // A next path is not a route of a classify task.
        ObjectNode next = workflow("auto");
        ((ArrayNode) next.path("transitions")).add(n("{\"id\":\"a8\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"next\"}"));
        assertThat(codes(next, approved(), Severity.BLOCKER)).contains("WF_OUTGOING_INVALID");
    }

    @Test
    void inputsMustMatchTheSkillContract() {
        ObjectNode wrongType = workflow("auto");
        ((ObjectNode) config(wrongType).path("inputMapping")).put("amount", "/input/title");
        assertThat(codes(wrongType, approved(), Severity.BLOCKER)).contains("WF_AI_INPUT_TYPE");

        ObjectNode extra = workflow("auto");
        ((ObjectNode) config(extra).path("inputMapping")).put("other", "/input/title");
        assertThat(codes(extra, approved(), Severity.BLOCKER)).contains("WF_AI_INPUT_UNKNOWN");

        ObjectNode required = workflow("auto");
        ((ObjectNode) config(required).path("inputMapping")).remove("purpose");
        assertThat(codes(required, approved(), Severity.BLOCKER)).contains("WF_AI_INPUT_MISSING",
                "WF_AI_REDACTION_UNKNOWN");
    }

    @Test
    void theAnswerExistsOnlyWhenAiAnswered() {
        // The manager reads the label in a step form? Use it in a branch: reachable through aiUnavailable -> refused.
        ObjectNode d = workflow("suggest");
        ((ArrayNode) d.path("nodes")).add(n("{\"id\":\"which\",\"type\":\"branch\",\"label\":\"Which label?\",\"config\":{}}"));
        for (JsonNode t : d.path("transitions")) {
            if ("a3".equals(t.path("id").asString()) || "a4".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "which");
            }
        }
        ((ArrayNode) d.path("transitions")).add(n("{\"id\":\"w1\",\"from\":\"which\",\"to\":\"manager\",\"on\":\"condition\","
                + "\"label\":\"Routine\",\"condition\":{\"fieldRef\":\"/steps/triage/label\",\"operator\":\"eq\",\"value\":\"routine\"}}"));
        ((ArrayNode) d.path("transitions")).add(n("{\"id\":\"w2\",\"from\":\"which\",\"to\":\"manager\",\"on\":\"default\"}"));
        assertThat(codes(d, approved(), Severity.BLOCKER)).contains("WF_FIELDREF_NOT_GUARANTEED");
        // The status is always there.
        ((ObjectNode) d.path("transitions").get(d.path("transitions").size() - 2).path("condition"))
                .put("fieldRef", "/steps/triage/status").put("value", "needsReview");
        assertThat(codes(d, approved(), Severity.BLOCKER)).doesNotContain("WF_FIELDREF_NOT_GUARANTEED");
    }

    @Test
    void theBpmnRoundTripKeepsTheAiTask() throws Exception {
        ObjectNode d = workflow("autoWithReview");
        config(d).put("reviewSamplePercent", 25);
        CompiledWorkflow compiled = Compilers.workflowCompiler().compile(d.toString(), approved());
        BpmnSource.Export export = BpmnSource.export(d.toString(), compiled);
        assertThat(export.differences()).as(export.differences().toString()).isEmpty();
        // A designer pin survives the round trip too.
        config(d).put("skillVersion", 1);
        compiled = Compilers.workflowCompiler().compile(d.toString(), approved());
        assertThat(compiled.bpmnXml()).contains("pinnedByDesigner=\"true\"");
        assertThat(BpmnSource.export(d.toString(), compiled).differences()).isEmpty();
    }

    @Test
    void theSimulatorMocksTheAnswerAndFollowsTheRuntimeRouting() {
        SimulationContext context = new SimulationContext(approved(), null, null);
        JsonNode routed = run(workflow("auto"), "{\"ai\":{\"triage\":{\"label\":\"unusual\",\"confidence\":0.9}}}", context);
        assertThat(routed.toString()).contains("\"route\":\"label:unusual\"", "No model is called in a test");
        JsonNode low = run(workflow("auto"), "{\"ai\":{\"triage\":{\"label\":\"routine\",\"confidence\":0.5}}}", context);
        assertThat(low.toString()).contains("\"route\":\"lowConfidence\"");
        JsonNode suggest = run(workflow("suggest"), "{}", context);
        assertThat(suggest.toString()).contains("\"route\":\"lowConfidence\"", "\"status\":\"needsReview\"");
        JsonNode down = run(workflow("auto"), "{\"ai\":{\"triage\":{\"unavailable\":true}}}", context);
        assertThat(down.toString()).contains("the path for when AI is unavailable");
        assertThatThrownBy(() -> run(workflow("auto"), "{\"ai\":{\"triage\":{\"label\":\"nope\"}}}", context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JsonNode run(ObjectNode d, String scenario, SimulationContext context) {
        SimulationResult result = Compilers.workflowSimulator().simulate(d.toString(), INPUT, scenario, context);
        assertThat(result.hasTrace()).as(result.status() + " " + result.blockers()).isTrue();
        return com.orqix.compiler.internal.json.StrictJson.parse(result.json());
    }

    private static ObjectNode config(ObjectNode d) {
        for (JsonNode x : d.path("nodes")) {
            if ("triage".equals(x.path("id").asString())) {
                return (ObjectNode) x.path("config");
            }
        }
        throw new AssertionError("triage");
    }

    @SuppressWarnings("unused")
    private static final BigDecimal UNUSED = BigDecimal.ONE;
}
