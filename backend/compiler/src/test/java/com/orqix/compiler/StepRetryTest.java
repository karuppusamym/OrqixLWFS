package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.node.ObjectNode;

/**
 * CG-W04 step retry policy (docs/build/S131): {@code config.retry} replaces the workflow-wide attempts and delay for
 * that step only, is written to the step's {@code orqix:retry} for the failed-job command, round-trips through BPMN,
 * and leaves steps without one unchanged. Bounds are in {@code examples/fixtures/compiler-cases.json}.
 */
class StepRetryTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();
    /** Index of the "procure" call in examples/purchase-approval.json. */
    private static final int PROCURE = 5;

    private static ObjectNode purchase(Consumer<ObjectNode> retry) {
        ObjectNode doc = TestSupport.purchase();
        ObjectNode config = (ObjectNode) doc.get("nodes").get(PROCURE).get("config");
        retry.accept(config.putObject("retry"));
        return doc;
    }

    private static ObjectNode full() {
        return purchase(r -> r.put("maxAttempts", 6).put("delay", "PT10S").put("multiplier", 3)
                .put("maxDelay", "PT30M").put("honorRetryAfter", true));
    }

    private static String serviceTask(String bpmn, String id) {
        int start = bpmn.indexOf("<serviceTask id=\"" + id + "\"");
        assertThat(start).as("service task " + id).isNotNegative();
        return bpmn.substring(start, bpmn.indexOf("</serviceTask>", start));
    }

    @Test
    void theStepPolicyDrivesItsRetryCycleAndIsWrittenForTheRuntime() throws CompilationException {
        CompiledWorkflow w = COMPILER.compile(TestSupport.text(full()), TestSupport.defaultContext());
        String procure = serviceTask(w.bpmnXml(), "procure");
        assertThat(procure).contains(">R6/PT10S</flowable:failedJobRetryTimeCycle>")
                .contains("<orqix:retry maxAttempts=\"6\" delay=\"PT10S\" multiplier=\"3\" maxDelay=\"PT30M\""
                        + " honorRetryAfter=\"true\"/>");
    }

    @Test
    void defaultsApplyWhenOnlyAttemptsAndDelayAreGiven() throws CompilationException {
        ObjectNode doc = purchase(r -> r.put("maxAttempts", 4).put("delay", "PT2M"));
        String procure = serviceTask(COMPILER.compile(TestSupport.text(doc), TestSupport.defaultContext()).bpmnXml(),
                "procure");
        assertThat(procure).contains(">R4/PT120S<")
                .contains("<orqix:retry maxAttempts=\"4\" delay=\"PT2M\" multiplier=\"1\" honorRetryAfter=\"true\"/>");
    }

    @Test
    void aWorkflowWithoutStepPoliciesKeepsItsWorkflowWideCycleAndNoRetryElement() throws CompilationException {
        CompiledWorkflow w = COMPILER.compile(TestSupport.purchaseJson(), TestSupport.defaultContext());
        assertThat(w.bpmnXml()).doesNotContain("orqix:retry");
        assertThat(serviceTask(w.bpmnXml(), "procure")).contains(">R3/PT30S<");
    }

    @Test
    void thePolicyIsPartOfTheSemanticHash() throws CompilationException {
        String plain = COMPILER.compile(TestSupport.purchaseJson(), TestSupport.defaultContext()).semanticHash();
        String retried = COMPILER.compile(TestSupport.text(full()), TestSupport.defaultContext()).semanticHash();
        assertThat(retried).isNotEqualTo(plain);
    }

    @Test
    void thePolicyRoundTripsThroughBpmn() throws CompilationException {
        String json = TestSupport.text(full());
        BpmnSource.Export export = BpmnSource.export(json, COMPILER.compile(json, TestSupport.defaultContext()));
        assertThat(export.differences()).isEmpty();
        assertThat(export.identical()).isTrue();
    }

    @Test
    void boundsAreExplainedOnTheRetryMembers() {
        ValidationReport report = COMPILER.validate(TestSupport.text(purchase(r -> r.put("maxAttempts", 3)
                .put("delay", "PT2M").put("maxDelay", "PT1M"))), TestSupport.defaultContext());
        List<String> issues = report.issues().stream().map(i -> i.code() + " " + i.path()).toList();
        assertThat(issues).containsExactly("WF_RETRY_MAX_DELAY_BELOW_DELAY /nodes/5/config/retry/maxDelay");
        assertThat(StrictJson.parse(TestSupport.purchaseJson()).path("nodes").get(PROCURE).path("config").has("retry"))
                .isFalse();
    }
}
