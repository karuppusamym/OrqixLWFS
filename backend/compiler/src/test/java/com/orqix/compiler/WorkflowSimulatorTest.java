package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/** The dry run per node kind, scenario control and warning (S4 section 13). */
class WorkflowSimulatorTest {

    private static final String INPUT_1800 = "{\"title\":\"Laptop\",\"purpose\":\"p\",\"costCenter\":\"IT\","
            + "\"amount\":1800}";

    private static WorkflowSimulator simulator() {
        return Compilers.workflowSimulator();
    }

    private static JsonNode purchase(String scenario) {
        return SimulatorFixtures.run(TestSupport.purchaseJson(), INPUT_1800, scenario);
    }

    private static JsonNode entry(JsonNode result, String stepId) {
        for (JsonNode e : result.get("trace")) {
            if (stepId.equals(e.get("stepId").stringValue())) {
                return e;
            }
        }
        throw new AssertionError("Step not visited: " + stepId);
    }

    private static String patched(String json, String operations) {
        return TestSupport.text(TestSupport.patch(StrictJson.parse(json), StrictJson.parse(operations)));
    }

    // ------------------------------------------------------------------ entry checks

    @Test
    void invalidSampleRequestsAreRefusedWithBusinessMessages() {
        SimulationResult r = simulator().simulate(TestSupport.purchaseJson(),
                "{\"title\":\"\",\"purpose\":\"p\",\"costCenter\":\"XX\",\"amount\":0.001}", null,
                SimulatorFixtures.context());
        assertThat(r.status()).isEqualTo(SimulationResult.Status.INVALID_INPUT);
        assertThat(r.hasTrace()).isFalse();
        assertThat(r.inputErrors()).extracting(FormValidator.FieldError::pointer)
                .contains("/title", "/costCenter", "/amount");
    }

    @Test
    void aWorkflowWithBlockersCannotBeRun() {
        String broken = patched(TestSupport.purchaseJson(), "[{\"op\":\"remove\",\"path\":\"/transitions/8\"}]");
        SimulationResult r = simulator().simulate(broken, INPUT_1800, null, SimulatorFixtures.context());
        assertThat(r.status()).isEqualTo(SimulationResult.Status.CANNOT_RUN);
        assertThat(r.blockers()).isNotEmpty();
        assertThat(simulator().simulate("{", "{}", null, SimulatorFixtures.context()).status())
                .isEqualTo(SimulationResult.Status.CANNOT_RUN);
    }

    @Test
    void aMalformedScenarioIsRejected() {
        assertThatThrownBy(() -> simulator().simulate(TestSupport.purchaseJson(), INPUT_1800, "[1]",
                SimulatorFixtures.context())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchase("{\"approvals\":{\"manager\":{\"outcome\":\"maybe\"}}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("approve, reject or timeout");
        assertThatThrownBy(() -> purchase("{\"calls\":{\"procure\":{\"failure\":\"boom\"}}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failure");
    }

    // ------------------------------------------------------------------ approval

    @Test
    void approvalsFollowTheScenarioAndDefaultToApproved() {
        assertThat(entry(purchase(null), "manager").at("/details/decision").asString()).isEqualTo("approved");
        JsonNode rejected = purchase("{\"approvals\":{\"manager\":{\"outcome\":\"reject\"}}}");
        assertThat(entry(rejected, "manager").at("/details/reason").asString()).isEqualTo("Sample rejection reason");
        assertThat(entry(rejected, "manager").at("/details/groupName").asString()).isEqualTo("Managers");
        assertThat(rejected.at("/outcome/businessOutcome").asString()).isEqualTo("Rejected");
        assertThat(rejected.at("/outcome/outcomeKind").asString()).isEqualTo("rejected");
    }

    @Test
    void aTimeoutWithoutATimeLimitIsExplainedAndTreatedAsApproved() {
        JsonNode r = purchase("{\"approvals\":{\"manager\":{\"outcome\":\"timeout\"}}}");
        assertThat(entry(r, "manager").at("/details/note").asString()).contains("no time limit");
        assertThat(entry(r, "manager").at("/details/decision").asString()).isEqualTo("approved");
    }

    @Test
    void anApprovalWithAnUnknownGroupIsAWarningNotAStop() {
        SimulationContext noGroups = new SimulationContext(new CompileContext(false, Map.of(), List.of(),
                java.util.Set.of()), null, null);
        SimulationResult r = simulator().simulate(TestSupport.purchaseJson(), INPUT_1800, null, noGroups);
        assertThat(r.hasTrace()).isTrue();
        JsonNode json = StrictJson.parse(r.json());
        assertThat(json.get("warnings").valueStream().map(w -> w.get("code").stringValue()).toList())
                .contains("WF_GROUP_UNBOUND");
        assertThat(entry(json, "manager").at("/details/groupName").asString()).isEqualTo("managers");
    }

    // ------------------------------------------------------------------ decisions: parity with the evaluator

    @ParameterizedTest
    @CsvSource({"4999.99,standard", "5000,financeReview", "5000.00,financeReview", "5000.01,financeReview",
        "0.01,standard", "1000000,financeReview"})
    void decisionRowsAgreeWithTheDecisionEvaluatorAtTheBoundaries(String amount, String route) {
        String input = "{\"title\":\"T\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":" + amount + "}";
        JsonNode r = SimulatorFixtures.run(TestSupport.purchaseJson(), input, null);
        JsonNode decision = entry(r, "amountPolicy");
        DecisionEvaluator.Result expected = Compilers.decisionEvaluator().evaluate(TestSupport.purchaseJson(),
                "amount-policy", Map.of("/input/amount", new BigDecimal(amount)));
        assertThat(decision.at("/details/outputs/route").asString()).isEqualTo(route)
                .isEqualTo(expected.output().get("route"));
        assertThat(decision.at("/details/usedDefault").asBoolean()).isEqualTo(expected.usedDefault());
        if (expected.matchedRow() == null) {
            assertThat(decision.at("/details/matchedRow").isNull()).isTrue();
        } else {
            assertThat(decision.at("/details/matchedRow").asInt()).isEqualTo(expected.matchedRow() + 1);
        }
        assertThat(r.at("/trace").valueStream().anyMatch(e -> "finance".equals(e.get("stepId").stringValue())))
                .isEqualTo("financeReview".equals(route));
        // Both rows of the table are shown with the values used.
        assertThat(decision.at("/details/inputs/0/shown").asString()).startsWith("USD ");
    }

    @Test
    void aDecisionThatNeedsAMissingValueStopsForAttention() {
        // The only input the table reads is made optional by removing it from "required" and from the request.
        String optional = patched(TestSupport.purchaseJson(), "[{\"op\":\"replace\",\"path\":\"/inputSchema/required\","
                + "\"value\":[\"title\",\"purpose\",\"costCenter\"]},{\"op\":\"replace\",\"path\":\"/outputMapping\","
                + "\"value\":{}}]");
        SimulationResult r = simulator().simulate(optional, "{\"title\":\"T\",\"purpose\":\"p\",\"costCenter\":\"IT\"}",
                null, SimulatorFixtures.context());
        assertThat(r.status()).isEqualTo(SimulationResult.Status.NEEDS_ATTENTION);
        JsonNode json = StrictJson.parse(r.json());
        assertThat(json.at("/outcome/attention/stepId").asString()).isEqualTo("amountPolicy");
        assertThat(json.at("/outcome/attention/message").asString()).contains("Amount").contains("no usable value");
    }

    // ------------------------------------------------------------------ branch

    @Test
    void compoundBranchConditionsShowEveryTermAndShortCircuit() {
        String compound = patched(TestSupport.purchaseJson(), "[{\"op\":\"replace\",\"path\":\"/transitions/4/condition\","
                + "\"value\":{\"any\":[{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"HR\"},"
                + "{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":5000}]}}]");
        JsonNode r = SimulatorFixtures.run(compound,
                "{\"title\":\"T\",\"purpose\":\"p\",\"costCenter\":\"HR\",\"amount\":10}", null);
        JsonNode route = entry(r, "route");
        assertThat(route.at("/details/routes/0/matched").asBoolean()).isTrue();
        assertThat(route.at("/details/routes/0/terms/0/matched").asBoolean()).isTrue();
        assertThat(route.at("/details/routes/0/terms/1/matched").isNull()).as("not needed after the first match").isTrue();
        assertThat(route.at("/details/chosen").asString()).isEqualTo("t5");
        assertThat(route.get("summary").asString()).contains("at least one of these is true");
        JsonNode other = SimulatorFixtures.run(compound,
                "{\"title\":\"T\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":10}", null);
        assertThat(entry(other, "route").at("/details/chosen").asString()).isEqualTo("t6");
    }

    // ------------------------------------------------------------------ call a system

    @ParameterizedTest
    @CsvSource({"rejected,EFFECT_REJECTED", "unavailable,RETRIES_EXHAUSTED", "unknownOutcome,EFFECT_UNKNOWN"})
    void callFailuresStopTheRequestWithTheEngineAttentionReason(String failure, String reason) {
        JsonNode r = purchase("{\"calls\":{\"procure\":{\"failure\":\"" + failure + "\"}}}");
        assertThat(r.at("/outcome/status").asString()).isEqualTo("needsAttention");
        assertThat(r.at("/outcome/attention/stepId").asString()).isEqualTo("procure");
        assertThat(r.at("/outcome/attention/reason").asString()).isEqualTo(reason);
        assertThat(r.get("trace").valueStream().map(e -> e.get("stepId").stringValue()).toList())
                .doesNotContain("notify", "fulfilled");
        assertThat(r.get("outputs").size()).isZero();
        assertThat(r.at("/coverage/steps/visited").asInt()).isLessThan(r.at("/coverage/steps/total").asInt());
    }

    @Test
    void callResultsComeFromTheScenarioOrFromTheResponseSchema() {
        JsonNode sample = purchase(null);
        assertThat(entry(sample, "procure").at("/details/sampleAnswer").asBoolean()).isTrue();
        assertThat(sample.at("/outputs/procurementId").asString()).isEqualTo("sample-id");
        JsonNode own = purchase("{\"calls\":{\"procure\":{\"result\":{\"id\":\"PO-9\"}}}}");
        assertThat(own.at("/outputs/procurementId").asString()).isEqualTo("PO-9");
        assertThat(entry(own, "procure").at("/details/request/amount").asInt()).isEqualTo(1800);
        JsonNode missing = purchase("{\"calls\":{\"procure\":{\"result\":{\"other\":1}}}}");
        assertThat(missing.at("/outcome/status").asString()).isEqualTo("needsAttention");
        assertThat(missing.at("/outcome/attention/reason").asString()).isEqualTo("EFFECT_UNKNOWN");
    }

    // ------------------------------------------------------------------ notification, wait, parallel, end

    @Test
    void notificationsShowTheMessageThatWouldBeSent() {
        JsonNode n = entry(purchase(null), "notify");
        assertThat(n.at("/details/message").asString()).startsWith("Your purchase request was approved");
        assertThat(n.at("/details/recipient").asString()).isEqualTo("initiator");
    }

    @Test
    void waitsAreSkippedWithANote() {
        JsonNode r = SimulatorFixtures.run(TestSupport.onboardingJson(),
                "{\"employeeName\":\"S\",\"role\":\"R\",\"department\":\"IT\",\"equipment\":\"L\","
                        + "\"equipmentBudget\":100,\"justification\":\"j\"}", null);
        assertThat(entry(r, "leadTime").get("summary").asString()).contains("would wait 1 minute")
                .contains("skips the wait");
    }

    @Test
    void parallelBranchesRunInOrderAndRejectionEndsEverything() {
        String input = "{\"employeeName\":\"S\",\"role\":\"R\",\"department\":\"IT\",\"equipment\":\"L\","
                + "\"equipmentBudget\":100,\"justification\":\"j\"}";
        JsonNode both = SimulatorFixtures.run(TestSupport.onboardingJson(), input, null);
        List<String> ids = both.get("trace").valueStream().map(e -> e.get("stepId").stringValue()).toList();
        assertThat(ids.indexOf("it")).isLessThan(ids.indexOf("facilities"));
        assertThat(ids.indexOf("facilities")).isLessThan(ids.indexOf("setupDone"));
        assertThat(entry(both, "it").get("branch").asString()).isEqualTo("1");
        assertThat(entry(both, "facilities").get("branch").asString()).isEqualTo("2");
        // IT rejects: the first branch ends the request; the Facilities branch is not tested.
        JsonNode itRejects = SimulatorFixtures.run(TestSupport.onboardingJson(), input,
                "{\"approvals\":{\"it\":{\"outcome\":\"reject\"}}}");
        assertThat(itRejects.at("/outcome/businessOutcome").asString()).isEqualTo("Rejected");
        assertThat(itRejects.get("trace").valueStream().map(e -> e.get("stepId").stringValue()).toList())
                .doesNotContain("facilities", "setupDone");
        assertThat(entry(itRejects, "setup").at("/details/remainingBranchesStopped").asBoolean()).isTrue();
        assertThat(itRejects.at("/coverage/steps/notVisited").valueStream().map(e -> e.get("id").stringValue()).toList())
                .contains("facilities");
    }

    @Test
    void aMessageWaitArrivesWithASamplePayloadOrFiresItsTimeLimit() {
        String order = TestSupport.read("examples/order-payment-wait.json");
        String input = "{\"orderNumber\":\"ORD-1\",\"customerName\":\"S\"}";
        JsonNode arrives = SimulatorFixtures.run(order, input, null);
        assertThat(entry(arrives, "waitPayment").at("/details/payload/paymentReference").asString())
                .isEqualTo("sample-paymentReference");
        assertThat(arrives.at("/outcome/businessOutcome").asString()).isEqualTo("Order paid");
        JsonNode late = SimulatorFixtures.run(order, input, "{\"messages\":{\"waitPayment\":\"timeout\"}}");
        assertThat(late.at("/outcome/outcomeKind").asString()).isEqualTo("rejected");
        assertThat(entry(late, "waitPayment").get("summary").asString()).contains("does not arrive within 2 days");
        assertThatThrownBy(() -> SimulatorFixtures.run(order, input, "{\"messages\":{\"waitPayment\":\"never\"}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ child workflows and iteration

    @Test
    void repeatStopsOnTheDesiredOutcomeOrAtTheBound() {
        String medium = TestSupport.read("examples/medium-bounded-rework.json");
        String input = "{\"title\":\"T\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":10}";
        JsonNode accepted = SimulatorFixtures.run(medium, input,
                "{\"children\":{\"child\":{\"outcomes\":[\"Rejected\",\"Accepted\",\"Rejected\"]}}}");
        assertThat(accepted.at("/outputs/iterations").asInt()).isEqualTo(2);
        assertThat(accepted.at("/outputs/limitReached").asBoolean()).isFalse();
        assertThat(accepted.at("/outcome/businessOutcome").asString()).isEqualTo("Accepted");
        JsonNode exhausted = SimulatorFixtures.run(medium, input,
                "{\"children\":{\"child\":{\"outcomes\":[\"Rejected\",\"Rejected\",\"Rejected\",\"Rejected\"]}}}");
        assertThat(exhausted.at("/outputs/iterations").asInt()).as("never more than the bound").isEqualTo(3);
        assertThat(exhausted.at("/outputs/limitReached").asBoolean()).isTrue();
        assertThat(exhausted.at("/outcome/businessOutcome").asString()).isEqualTo("Rejected");
        // Without a scenario the child ends the way the repeat wants.
        assertThat(SimulatorFixtures.run(medium, input, null).at("/outputs/iterations").asInt()).isEqualTo(1);
    }

    @Test
    void aChildCanBeSimulatedItselfAndItsAttentionHoldsTheParent() {
        String input = "{\"employeeName\":\"S\",\"role\":\"R\",\"department\":\"IT\",\"equipment\":\"L\","
                + "\"equipmentBudget\":6000,\"justification\":\"j\"}";
        JsonNode r = SimulatorFixtures.run(TestSupport.onboardingJson(), input, "{\"simulateChildren\":true,"
                + "\"childScenarios\":{\"orderEquipment\":{\"calls\":{\"procure\":{\"failure\":\"rejected\"}}}}}");
        assertThat(r.at("/outcome/status").asString()).isEqualTo("needsAttention");
        assertThat(r.at("/outcome/attention/reason").asString()).isEqualTo("CHILD_NEEDS_ATTENTION");
        JsonNode child = entry(r, "orderEquipment");
        assertThat(child.at("/details/simulated").asBoolean()).isTrue();
        assertThat(child.at("/details/occurrences/0/trace").size()).isGreaterThan(3);
    }

    @Test
    void forEachRunsOneChildPerItemInOrder() {
        String batch = TestSupport.read("examples/complex-batch-review.json");
        String input = "{\"title\":\"B\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":10,"
                + "\"items\":[\"a\",\"b\",\"c\"]}";
        JsonNode r = SimulatorFixtures.run(batch, input,
                "{\"children\":{\"child\":{\"outcomes\":[\"Rejected\",\"Accepted\"]}}}");
        assertThat(entry(r, "child").at("/details/iterations").asInt()).isEqualTo(3);
        assertThat(r.at("/outputs/outcomeItems").valueStream().map(JsonNode::asString).toList())
                .containsExactly("Rejected", "Accepted", "Accepted");
        assertThat(entry(r, "child").at("/details/allSucceeded").asBoolean()).isFalse();
    }

    @Test
    void nestedRepeatsShareOneChildRunBudget() {
        String medium = TestSupport.read("examples/medium-bounded-rework.json");
        String root = patched(medium, "[{\"op\":\"replace\",\"path\":\"/workflowKey\",\"value\":\"root-review\"},{\"op\":\"replace\",\"path\":\"/nodes/1/config/workflowRef\",\"value\":\"mid-review\"}]");
        String mid = patched(medium, "[{\"op\":\"replace\",\"path\":\"/workflowKey\",\"value\":\"mid-review\"},{\"op\":\"replace\",\"path\":\"/nodes/1/config/repeat/maxIterations\",\"value\":25}]");
        SimulationContext base = SimulatorFixtures.context();
        CompileContext c = base.workspace();
        Map<String, CompileContext.WorkflowInfo> workflows = new java.util.LinkedHashMap<>(c.workflows());
        workflows.put("mid-review", CompileContext.WorkflowInfo.of("mid-review", "Mid review",
                "00000000-0000-7000-8000-0000000000a2", 1, "hash", mid, java.util.Set.of("review-request"), 1));
        SimulationContext context = new SimulationContext(new CompileContext(c.selfApprovalAllowed(), c.groups(),
                c.connectionVersions(), c.enabledConnectionBindings(), workflows),
                key -> "mid-review".equals(key) ? mid : TestSupport.read("examples/simple-review.json"), base.today());
        SimulationResult r = simulator().simulate(root, INPUT_1800,
                "{\"simulateChildren\":true,\"childScenarios\":{\"child\":{\"simulateChildren\":true,\"childScenarios\":{\"child\":{\"approvals\":{\"review\":{\"outcome\":\"reject\"}}}}}}}", context);
        assertThat(r.status()).isEqualTo(SimulationResult.Status.NEEDS_ATTENTION);
        assertThat(r.json()).contains("25 child-run budget");
    }

    @Test
    void scenarioCollectionsAndBytesAreBoundedBeforeEvaluation() {
        assertThatThrownBy(() -> purchase("{\"note\":\"" + "x".repeat(65536) + "\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchase("{\"approvals\":[]}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchase("{\"children\":{\"child\":{\"outcomes\":[" +
                java.util.stream.IntStream.range(0,26).mapToObj(i -> "\"Rejected\"")
                        .collect(java.util.stream.Collectors.joining(",")) + "]}}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ warnings and coverage

    @Test
    void rulesThatCanNeverApplyAreReported() {
        String never = patched(TestSupport.purchaseJson(), "[{\"op\":\"replace\",\"path\":\"/decisions/0/rows/0/when/value\","
                + "\"value\":2000000}]");
        JsonNode r = SimulatorFixtures.run(never, INPUT_1800, null);
        JsonNode warning = r.get("warnings").valueStream().filter(w -> "RULE_NEVER_MATCHES".equals(
                w.get("code").stringValue())).findFirst().orElseThrow();
        assertThat(warning.get("stepId").asString()).isEqualTo("amountPolicy");
        assertThat(warning.get("message").asString()).contains("USD 2,000,000").contains("at most USD 1,000,000");
    }

    @Test
    void unknownStepsInTheScenarioAreReportedNotFailed() {
        JsonNode r = purchase("{\"approvals\":{\"nope\":{\"outcome\":\"reject\"}},\"calls\":{\"manager\":{}}}");
        long count = r.get("warnings").valueStream().filter(w -> "SCENARIO_UNKNOWN_STEP".equals(
                w.get("code").stringValue())).count();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void coverageCountsStepsRoutesAndRules() {
        JsonNode r = purchase(null);
        assertThat(r.at("/coverage/routes/total").asInt()).isEqualTo(6);
        assertThat(r.at("/coverage/routes/taken").asInt()).isEqualTo(2);
        assertThat(r.at("/coverage/rules/total").asInt()).isEqualTo(2);
        assertThat(r.at("/coverage/rules/matched").asInt()).isEqualTo(1);
        assertThat(r.at("/coverage/rules/notMatched/0/row").asString()).isEqualTo("Rule 1");
        assertThat(r.at("/coverage/summary").asString()).isEqualTo(
                "Tested 7 of 10 steps, 2 of 6 routes and 1 of 2 decision rules.");
        assertThat(r.get("limits").asString()).contains("not the workflow engine");
    }

    @Test
    void theSimulatorNeverMutatesItsInputsAndIsRepeatable() {
        String definition = TestSupport.purchaseJson();
        String before = definition;
        JsonNode a = SimulatorFixtures.run(definition, INPUT_1800, null);
        JsonNode b = SimulatorFixtures.run(definition, INPUT_1800, null);
        assertThat(a).isEqualTo(b);
        assertThat(definition).isEqualTo(before);
    }
}
