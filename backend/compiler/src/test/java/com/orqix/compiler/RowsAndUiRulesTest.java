package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import com.orqix.compiler.internal.json.StrictJson;

class RowsAndUiRulesTest {
    private ObjectNode rows(String columns,String required) {
        var d=TestSupport.purchase();
        ((ObjectNode)d.path("inputSchema").path("properties")).set("lines",StrictJson.parse("{\"type\":\"array\",\"title\":\"Line items\",\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":"+required+",\"properties\":"+columns+"}}"));
        return d;
    }
    private List<String> blockers(ObjectNode d) { return Compilers.workflowCompiler().validate(TestSupport.text(d),TestSupport.defaultContext()).issues().stream().filter(i->i.severity()==Severity.BLOCKER).map(ValidationIssue::code).toList(); }
    @Test void acceptsBoundedRowsOfRequiredAndOptionalScalars() throws Exception {
        var d=rows("{\"quantity\":{\"type\":\"integer\",\"title\":\"Quantity\",\"minimum\":1,\"maximum\":10},\"date\":{\"type\":\"string\",\"title\":\"Delivery date\",\"format\":\"date\",\"formatMinimum\":\"2026-01-01\",\"formatMaximum\":\"2026-12-31\"}}","[\"quantity\"]");
        assertThat(blockers(d)).isEmpty();
        assertThat(Compilers.workflowCompiler().compile(TestSupport.text(d),TestSupport.defaultContext()).bpmnXml()).contains("Create purchase order");
    }
    @Test void rejectsUndefinedRequiredColumnsAndInvertedBounds() {
        var d=rows("{\"label\":{\"type\":\"string\",\"title\":\"Item\",\"minLength\":10,\"maxLength\":2},\"quantity\":{\"type\":\"integer\",\"title\":\"Quantity\",\"minimum\":10,\"maximum\":1}}","[\"missing\"]");
        assertThat(blockers(d)).contains("WF_FORM_INVALID");
        assertThat(Compilers.workflowCompiler().validate(TestSupport.text(d),TestSupport.defaultContext()).issues()).filteredOn(i->i.severity()==Severity.BLOCKER).hasSizeGreaterThanOrEqualTo(3);
    }
    @Test void rejectsFilesEditableColumnsAndWrongConstraintKinds() {
        var d=rows("{\"file\":{\"type\":\"string\",\"title\":\"File\",\"format\":\"attachment\"},\"count\":{\"type\":\"integer\",\"title\":\"Count\",\"multipleOf\":0.01,\"minLength\":1},\"note\":{\"type\":\"string\",\"title\":\"Note\",\"minimum\":1,\"incidentEditable\":true}}","[]");
        assertThat(blockers(d)).contains("WF_FORM_INVALID");
    }
    @Test void rejectsInvalidRowDatesAndNestedCollections() {
        assertThat(blockers(rows("{\"date\":{\"type\":\"string\",\"title\":\"Delivery date\",\"format\":\"date\",\"formatMinimum\":\"2026-02-30\"}}","[]"))).contains("WF_FORM_INVALID");
        assertThat(blockers(rows("{\"nested\":{\"type\":\"array\",\"title\":\"Nested\",\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":\"string\",\"title\":\"Item\"}}}","[]"))).contains("WF_SCHEMA_INVALID");
    }
    @Test void rejectsConditionalTargetsAndControllersOutsideTheRequest() {
        var d=TestSupport.purchase();var ui=(ObjectNode)d.path("inputUi");
        ui.putObject("visibleWhen").set("missing",StrictJson.parse("{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"OPS\"}"));
        assertThat(blockers(d)).contains("WF_FORM_INVALID");
        ui.putObject("visibleWhen").set("purpose",StrictJson.parse("{\"fieldRef\":\"/steps/manager/decision\",\"operator\":\"eq\",\"value\":\"approved\"}"));
        assertThat(blockers(d)).contains("WF_FORM_INVALID");
    }
    @Test void rejectsConditionalControllerChainsAndDuplicatedSectionQuestions() {
        var d=TestSupport.purchase();var ui=(ObjectNode)d.path("inputUi");
        var visible=ui.putObject("visibleWhen");visible.set("purpose",StrictJson.parse("{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"OPS\"}"));
        visible.set("title",StrictJson.parse("{\"fieldRef\":\"/input/purpose\",\"operator\":\"eq\",\"value\":\"Allow\"}"));
        assertThat(blockers(d)).contains("WF_FORM_INVALID");
        ui.remove("visibleWhen");ui.putObject("layout").set("sections",StrictJson.parse("[{\"id\":\"first\",\"title\":\"First section\",\"fields\":[\"purpose\"]},{\"id\":\"second\",\"title\":\"Second section\",\"fields\":[\"purpose\",\"missing\"]}]"));
        assertThat(blockers(d)).contains("WF_FORM_INVALID");
    }
}
