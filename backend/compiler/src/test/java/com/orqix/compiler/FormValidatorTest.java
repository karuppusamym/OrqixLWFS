package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.orqix.compiler.FormValidator.FieldError;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/** Request input validation (S5 section 3) against purchase-scenarios.json inputValidation and edge cases. */
class FormValidatorTest {

    private static final FormValidator VALIDATOR = Compilers.formValidator();

    private static String purchaseInputSchema() {
        return StrictJson.write(TestSupport.purchase().get("inputSchema"));
    }

    @TestFactory
    Stream<DynamicTest> inputValidationFixtures() {
        JsonNode fixtures = TestSupport.json("examples/fixtures/purchase-scenarios.json").get("inputValidation");
        String schema = purchaseInputSchema();
        return fixtures.valueStream().map(f -> DynamicTest.dynamicTest(f.get("name").stringValue(), () -> {
            List<FieldError> errors = VALIDATOR.validate(schema, StrictJson.write(f.get("input")));
            if (f.get("valid").booleanValue()) {
                assertThat(errors).isEmpty();
            } else {
                List<String> expected = f.get("fieldErrors").valueStream().map(JsonNode::stringValue).toList();
                assertThat(errors).extracting(FieldError::pointer).containsExactlyInAnyOrderElementsOf(expected);
                errors.forEach(e -> assertThat(e.message()).isNotBlank());
            }
        }));
    }

    @Test
    void reportsTheFailedKeyword() {
        String schema = purchaseInputSchema();
        assertThat(VALIDATOR.validate(schema, input("1800.071"))).containsExactly(
                new FieldError("/amount", "multipleOf", "Use at most two decimal places."));
        assertThat(VALIDATOR.validate(schema, input("0"))).extracting(FieldError::code).containsExactly("minimum");
        assertThat(VALIDATOR.validate(schema, input("1000000.01"))).extracting(FieldError::code)
                .containsExactly("maximum");
        assertThat(VALIDATOR.validate(schema, input("\"1800\""))).containsExactly(
                new FieldError("/amount", "type", "Enter a number."));
        assertThat(VALIDATOR.validate(schema, input("null"))).extracting(FieldError::code).containsExactly("type");
        assertThat(VALIDATOR.validate(schema, input("1800.10"))).isEmpty();
        assertThat(VALIDATOR.validate(schema, input("5000.00"))).isEmpty();
        assertThat(VALIDATOR.validate(schema, input("1E3"))).isEmpty();
        assertThat(VALIDATOR.validate(schema, input("1000000"))).isEmpty();
        assertThat(VALIDATOR.validate(schema, input("0.01"))).isEmpty();
    }

    @Test
    void stringKeywordsCountCodePoints() {
        String schema = """
                {"type":"object","additionalProperties":false,"required":["name"],"properties":{
                  "name":{"type":"string","title":"Name","minLength":2,"maxLength":3},
                  "kind":{"type":"string","title":"Kind","enum":["a","b"]}}}
                """;
        assertThat(VALIDATOR.validate(schema, "{\"name\":\"😀😀\"}")).isEmpty();
        assertThat(VALIDATOR.validate(schema, "{\"name\":\"x\"}")).containsExactly(
                new FieldError("/name", "minLength", "Enter at least 2 characters."));
        assertThat(VALIDATOR.validate(schema, "{\"name\":\"xxxx\",\"kind\":\"c\"}"))
                .extracting(FieldError::pointer, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("/kind", "enum"),
                        org.assertj.core.groups.Tuple.tuple("/name", "maxLength"));
        assertThat(VALIDATOR.validate(schema, "{\"name\":\"ok\",\"a/b\":1,\"t~x\":2}"))
                .extracting(FieldError::pointer).containsExactly("/a~1b", "/t~0x");
        assertThat(VALIDATOR.validate(schema, "{}")).containsExactly(
                new FieldError("/name", "required", "This field is required."));
        assertThat(VALIDATOR.validate(schema, "[]")).extracting(FieldError::code).containsExactly("type");
        String single = schema.replace("\"minLength\":2", "\"minLength\":1");
        assertThat(VALIDATOR.validate(single, "{\"name\":\"\"}")).extracting(FieldError::message)
                .containsExactly("This field cannot be empty.");
    }

    @Test
    void integersBooleansAndOpenForms() {
        String schema = """
                {"type":"object","additionalProperties":false,"properties":{
                  "count":{"type":"integer","title":"Count","minimum":1,"multipleOf":1},
                  "step":{"type":"number","title":"Step","multipleOf":0.5},
                  "ok":{"type":"boolean","title":"OK"}}}
                """;
        assertThat(VALIDATOR.validate(schema, "{\"count\":3,\"ok\":true,\"step\":1.5}")).isEmpty();
        assertThat(VALIDATOR.validate(schema, "{\"count\":3.0}")).isEmpty();
        assertThat(VALIDATOR.validate(schema, "{\"count\":3.5}")).containsExactly(
                new FieldError("/count", "type", "Enter a whole number."));
        assertThat(VALIDATOR.validate(schema, "{\"ok\":\"true\"}")).containsExactly(
                new FieldError("/ok", "type", "Choose true or false."));
        assertThat(VALIDATOR.validate(schema, "{\"step\":0.7}")).containsExactly(
                new FieldError("/step", "multipleOf", "Enter a multiple of 0.5."));
        assertThat(VALIDATOR.validate(schema, "{\"title\":5}")).extracting(FieldError::code)
                .containsExactly("additionalProperties");
        String open = schema.replace("\"additionalProperties\":false", "\"additionalProperties\":true");
        assertThat(VALIDATOR.validate(open, "{\"extra\":1}")).isEmpty();
        String whole = "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"number\",\"multipleOf\":1}}}";
        assertThat(VALIDATOR.validate(whole, "{\"n\":1.5}")).extracting(FieldError::message)
                .containsExactly("Enter a whole number.");
    }

    @Test
    void rejectsUnusableDocuments() {
        assertThatThrownBy(() -> VALIDATOR.validate("[]", "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VALIDATOR.validate(purchaseInputSchema(), "{\"a\":1,\"a\":2}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VALIDATOR.validate(
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"array\"}}}", "{\"x\":[]}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String input(String amount) {
        return "{\"title\":\"Desk\",\"purpose\":\"New hire\",\"costCenter\":\"HR\",\"amount\":" + amount + "}";
    }
}
