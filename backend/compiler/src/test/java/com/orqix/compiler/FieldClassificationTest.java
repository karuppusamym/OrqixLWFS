package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** CG-I05 (docs/build/S151): classification of request questions, its rules and masking. */
class FieldClassificationTest {

    private static ValidationReport report(ObjectNode workflow) {
        return Compilers.workflowCompiler().validate(TestSupport.text(workflow), TestSupport.defaultContext());
    }

    private static String withoutHash(String bpmn) {
        return bpmn.replaceAll("semanticHash=\"[0-9a-f]{64}\"", "semanticHash=\"\"");
    }

    private static ObjectNode field(ObjectNode workflow, String key) {
        return (ObjectNode) workflow.path("inputSchema").path("properties").path(key);
    }

    @Test
    void requestQuestionsCanBeClassifiedWithoutChangingTheBpmn() throws Exception {
        ObjectNode workflow = TestSupport.purchase();
        String before = withoutHash(Compilers.workflowCompiler().compile(TestSupport.text(workflow),
                TestSupport.defaultContext()).bpmnXml());
        field(workflow, "title").put("classification", "public");
        field(workflow, "costCenter").put("classification", "internal");
        field(workflow, "purpose").put("classification", "personal");
        field(workflow, "amount").put("classification", "sensitive");
        ValidationReport report = report(workflow);
        assertThat(report.issues()).isEmpty();
        assertThat(report.publishable()).isTrue();
        assertThat(withoutHash(Compilers.workflowCompiler().compile(TestSupport.text(workflow),
                TestSupport.defaultContext()).bpmnXml())).isEqualTo(before);
    }

    @Test
    void protectedQuestionsCannotBeSearchableOrFilesAndOnlyRequestQuestionsAreClassified() {
        ObjectNode workflow = TestSupport.purchase();
        field(workflow, "costCenter").put("classification", "personal").put("searchable", true);
        ObjectNode properties = (ObjectNode) workflow.path("inputSchema").path("properties");
        properties.set("receipt", StrictJson.parse("{\"type\":\"string\",\"title\":\"Receipt\",\"format\":\"attachment\","
                + "\"classification\":\"sensitive\"}"));
        properties.set("lines", StrictJson.parse("{\"type\":\"array\",\"title\":\"Lines\",\"minItems\":1,"
                + "\"maxItems\":5,\"items\":{\"type\":\"string\",\"title\":\"SKU\",\"maxLength\":20,"
                + "\"classification\":\"personal\"}}"));
        ValidationReport report = report(workflow);
        assertThat(report.issues()).filteredOn(i -> i.code().equals("WF_FIELD_CLASSIFICATION"))
                .extracting(ValidationIssue::path).containsExactlyInAnyOrder(
                        "/inputSchema/properties/costCenter/searchable",
                        "/inputSchema/properties/receipt/classification",
                        "/inputSchema/properties/lines/items/classification");
        assertThat(report.publishable()).isFalse();

        // public and internal answers are not masked, so they stay searchable.
        ObjectNode open = TestSupport.purchase();
        field(open, "costCenter").put("classification", "internal").put("searchable", true);
        assertThat(report(open).issues()).isEmpty();
    }

    @Test
    void maskReplacesOnlyPresentProtectedAnswers() {
        JsonNode schema = StrictJson.parse("{\"type\":\"object\",\"properties\":{"
                + "\"name\":{\"type\":\"string\",\"title\":\"Name\",\"classification\":\"personal\"},"
                + "\"iban\":{\"type\":\"string\",\"title\":\"IBAN\",\"classification\":\"sensitive\"},"
                + "\"note\":{\"type\":\"string\",\"title\":\"Note\",\"classification\":\"internal\"},"
                + "\"city\":{\"type\":\"string\",\"title\":\"City\"}}}");
        JsonNode input = StrictJson.parse("{\"name\":\"Ada\",\"iban\":null,\"note\":\"n\",\"city\":\"Paris\"}");
        JsonNode masked = FieldClassification.mask(input, schema);
        assertThat(masked.path("name").asString()).isEqualTo(FieldClassification.MASK);
        assertThat(masked.path("iban").isNull()).isTrue();
        assertThat(masked.path("note").asString()).isEqualTo("n");
        assertThat(masked.path("city").asString()).isEqualTo("Paris");
        assertThat(input.path("name").asString()).isEqualTo("Ada");
        assertThat(FieldClassification.protectedFields(schema)).containsOnlyKeys("name", "iban");
        assertThat(FieldClassification.sensitiveFields(schema)).containsExactly("iban");
        assertThat(FieldClassification.isMask(masked.path("name"))).isTrue();
    }
}
