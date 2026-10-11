package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.orqix.compiler.internal.json.StrictJson;

/**
 * Case stages (docs/build/S17 section 4, rules WF_STAGE_*) and file fields (format attachment, section 1): rules,
 * no effect on the generated process, and form validation of the file reference.
 */
class StagesAndFilesTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();

    private static final String STAGES = """
            {"op":"add","path":"/stages","value":[
              {"id":"intake","label":"Intake","nodes":["manager"]},
              {"id":"finance","label":"Finance review","nodes":["finance","procure"],"sla":"P2D"}]}""";

    private static Arguments c(String name, String patch, String... expected) {
        return Arguments.of(name, patch, List.of(expected));
    }

    private static String stages(String value) {
        return "[{\"op\":\"add\",\"path\":\"/stages\",\"value\":" + value + "}]";
    }

    static Stream<Arguments> patches() {
        return Stream.of(
                c("valid stages with a warning for uncovered steps", "[" + STAGES + "]",
                        "W WF_STAGE_NOT_COVERED /stages"),
                c("duplicate stage id", stages("[{\"id\":\"aa\",\"label\":\"A\",\"nodes\":[\"manager\"]},"
                        + "{\"id\":\"aa\",\"label\":\"B\",\"nodes\":[\"finance\"]}]"),
                        "B WF_STAGE_DUPLICATE_ID /stages/1/id"),
                c("unknown step", stages("[{\"id\":\"aa\",\"label\":\"A\",\"nodes\":[\"manager\",\"nowhere\"]}]"),
                        "B WF_STAGE_NODE_UNKNOWN /stages/0/nodes/1"),
                c("step in two stages", stages("[{\"id\":\"aa\",\"label\":\"A\",\"nodes\":[\"manager\"]},"
                        + "{\"id\":\"bb\",\"label\":\"B\",\"nodes\":[\"manager\",\"finance\"]}]"),
                        "B WF_STAGE_NODE_DUPLICATE /stages/1/nodes/0"),
                c("step listed twice", stages("[{\"id\":\"aa\",\"label\":\"A\",\"nodes\":[\"manager\",\"manager\"]}]"),
                        "B WF_STAGE_NODE_DUPLICATE /stages/0/nodes/1"),
                c("empty stage", stages("[{\"id\":\"aa\",\"label\":\"A\",\"nodes\":[]}]"),
                        "B WF_STAGE_EMPTY /stages/0/nodes"),
                c("only instant steps", stages("[{\"id\":\"aa\",\"label\":\"A\",\"nodes\":[\"notify\"]}]"),
                        "W WF_STAGE_NO_WAITING_STEP /stages/0/nodes"),
                c("sla too long", stages("[{\"id\":\"aa\",\"label\":\"A\",\"nodes\":[\"manager\"],\"sla\":\"P91D\"}]"),
                        "B WF_STAGE_SLA_TOO_LONG /stages/0/sla"),
                c("sla too short", stages("[{\"id\":\"aa\",\"label\":\"A\",\"nodes\":[\"manager\"],\"sla\":\"PT0M\"}]"),
                        "B WF_STAGE_SLA_TOO_SHORT /stages/0/sla"),
                c("sla format", stages("[{\"id\":\"aa\",\"label\":\"A\",\"nodes\":[\"manager\"],\"sla\":\"2 days\"}]"),
                        "B WF_SCHEMA_INVALID /stages/0/sla"),
                c("stage id format", stages("[{\"id\":\"A b\",\"label\":\"A\",\"nodes\":[\"manager\"]}]"),
                        "B WF_SCHEMA_INVALID /stages/0/id"),
                c("too many stages", stages(manyStages(21)), "B WF_SCHEMA_INVALID /stages"),
                // file fields
                c("file field on the request form", """
                        [{"op":"add","path":"/inputSchema/properties/quote","value":
                          {"type":"string","title":"Quote","format":"attachment","acceptTypes":["pdf"],"maxFileBytes":1000}}]"""),
                c("unknown file type", """
                        [{"op":"add","path":"/inputSchema/properties/quote","value":
                          {"type":"string","title":"Quote","format":"attachment","acceptTypes":["exe"]}}]""",
                        "B WF_SCHEMA_INVALID /inputSchema/properties/quote/acceptTypes/0"),
                c("file limit above the platform maximum", """
                        [{"op":"add","path":"/inputSchema/properties/quote","value":
                          {"type":"string","title":"Quote","format":"attachment","maxFileBytes":20000000}}]""",
                        "B WF_SCHEMA_INVALID /inputSchema/properties/quote/maxFileBytes"),
                c("file options on a date", """
                        [{"op":"add","path":"/inputSchema/properties/day","value":
                          {"type":"string","title":"Day","format":"date","acceptTypes":["pdf"]}}]""",
                        "B WF_SCHEMA_INVALID /inputSchema/properties/day"),
                c("file field with a list of choices", """
                        [{"op":"add","path":"/inputSchema/properties/quote","value":
                          {"type":"string","title":"Quote","format":"attachment","enum":["a"]}}]""",
                        "B WF_FORM_INVALID /inputSchema/properties/quote/enum"),
                c("file field on the result form", """
                        [{"op":"add","path":"/outputSchema/properties/quote","value":
                          {"type":"string","title":"Quote","format":"attachment"}}]""",
                        "B WF_FORM_INVALID /outputSchema/properties/quote/format"),
                c("list of files", """
                        [{"op":"add","path":"/inputSchema/properties/quotes","value":
                          {"type":"array","title":"Quotes","minItems":1,"maxItems":3,
                           "items":{"type":"string","title":"Quote","format":"attachment"}}}]""",
                        "B WF_FORM_INVALID /inputSchema/properties/quotes/items/format"),
                c("file used in a condition", """
                        [{"op":"add","path":"/inputSchema/properties/quote","value":
                          {"type":"string","title":"Quote","format":"attachment"}},
                         {"op":"replace","path":"/transitions/4/condition","value":
                          {"fieldRef":"/input/quote","operator":"eq","value":"x"}}]""",
                        "B WF_OPERATOR_TYPE /transitions/4/condition/operator"));
    }

    private static String manyStages(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":\"s").append(i).append("x\",\"label\":\"S\",\"nodes\":[]}");
        }
        return sb.append(']').toString();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("patches")
    void ruleFires(String name, String patch, List<String> expected) {
        String doc = TestSupport.text(TestSupport.patch(TestSupport.purchase(), StrictJson.parse(patch)));
        ValidationReport report = COMPILER.validate(doc, TestSupport.defaultContext());
        List<String> found = report.issues().stream()
                .map(i -> (i.severity() == Severity.BLOCKER ? "B " : "W ") + i.code() + " " + i.path()).toList();
        assertThat(found).as(name + "\n" + report.issues()).containsAll(expected);
        if (expected.isEmpty()) {
            assertThat(TestSupport.codes(report.issues(), Severity.BLOCKER)).as(name).isEmpty();
        }
    }

    @Test
    void stagesAddNothingToTheGeneratedProcess() throws Exception {
        String plain = TestSupport.purchaseJson();
        String staged = TestSupport.text(TestSupport.patch(TestSupport.purchase(),
                StrictJson.parse("[" + STAGES + "]")));
        CompiledWorkflow a = COMPILER.compile(plain, TestSupport.defaultContext());
        CompiledWorkflow b = COMPILER.compile(staged, TestSupport.defaultContext());
        // The BPMN only carries the semantic hash of the whole definition; everything else is identical.
        assertThat(b.bpmnXml().replaceAll("semanticHash=\"[0-9a-f]+\"", "")).isEqualTo(a.bpmnXml().replaceAll("semanticHash=\"[0-9a-f]+\"", ""));
        assertThat(b.dmnResources()).isEqualTo(a.dmnResources());
        
        // Stages are part of the definition, so they change its semantic hash but a re-compile is stable.
        assertThat(b.semanticHash()).isNotEqualTo(a.semanticHash());
        assertThat(COMPILER.compile(staged, TestSupport.defaultContext()).semanticHash()).isEqualTo(b.semanticHash());
    }

    @Test
    void theFormValidatorChecksTheFileReference() {
        String schema = """
                {"type":"object","additionalProperties":false,"required":["quote"],
                 "properties":{"quote":{"type":"string","title":"Quote","format":"attachment"}}}""";
        FormValidator validator = Compilers.formValidator();
        assertThat(validator.validate(schema, "{\"quote\":\"018f3c1a-7b2e-7c11-9a4d-0123456789ab\"}")).isEmpty();
        assertThat(validator.validate(schema, "{\"quote\":\"report.pdf\"}")).extracting(FormValidator.FieldError::code)
                .containsExactly("format");
        assertThat(validator.validate(schema, "{\"quote\":5}")).extracting(FormValidator.FieldError::code)
                .containsExactly("type");
        assertThat(validator.validate(schema, "{}")).extracting(FormValidator.FieldError::code)
                .containsExactly("required");
    }

    @Test
    void theLoanCaseExampleCompilesWithoutIssues() throws Exception {
        String doc = TestSupport.read("examples/case-with-stages.json");
        ValidationReport report = COMPILER.validate(doc, TestSupport.defaultContext());
        assertThat(report.issues()).as(report.issues().toString()).isEmpty();
        CompiledWorkflow compiled = COMPILER.compile(doc, TestSupport.defaultContext());
        assertThat(compiled.bpmnXml()).contains("creditReview");
        assertThat(COMPILER.compile(doc, TestSupport.defaultContext()).bpmnHash()).isEqualTo(compiled.bpmnHash());
    }
}
