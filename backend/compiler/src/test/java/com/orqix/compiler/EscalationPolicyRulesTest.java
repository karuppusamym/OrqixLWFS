package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.node.ObjectNode;

/** CG-T04 (docs/build/S141): escalation policies on approval steps. */
class EscalationPolicyRulesTest {

    private static ObjectNode approval(ObjectNode workflow) {
        return (ObjectNode) workflow.path("nodes").get(1).path("config");
    }

    private static ValidationReport report(ObjectNode workflow) {
        return Compilers.workflowCompiler().validate(TestSupport.text(workflow), TestSupport.defaultContext());
    }

    private static void policy(ObjectNode workflow, String levels) {
        approval(workflow).set("escalationPolicy", StrictJson.parse("{\"levels\":" + levels + "}"));
    }

    @Test
    void validPolicyCompilesAndIsCarriedInTheBpmn() throws Exception {
        ObjectNode workflow = TestSupport.purchase();
        approval(workflow).put("dueAfterHours", 8);
        approval(workflow).set("calendar", StrictJson.parse(
                "{\"zone\":\"UTC\",\"weekdays\":[1,2,3,4,5],\"start\":\"09:00\",\"end\":\"17:00\"}"));
        policy(workflow, "[{\"afterHours\":4,\"action\":\"notify\",\"groupRef\":\"finance-approvers\"},"
                + "{\"afterHours\":8,\"action\":\"reassign\",\"groupRef\":\"finance-approvers\"},"
                + "{\"afterHours\":16,\"action\":\"alertOperators\"}]");
        assertThat(report(workflow).publishable()).isTrue();
        assertThat(report(workflow).issues()).isEmpty();
        CompiledWorkflow compiled = Compilers.workflowCompiler().compile(TestSupport.text(workflow), TestSupport.defaultContext());
        assertThat(compiled.bpmnXml()).contains("escalationPolicy=").contains("alertOperators");
    }

    @Test
    void ordersGroupsAndKnownGroupsAreChecked() {
        ObjectNode workflow = TestSupport.purchase();
        policy(workflow, "[{\"afterHours\":8,\"action\":\"notify\",\"groupRef\":\"no-such-group\"},"
                + "{\"afterHours\":8,\"action\":\"reassign\"},"
                + "{\"afterHours\":4,\"action\":\"alertOperators\",\"groupRef\":\"managers\"}]");
        assertThat(report(workflow).issues()).extracting(ValidationIssue::code).contains(
                "WF_ESCALATION_POLICY_GROUP_UNBOUND", "WF_ESCALATION_POLICY_ORDER", "WF_ESCALATION_POLICY_GROUP");
        assertThat(report(workflow).publishable()).isFalse();
    }

    @Test
    void reassigningToTheSameQueueIsAWarning() {
        ObjectNode workflow = TestSupport.purchase();
        String queue = approval(workflow).path("candidateGroupRef").asString();
        policy(workflow, "[{\"afterHours\":2,\"action\":\"reassign\",\"groupRef\":\"" + queue + "\"}]");
        ValidationReport report = report(workflow);
        assertThat(report.issues()).extracting(ValidationIssue::code).containsExactly("WF_ESCALATION_POLICY_SAME_QUEUE");
        assertThat(report.publishable()).isTrue();
    }

    @Test
    void schemaBoundsLevelsAndActions() {
        ObjectNode workflow = TestSupport.purchase();
        policy(workflow, "[]");
        assertThat(report(workflow).publishable()).isFalse();
        policy(workflow, "[{\"afterHours\":0,\"action\":\"escalate\"}]");
        assertThat(report(workflow).issues()).extracting(ValidationIssue::code).contains("WF_SCHEMA_INVALID");
        policy(workflow, "[{\"afterHours\":1,\"action\":\"notify\",\"groupRef\":\"managers\"},"
                + "{\"afterHours\":2,\"action\":\"notify\",\"groupRef\":\"managers\"},"
                + "{\"afterHours\":3,\"action\":\"notify\",\"groupRef\":\"managers\"},"
                + "{\"afterHours\":4,\"action\":\"notify\",\"groupRef\":\"managers\"},"
                + "{\"afterHours\":5,\"action\":\"notify\",\"groupRef\":\"managers\"},"
                + "{\"afterHours\":6,\"action\":\"notify\",\"groupRef\":\"managers\"}]");
        assertThat(report(workflow).issues()).extracting(ValidationIssue::code).contains("WF_SCHEMA_INVALID");
    }
}
