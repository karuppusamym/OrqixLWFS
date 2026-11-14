package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * S152: the AI reviewer of an approval step. The skill must exist, be approved, be a classify or recommend skill with
 * the answers approve and reject, have passed its evaluation and be promoted for the autonomy; inputs follow the skill
 * contract; steps with several approvers, a list of items or fields to fill in are refused; the manifest pins the
 * version and the BPMN round trip keeps the reviewer.
 */
class AiReviewerCompilerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static CompileContext.AiSkillVersion version(int number, String status, String mode, List<String> answers,
            String evalRun, String ceiling) {
        boolean classify = "classify".equals(mode);
        return new CompileContext.AiSkillVersion(number, status, mode, "hash-" + number, "economy",
                classify ? answers.stream().map(a -> new CompileContext.AiLabel(a, "low", false)).toList() : List.of(),
                Map.of("purpose", "string", "amount", "number"), Set.of("purpose"), Map.of(), Set.of(), null, null,
                classify ? List.of() : answers, evalRun, ceiling);
    }

    private static CompileContext context(CompileContext.AiSkillVersion... versions) {
        Map<Integer, CompileContext.AiSkillVersion> byNumber = new java.util.LinkedHashMap<>();
        for (CompileContext.AiSkillVersion v : versions) {
            byNumber.put(v.version(), v);
        }
        return SimulatorFixtures.context().workspace().withAiSkills(Map.of("purchase-review",
                new CompileContext.AiSkillInfo("purchase-review", "Purchase review", byNumber)));
    }

    private static CompileContext approved() {
        return context(version(1, "approved", "classify", List.of("approve", "reject"), "run-1", "auto"),
                version(2, "draft", "classify", List.of("approve", "reject"), null, "suggest"));
    }

    /** The purchase template with an AI reviewer on the manager approval. */
    private static ObjectNode workflow(String autonomy) {
        ObjectNode d = TestSupport.purchase();
        manager(d).set("aiReviewer", MAPPER.readTree("{\"skill\":{\"key\":\"purchase-review\",\"version\":1},"
                + "\"autonomy\":\"" + autonomy + "\",\"minConfidence\":0.9,"
                + "\"inputs\":{\"purpose\":\"/input/purpose\",\"amount\":\"/input/amount\"},"
                + "\"redaction\":{\"purpose\":\"maskCardNumbersAndEmails\"}}"));
        return d;
    }

    private static ObjectNode manager(ObjectNode d) {
        for (JsonNode n : d.path("nodes")) {
            if ("manager".equals(n.path("id").asString())) {
                return (ObjectNode) n.path("config");
            }
        }
        throw new AssertionError("manager");
    }

    private static ObjectNode reviewer(ObjectNode d) {
        return (ObjectNode) manager(d).path("aiReviewer");
    }

    private static List<String> codes(ObjectNode d, CompileContext context, Severity severity) {
        return Compilers.workflowCompiler().validate(d.toString(), context).issues().stream()
                .filter(i -> i.severity() == severity).map(ValidationIssue::code).toList();
    }

    private static List<ValidationIssue> blockers(ObjectNode d, CompileContext context) {
        return Compilers.workflowCompiler().validate(d.toString(), context).issues().stream()
                .filter(i -> i.severity() == Severity.BLOCKER).toList();
    }

    @Test
    void anApprovalWithAnAiReviewerCompilesAndPinsTheSkillVersion() throws Exception {
        ObjectNode d = workflow("autoWithReview");
        assertThat(codes(d, approved(), Severity.BLOCKER)).isEmpty();
        CompiledWorkflow compiled = Compilers.workflowCompiler().compile(d.toString(), approved());
        assertThat(compiled.aiSkills()).containsExactly(new CompiledWorkflow.AiSkillRef("manager", "purchase-review",
                1, "hash-1", "economy", "autoWithReview", "run-1"));
        assertThat(compiled.bpmnXml()).contains("aiReviewer=");
        BpmnSource.Export export = BpmnSource.export(d.toString(), compiled);
        assertThat(export.differences()).as(export.differences().toString()).isEmpty();
        // Approvals without a reviewer generate exactly the BPMN they did.
        assertThat(Compilers.workflowCompiler().compile(TestSupport.purchase().toString(), approved()).bpmnXml())
                .doesNotContain("aiReviewer");
    }

    @Test
    void theSkillMustBeAnApprovedEvaluatedApproveOrRejectSkill() {
        assertThat(codes(workflow("suggest"), SimulatorFixtures.context().workspace(), Severity.BLOCKER))
                .contains("WF_AI_REVIEWER_SKILL");
        ObjectNode draft = workflow("suggest");
        ((ObjectNode) reviewer(draft).path("skill")).put("version", 2);
        assertThat(codes(draft, approved(), Severity.BLOCKER)).contains("WF_AI_SKILL_NOT_APPROVED");

        CompileContext extract = context(new CompileContext.AiSkillVersion(1, "approved", "extract", "hash-1", "economy",
                List.of(), Map.of("purpose", "string"), Set.of(), Map.of("x", "string"), Set.of(), null, null,
                List.of(), "run-1", "auto"));
        List<ValidationIssue> mode = blockers(workflow("suggest"), extract);
        assertThat(mode).extracting(ValidationIssue::code).contains("WF_AI_REVIEWER_SKILL");
        assertThat(mode.toString()).contains("must be a classify or recommend skill");

        CompileContext noReject = context(version(1, "approved", "recommend", List.of("approve", "escalate"), "run-1",
                "auto"));
        List<ValidationIssue> answers = blockers(workflow("suggest"), noReject);
        assertThat(answers).extracting(ValidationIssue::code).contains("WF_AI_REVIEWER_SKILL");
        assertThat(answers.toString()).contains("has no outcome \"reject\"");
        // A recommend skill with both outcomes (and more) is fine.
        assertThat(codes(workflow("auto"), context(version(1, "approved", "recommend",
                List.of("approve", "reject", "escalate"), "run-1", "auto")), Severity.BLOCKER)).isEmpty();

        assertThat(codes(workflow("suggest"), context(version(1, "approved", "classify", List.of("approve", "reject"),
                null, "auto")), Severity.BLOCKER)).contains("WF_AI_EVALUATION_REQUIRED");
        assertThat(codes(workflow("auto"), context(version(1, "approved", "classify", List.of("approve", "reject"),
                "run-1", "autoWithReview")), Severity.BLOCKER)).contains("WF_AI_AUTONOMY_NOT_PROMOTED");
    }

    @Test
    void inputsFollowTheSkillContract() {
        ObjectNode wrongType = workflow("suggest");
        ((ObjectNode) reviewer(wrongType).path("inputs")).put("amount", "/input/title");
        List<ValidationIssue> issues = blockers(wrongType, approved());
        assertThat(issues).extracting(ValidationIssue::code).contains("WF_AI_INPUT_TYPE");
        assertThat(issues).extracting(ValidationIssue::path).contains("/nodes/1/config/aiReviewer/inputs/amount");

        ObjectNode missing = workflow("suggest");
        ((ObjectNode) reviewer(missing).path("inputs")).remove("purpose");
        assertThat(codes(missing, approved(), Severity.BLOCKER)).contains("WF_AI_INPUT_MISSING",
                "WF_AI_REDACTION_UNKNOWN");
    }

    @Test
    void stepsOneAnswerCannotDecideAreRefused() {
        ObjectNode several = workflow("auto");
        manager(several).set("multiInstance", MAPPER.readTree("{\"approvers\":2}"));
        List<ValidationIssue> issues = blockers(several, approved());
        assertThat(issues).extracting(ValidationIssue::code).contains("WF_AI_REVIEWER_UNSUPPORTED");
        assertThat(issues.toString()).contains("needs several people to approve");

        ObjectNode required = workflow("auto");
        manager(required).set("fieldAccess", MAPPER.readTree("{\"costCenter\":\"required\"}"));
        assertThat(blockers(required, approved()).toString()).contains("WF_AI_REVIEWER_UNSUPPORTED",
                "has fields the person deciding must fill in");

        ObjectNode capture = workflow("auto");
        manager(capture).set("capture", MAPPER.readTree("{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"required\":[\"poNumber\"],\"properties\":{\"poNumber\":{\"type\":\"string\",\"title\":\"PO number\","
                + "\"maxLength\":20}}}"));
        assertThat(codes(capture, approved(), Severity.BLOCKER)).contains("WF_AI_REVIEWER_UNSUPPORTED");

        // An optional captured field stays empty when AI decides: allowed.
        manager(capture).set("capture", MAPPER.readTree("{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"poNumber\":{\"type\":\"string\",\"title\":\"PO number\",\"maxLength\":20}}}"));
        assertThat(codes(capture, approved(), Severity.BLOCKER)).doesNotContain("WF_AI_REVIEWER_UNSUPPORTED");
    }

    @Test
    void theReviewSampleAppliesOnlyToAutoWithReview() {
        ObjectNode d = workflow("auto");
        reviewer(d).put("reviewSamplePercent", 20);
        assertThat(codes(d, approved(), Severity.WARNING)).contains("WF_AI_REVIEW_SAMPLE_UNUSED");
        ObjectNode schema = workflow("auto");
        reviewer(schema).put("autonomy", "shadow");
        assertThat(codes(schema, approved(), Severity.BLOCKER)).isNotEmpty();
    }
}
