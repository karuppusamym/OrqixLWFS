package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.node.ObjectNode;

/** CG-O04 (docs/build/S142): searchable request fields. */
class SearchableFieldRulesTest {

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
    void scalarRequestFieldsCanBeSearchableWithoutChangingTheBpmn() throws Exception {
        ObjectNode workflow = TestSupport.purchase();
        String before = withoutHash(Compilers.workflowCompiler().compile(TestSupport.text(workflow),
                TestSupport.defaultContext()).bpmnXml());
        field(workflow, "costCenter").put("searchable", true);
        field(workflow, "amount").put("searchable", true);
        field(workflow, "title").put("searchable", true);
        ValidationReport report = report(workflow);
        assertThat(report.issues()).isEmpty();
        assertThat(report.publishable()).isTrue();
        // Only the semantic hash of the definition changes; the process itself is the same.
        assertThat(withoutHash(Compilers.workflowCompiler().compile(TestSupport.text(workflow),
                TestSupport.defaultContext()).bpmnXml())).isEqualTo(before);
    }

    @Test
    void longTextIsAWarningAndFilesTimesAndTooManyFieldsAreBlocked() {
        ObjectNode workflow = TestSupport.purchase();
        field(workflow, "purpose").put("searchable", true);
        ValidationReport report = report(workflow);
        assertThat(report.issues()).extracting(ValidationIssue::code).containsExactly("WF_SEARCHABLE_LONG_TEXT");
        assertThat(report.publishable()).isTrue();

        ObjectNode properties = (ObjectNode) workflow.path("inputSchema").path("properties");
        properties.set("receipt", StrictJson.parse("{\"type\":\"string\",\"title\":\"Receipt\",\"format\":\"attachment\","
                + "\"searchable\":true}"));
        properties.set("pickup", StrictJson.parse("{\"type\":\"string\",\"title\":\"Pick-up time\",\"format\":\"time\","
                + "\"searchable\":true}"));
        assertThat(report(workflow).issues()).extracting(ValidationIssue::code)
                .contains("WF_SEARCHABLE_FIELD", "WF_SEARCHABLE_LONG_TEXT");
        assertThat(report(workflow).issues()).filteredOn(i -> i.code().equals("WF_SEARCHABLE_FIELD"))
                .extracting(ValidationIssue::path).containsExactlyInAnyOrder(
                        "/inputSchema/properties/receipt/searchable", "/inputSchema/properties/pickup/searchable");
        assertThat(report(workflow).publishable()).isFalse();

        ObjectNode many = TestSupport.purchase();
        ObjectNode fields = (ObjectNode) many.path("inputSchema").path("properties");
        for (int i = 0; i < 11; i++) {
            fields.set("code" + i, StrictJson.parse("{\"type\":\"string\",\"title\":\"Code " + i
                    + "\",\"maxLength\":20,\"searchable\":true}"));
        }
        assertThat(report(many).issues()).extracting(ValidationIssue::code).containsExactly("WF_SEARCHABLE_LIMIT");
    }

    @Test
    void listsRowsAndOtherFormsCannotBeSearchable() {
        ObjectNode workflow = TestSupport.purchase();
        ObjectNode properties = (ObjectNode) workflow.path("inputSchema").path("properties");
        properties.set("lines", StrictJson.parse("{\"type\":\"array\",\"title\":\"Lines\",\"minItems\":1,"
                + "\"maxItems\":5,\"items\":{\"type\":\"string\",\"title\":\"SKU\",\"maxLength\":20,"
                + "\"searchable\":true}}"));
        ValidationReport report = report(workflow);
        assertThat(report.issues()).extracting(ValidationIssue::code).contains("WF_SEARCHABLE_FIELD");
        assertThat(report.publishable()).isFalse();
    }
}
