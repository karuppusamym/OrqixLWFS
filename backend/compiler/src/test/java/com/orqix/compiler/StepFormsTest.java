package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Step forms: field access per human step and fields captured at the step (docs/build/S123). */
class StepFormsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String CAPTURE = "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"poNumber\"],"
            + "\"properties\":{\"poNumber\":{\"type\":\"string\",\"title\":\"PO number\",\"minLength\":3,\"maxLength\":20},"
            + "\"approvedAmount\":{\"type\":\"number\",\"title\":\"Approved amount\",\"minimum\":0}}}";
    private static final String INPUT = "{\"title\":\"Laptop\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":1800}";

    private static JsonNode n(String json) {
        return MAPPER.readTree(json);
    }

    private static ObjectNode node(ObjectNode d, String id) {
        for (JsonNode x : d.path("nodes")) {
            if (id.equals(x.path("id").asString())) {
                return (ObjectNode) x;
            }
        }
        throw new AssertionError(id);
    }

    private static ObjectNode config(ObjectNode d, String id) {
        return (ObjectNode) node(d, id).path("config");
    }

    private static ObjectNode transition(ObjectNode d, String id) {
        for (JsonNode t : d.path("transitions")) {
            if (id.equals(t.path("id").asString())) {
                return (ObjectNode) t;
            }
        }
        throw new AssertionError(id);
    }

    /** The purchase template where the manager fills in a PO number (required) and an approved amount (optional). */
    private static ObjectNode withCapture() {
        ObjectNode d = TestSupport.purchase();
        config(d, "manager").set("capture", n(CAPTURE));
        return d;
    }

    private static List<String> blockers(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues()
                .stream().filter(i -> i.severity() == Severity.BLOCKER).map(i -> i.code() + " " + i.path()).toList();
    }

    private static List<String> messages(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues()
                .stream().map(ValidationIssue::message).toList();
    }

    @Test
    void aCapturedFieldCanBeUsedAfterTheApprovedRoute() throws Exception {
        ObjectNode d = withCapture();
        config(d, "manager").set("fieldAccess", n("{\"purpose\":\"hidden\",\"costCenter\":\"readOnly\",\"title\":\"required\"}"));
        // The route after the manager's approval checks the PO number the manager typed.
        transition(d, "t5").set("condition", n("{\"all\":["
                + "{\"fieldRef\":\"/steps/amountPolicy/route\",\"operator\":\"eq\",\"value\":\"financeReview\"},"
                + "{\"fieldRef\":\"/steps/manager/poNumber\",\"operator\":\"ne\",\"value\":\"NONE\"}]}"));
        assertThat(blockers(d)).isEmpty();
        // The compiled process is the same kind of user task: the values travel in the step output.
        assertThat(Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace()).bpmnXml())
                .contains("id=\"manager\"");
    }

    @Test
    void aCapturedFieldIsNotAvailableWhereTheStepCanBeRejected() {
        ObjectNode d = withCapture();
        // A branch on the rejected route reads the PO number, which a rejection never fills in.
        ((ArrayNode) d.path("nodes")).add(n("{\"id\":\"checkPo\",\"type\":\"branch\",\"label\":\"Check the PO\",\"config\":{}}"));
        transition(d, "t3").put("to", "checkPo");
        ((ArrayNode) d.path("transitions")).add(n("{\"id\":\"t12\",\"from\":\"checkPo\",\"to\":\"notifyRejected\",\"on\":\"condition\","
                + "\"label\":\"Has a PO\",\"condition\":{\"fieldRef\":\"/steps/manager/poNumber\",\"operator\":\"eq\",\"value\":\"PO-1\"}}"));
        ((ArrayNode) d.path("transitions")).add(n("{\"id\":\"t13\",\"from\":\"checkPo\",\"to\":\"notifyRejected\",\"on\":\"default\",\"label\":\"Other\"}"));
        d.remove("uiLayout");
        assertThat(blockers(d)).anyMatch(b -> b.startsWith("WF_FIELDREF_NOT_GUARANTEED"));
        assertThat(messages(d)).anyMatch(m -> m.contains("filled in only when the step is approved"));
    }

    @Test
    void anUnknownOutputListsTheCapturedFields() {
        ObjectNode d = withCapture();
        transition(d, "t5").set("condition", n("{\"fieldRef\":\"/steps/manager/nothing\",\"operator\":\"eq\",\"value\":\"x\"}"));
        assertThat(blockers(d)).anyMatch(b -> b.startsWith("WF_FIELDREF_UNKNOWN"));
        assertThat(messages(d)).anyMatch(m -> m.contains("approvedAmount") && m.contains("poNumber"));
    }

    @Test
    void fieldAccessMustNameRequestQuestions() {
        ObjectNode d = TestSupport.purchase();
        config(d, "manager").set("fieldAccess", n("{\"nothing\":\"hidden\"}"));
        assertThat(blockers(d)).contains("WF_STEP_FORM_INVALID /nodes/1/config/fieldAccess/nothing");
    }

    @Test
    void anEditableFieldFollowsTheCorrectionRules() {
        ObjectNode d = TestSupport.purchase();
        // The amount decision reads the amount before finance, so finance cannot change it.
        config(d, "finance").set("fieldAccess", n("{\"amount\":\"editable\"}"));
        assertThat(blockers(d)).contains("WF_CORRECTION_INVALID /nodes/4/config/fieldAccess");
        ObjectNode ok = TestSupport.purchase();
        config(ok, "manager").set("fieldAccess", n("{\"purpose\":\"required\"}"));
        assertThat(blockers(ok)).isEmpty();
    }

    @Test
    void capturedFieldsAreSingleValuesWithFreeNames() {
        ObjectNode reserved = TestSupport.purchase();
        config(reserved, "manager").set("capture", n("{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"decision\":{\"type\":\"string\",\"title\":\"Decision\"}}}"));
        assertThat(blockers(reserved)).contains("WF_STEP_FORM_INVALID /nodes/1/config/capture/properties/decision");

        ObjectNode list = TestSupport.purchase();
        config(list, "manager").set("capture", n("{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"tags\":{\"type\":\"array\",\"title\":\"Tags\",\"items\":{\"type\":\"string\",\"title\":\"Tag\"}}}}"));
        assertThat(blockers(list)).anyMatch(b -> b.startsWith("WF_STEP_FORM_INVALID") || b.startsWith("WF_SCHEMA"));

        ObjectNode missingRequired = TestSupport.purchase();
        config(missingRequired, "manager").set("capture", n("{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"required\":[\"other\"],\"properties\":{\"poNumber\":{\"type\":\"string\",\"title\":\"PO number\"}}}"));
        assertThat(blockers(missingRequired)).contains("WF_STEP_FORM_INVALID /nodes/1/config/capture/required");
    }

    @Test
    void severalApproversCannotFillInFields() {
        ObjectNode d = withCapture();
        config(d, "manager").set("multiInstance", n("{\"approvers\":2}"));
        assertThat(blockers(d)).anyMatch(b -> b.startsWith("WF_MULTI_UNSUPPORTED"));
    }

    @Test
    void anUnknownAccessValueIsASchemaError() {
        ObjectNode d = TestSupport.purchase();
        config(d, "manager").set("fieldAccess", n("{\"amount\":\"secret\"}"));
        assertThat(blockers(d)).isNotEmpty().noneMatch(b -> b.startsWith("WF_STEP_FORM_INVALID"));
    }

    @Test
    void theTestRunFillsInTheCapturedFields() {
        ObjectNode d = withCapture();
        JsonNode sampled = SimulatorFixtures.run(d.toString(), INPUT, null);
        JsonNode manager = entry(sampled, "manager");
        assertThat(manager.at("/wrote/poNumber").asString()).isEqualTo("Sample po number");
        assertThat(manager.has("wrote") && manager.get("wrote").has("approvedAmount")).isFalse();
        assertThat(manager.path("summary").asString()).contains("They fill in PO number");

        JsonNode given = SimulatorFixtures.run(d.toString(), INPUT,
                "{\"approvals\":{\"manager\":{\"fields\":{\"poNumber\":\"PO-77\",\"approvedAmount\":1500}}}}");
        assertThat(entry(given, "manager").at("/wrote/poNumber").asString()).isEqualTo("PO-77");
        assertThat(entry(given, "manager").at("/wrote/approvedAmount").asInt()).isEqualTo(1500);

        JsonNode rejected = SimulatorFixtures.run(d.toString(), INPUT, "{\"approvals\":{\"manager\":{\"outcome\":\"reject\"}}}");
        assertThat(entry(rejected, "manager").at("/wrote/poNumber").isMissingNode()).isTrue();
    }

    private static JsonNode entry(JsonNode result, String stepId) {
        for (JsonNode e : result.get("trace")) {
            if (stepId.equals(e.get("stepId").stringValue())) {
                return e;
            }
        }
        throw new AssertionError("Step not visited: " + stepId);
    }
}
