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
}

