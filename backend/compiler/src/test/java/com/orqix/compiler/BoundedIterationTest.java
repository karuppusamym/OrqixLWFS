package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class BoundedIterationTest {
    private final WorkflowCompiler compiler = Compilers.workflowCompiler();

    private ObjectNode childNode(ObjectNode d) {
        for (var n : d.path("nodes")) if (n.path("type").asString().equals("startWorkflow")) return (ObjectNode) n;
        throw new AssertionError("Missing child step");
    }

    @Test
    void repeatPinsTheChildAndRejectsUnknownOutcomesAndUnboundedCounts() {
        ObjectNode d = TestSupport.onboarding();
        ObjectNode config = (ObjectNode) childNode(d).path("config");
        config.set("repeat", com.orqix.compiler.internal.json.StrictJson.parse("{\"maxIterations\":3,\"untilOutcome\":\"Fulfilled\"}"));
        assertThat(compiler.validate(d.toString(), TestSupport.onboardingContext()).publishable()).isTrue();
        ((ObjectNode) config.path("repeat")).put("untilOutcome", "Invented");
        assertThat(compiler.validate(d.toString(), TestSupport.onboardingContext()).issues()).extracting(ValidationIssue::code).contains("WF_REPEAT_OUTCOME_UNKNOWN");
        ((ObjectNode) config.path("repeat")).put("maxIterations", 26);
        assertThat(compiler.validate(d.toString(), TestSupport.onboardingContext()).publishable()).isFalse();
    }

    @Test
    void forEachRequiresTypedBoundedRequiredItemsAndExclusiveMapping() {
        ObjectNode d = TestSupport.onboarding();
        ObjectNode config = (ObjectNode) childNode(d).path("config");
        ((ObjectNode) d.path("inputSchema").path("properties")).set("equipment", com.orqix.compiler.internal.json.StrictJson.parse("{\"type\":\"array\",\"title\":\"Equipment\",\"minItems\":1,\"maxItems\":3,\"items\":{\"type\":\"string\",\"title\":\"Equipment item\"}}"));
        ((ObjectNode) config.path("inputMapping")).remove("title");
        config.set("forEach", com.orqix.compiler.internal.json.StrictJson.parse("{\"itemsRef\":\"/input/equipment\",\"itemInput\":\"title\",\"maxItems\":3}"));
        assertThat(compiler.validate(d.toString(), TestSupport.onboardingContext()).publishable()).isTrue();
        ((ObjectNode) config.path("forEach")).put("maxItems", 2);
        assertThat(compiler.validate(d.toString(), TestSupport.onboardingContext()).issues()).extracting(ValidationIssue::code).contains("WF_FOREACH_BOUND");
    }

    @Test
    void collectionsValidateSizesAndScalarItemsAtTheirOwnPointers() {
        var validator = Compilers.formValidator();
        String schema = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"items\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":\"integer\",\"minimum\":1}}}}";
        assertThat(validator.validate(schema, "{\"items\":[]}")).extracting(FormValidator.FieldError::code).containsExactly("minItems");
        assertThat(validator.validate(schema, "{\"items\":[1,2,3]}")).extracting(FormValidator.FieldError::code).containsExactly("maxItems");
        assertThat(validator.validate(schema, "{\"items\":[1,\"two\"]}")).extracting(FormValidator.FieldError::pointer).containsExactly("/items/1");
        assertThat(validator.validate(schema, "{\"items\":[1,2]}")).isEmpty();
    }

    @Test
    void collectedOutcomesHaveTypedArrayResultsAndRejectIncompatibleItems() {
        ObjectNode d = TestSupport.onboarding();
        ObjectNode config = (ObjectNode) childNode(d).path("config");
        ((ObjectNode) d.path("inputSchema").path("properties")).set("equipment", com.orqix.compiler.internal.json.StrictJson.parse("{\"type\":\"array\",\"title\":\"Equipment\",\"minItems\":1,\"maxItems\":3,\"items\":{\"type\":\"string\",\"title\":\"Item\"}}"));
        ((ObjectNode) config.path("inputMapping")).remove("title");
        config.set("forEach", com.orqix.compiler.internal.json.StrictJson.parse("{\"itemsRef\":\"/input/equipment\",\"itemInput\":\"title\",\"maxItems\":3,\"mode\":\"parallel\"}"));
        config.set("collectionMapping", com.orqix.compiler.internal.json.StrictJson.parse("{\"outcomeItems\":\"outcome\"}"));
        ((ObjectNode) d.path("outputSchema").path("properties")).set("outcomeItems", com.orqix.compiler.internal.json.StrictJson.parse("{\"type\":\"array\",\"title\":\"Outcomes\",\"minItems\":1,\"maxItems\":3,\"items\":{\"type\":\"string\",\"title\":\"Outcome\"}}"));
        ((ObjectNode) d.path("outputMapping")).set("outcomeItems", com.orqix.compiler.internal.json.StrictJson.parse("{\"fieldRef\":\"/steps/orderEquipment/outcomeItems\",\"required\":false}"));
        assertThat(compiler.validate(d.toString(), TestSupport.onboardingContext()).publishable()).isTrue();
        ((ObjectNode) d.path("outputSchema").path("properties").path("outcomeItems").path("items")).put("type", "integer");
        assertThat(compiler.validate(d.toString(), TestSupport.onboardingContext()).issues()).extracting(ValidationIssue::code).contains("WF_OUTPUT_MAPPING_UNKNOWN");
    }

    // ---------------------------------------------------------------- S105: one child per row of a list of rows

    private static final String ROWS = "{\"type\":\"array\",\"title\":\"Equipment lines\",\"minItems\":1,\"maxItems\":3,"
            + "\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"item\",\"budget\"],\"properties\":{"
            + "\"item\":{\"type\":\"string\",\"title\":\"Item\",\"minLength\":1},"
            + "\"budget\":{\"type\":\"number\",\"title\":\"Budget\",\"minimum\":0},"
            + "\"note\":{\"type\":\"string\",\"title\":\"Note\"}}}}";

    /** Onboarding whose equipment child runs once per row: title and amount come from each row. */
    private ObjectNode perRow(String itemMapping) {
        ObjectNode d = TestSupport.onboarding();
        ((ObjectNode) d.path("inputSchema").path("properties")).set("lines",
                com.orqix.compiler.internal.json.StrictJson.parse(ROWS));
        ((tools.jackson.databind.node.ArrayNode) d.path("inputSchema").path("required")).add("lines");
        ObjectNode config = (ObjectNode) childNode(d).path("config");
        ((ObjectNode) config.path("inputMapping")).remove("title");
        ((ObjectNode) config.path("inputMapping")).remove("amount");
        config.set("forEach", com.orqix.compiler.internal.json.StrictJson.parse(
                "{\"itemsRef\":\"/input/lines\",\"itemMapping\":" + itemMapping + ",\"maxItems\":3}"));
        return d;
    }

    private java.util.List<String> codes(ObjectNode d) {
        return compiler.validate(d.toString(), TestSupport.onboardingContext()).issues().stream()
                .filter(i -> i.severity() == Severity.BLOCKER).map(ValidationIssue::code).toList();
    }

    @Test
    void aListOfRowsMapsNamedColumnsIntoChildFields() {
        assertThat(codes(perRow("{\"title\":\"item\",\"amount\":\"budget\"}"))).isEmpty();
        // The column must exist, accept the child's type and always have a value for a required child field.
        assertThat(codes(perRow("{\"title\":\"colour\",\"amount\":\"budget\"}"))).contains("WF_FOREACH_COLUMN");
        assertThat(codes(perRow("{\"title\":\"budget\",\"amount\":\"budget\"}"))).contains("WF_FOREACH_ITEM_TYPE");
        assertThat(codes(perRow("{\"title\":\"note\",\"amount\":\"budget\"}"))).contains("WF_FOREACH_ITEM_TYPE");
        // A child field filled by the row must not also come from inputMapping, and must exist.
        assertThat(codes(perRow("{\"title\":\"item\",\"amount\":\"budget\",\"purpose\":\"item\"}"))).contains("WF_FOREACH_INPUT");
        assertThat(codes(perRow("{\"title\":\"item\",\"amount\":\"budget\",\"nothing\":\"item\"}"))).contains("WF_FOREACH_INPUT");
        // Leaving out a required child field is reported as before.
        assertThat(codes(perRow("{\"title\":\"item\"}"))).contains("WF_CHILD_MAPPING_MISSING_REQUIRED");
    }

    @Test
    void rowsNeedAnItemMappingAndSingleValuesAnItemInputAndNeverBoth() {
        ObjectNode rowsWithInput = perRow("{\"title\":\"item\"}");
        ObjectNode each = (ObjectNode) childNode(rowsWithInput).path("config").path("forEach");
        each.remove("itemMapping");
        each.put("itemInput", "title");
        assertThat(codes(rowsWithInput)).contains("WF_FOREACH_ITEM_TYPE");

        ObjectNode valuesWithMapping = TestSupport.onboarding();
        ((ObjectNode) valuesWithMapping.path("inputSchema").path("properties")).set("equipmentList", com.orqix.compiler.internal.json.StrictJson.parse(
                "{\"type\":\"array\",\"title\":\"Equipment\",\"minItems\":1,\"maxItems\":3,\"items\":{\"type\":\"string\",\"title\":\"Equipment item\"}}"));
        ((tools.jackson.databind.node.ArrayNode) valuesWithMapping.path("inputSchema").path("required")).add("equipmentList");
        ObjectNode config = (ObjectNode) childNode(valuesWithMapping).path("config");
        ((ObjectNode) config.path("inputMapping")).remove("title");
        config.set("forEach", com.orqix.compiler.internal.json.StrictJson.parse(
                "{\"itemsRef\":\"/input/equipmentList\",\"itemMapping\":{\"title\":\"item\"},\"maxItems\":3}"));
        assertThat(codes(valuesWithMapping)).contains("WF_FOREACH_ITEM_TYPE");

        ObjectNode both = perRow("{\"title\":\"item\",\"amount\":\"budget\"}");
        ((ObjectNode) childNode(both).path("config").path("forEach")).put("itemInput", "title");
        assertThat(compiler.validate(both.toString(), TestSupport.onboardingContext()).publishable()).isFalse();
    }

    @Test
    void theSimulatorGivesEachChildItsRowValues() {
        ObjectNode d = perRow("{\"title\":\"item\",\"amount\":\"budget\"}");
        String input = "{\"employeeName\":\"Ada\",\"role\":\"Engineer\",\"department\":\"IT\",\"equipment\":\"Kit\","
                + "\"equipmentBudget\":100,\"justification\":\"Start\",\"lines\":[{\"item\":\"Laptop\",\"budget\":1200},"
                + "{\"item\":\"Phone\",\"budget\":400}]}";
        tools.jackson.databind.JsonNode r = SimulatorFixtures.run(d.toString(), input, "{\"simulateChildren\":true}");
        tools.jackson.databind.JsonNode child = null;
        for (tools.jackson.databind.JsonNode e : r.path("trace")) {
            if ("orderEquipment".equals(e.path("stepId").asString())) {
                child = e;
            }
        }
        assertThat(child).as(r.toString()).isNotNull();
        // Each child was really run with its row (a child without its title could not be tested).
        assertThat(child.at("/details/iterations").asInt()).isEqualTo(2);
        assertThat(child.at("/details/occurrences/0/trace").size()).isGreaterThan(2);
        assertThat(child.at("/details/occurrences/1/trace").size()).isGreaterThan(2);
        assertThat(r.at("/outcome/status").asString()).isNotEqualTo("needsAttention");
    }
}

