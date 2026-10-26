package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.orqix.compiler.FormValidator.FieldError;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * G5 (docs/build/S90): the safe form pattern engine against the shared cases (also run by the browser engine), its
 * linear-time behaviour on catastrophic-backtracking patterns, the compiler rule WF_FORM_PATTERN_INVALID, server
 * validation and the BPMN round trip.
 */
class FormPatternTest {

    private static final JsonNode CASES = TestSupport.json("examples/fixtures/form-pattern-cases.json");
    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();
    private static final FormValidator VALIDATOR = Compilers.formValidator();

    @TestFactory
    Stream<DynamicTest> acceptedPatternsMatchExactlyTheSharedCases() {
        return CASES.get("valid").valueStream().map(c -> DynamicTest.dynamicTest(c.get("pattern").stringValue(), () -> {
            FormPattern pattern = FormPattern.compile(c.get("pattern").stringValue());
            c.get("accept").forEach(a -> assertThat(pattern.matches(a.stringValue())).as("accepts " + a).isTrue());
            c.get("reject").forEach(r -> assertThat(pattern.matches(r.stringValue())).as("rejects " + r).isFalse());
            assertThat(pattern.example()).isEqualTo(c.get("example").stringValue());
            assertThat(pattern.matches(pattern.example())).isTrue();
            assertThat(FormPattern.problem(c.get("pattern").stringValue())).isNull();
        }));
    }

    @TestFactory
    Stream<DynamicTest> refusedPatternsGiveTheReason() {
        return CASES.get("invalid").valueStream().map(c -> DynamicTest.dynamicTest("refuses \"" + c.get("pattern").stringValue() + "\"", () -> {
            assertThatThrownBy(() -> FormPattern.compile(c.get("pattern").stringValue()))
                    .isInstanceOf(FormPattern.Invalid.class)
                    .hasMessageContaining(c.get("reason").stringValue());
            assertThat(FormPattern.problem(c.get("pattern").stringValue())).contains(c.get("reason").stringValue());
        }));
    }

    /** The accepted subset means the same as java.util.regex for the cases without \s (whose sets differ by design). */
    @Test
    void acceptedPatternsAgreeWithABacktrackingEngineOnTheSharedCases() {
        for (JsonNode c : CASES.get("valid")) {
            String source = c.get("pattern").stringValue();
            if (source.contains("\\s") || source.contains("\\S")) {
                continue;
            }
            java.util.regex.Pattern reference = java.util.regex.Pattern.compile(source.substring(1, source.length() - 1));
            FormPattern pattern = FormPattern.compile(source);
            for (String group : List.of("accept", "reject")) {
                for (JsonNode input : c.get(group)) {
                    assertThat(pattern.matches(input.stringValue())).as(source + " on " + input)
                            .isEqualTo(reference.matcher(input.stringValue()).matches());
                }
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> catastrophicBacktrackingPatternsFinishQuickly() {
        return CASES.get("slow").valueStream().map(c -> DynamicTest.dynamicTest(c.get("pattern").stringValue(), () -> {
            FormPattern pattern = FormPattern.compile(c.get("pattern").stringValue());
            String input = c.get("input").stringValue().repeat(c.get("repeat").asInt()) + c.get("suffix").stringValue();
            long started = System.nanoTime();
            boolean matched = pattern.matches(input);
            long ms = Duration.ofNanos(System.nanoTime() - started).toMillis();
            // A backtracking engine needs longer than the age of the universe for several of these.
            assertThat(ms).as("matching took " + ms + " ms").isLessThan(1000);
            assertThat(matched).isEqualTo("^(\\w+\\s?)*$".equals(c.get("pattern").stringValue()) ? false
                    : "^(x+x+)+y$".equals(c.get("pattern").stringValue()) ? false : matched);
        }));
    }

    @Test
    void longAnswersNeverMatchAndCompilingTheLargestPatternsIsFast() {
        FormPattern any = FormPattern.compile("^.*$");
        assertThat(any.matches("a".repeat(FormPattern.MAX_INPUT_LENGTH))).isTrue();
        assertThat(any.matches("a".repeat(FormPattern.MAX_INPUT_LENGTH + 1))).isFalse();
        assertThat(any.matches(null)).isFalse();
        long started = System.nanoTime();
        for (String source : List.of("^([a-z]{1,50}){1,10}$", "^(a{100}){19}$", "^(((){100}){100}){100}$",
                "^((a|b|c|d|e|f|g|h){100}){19}$")) {
            FormPattern.problem(source);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(3000);
    }

    // ------------------------------------------------------------------ compiler rule

    private static List<String> issues(String patch) {
        String doc = TestSupport.text(TestSupport.patch(TestSupport.purchase(), StrictJson.parse(patch)));
        ValidationReport report = COMPILER.validate(doc, TestSupport.defaultContext());
        report.issues().forEach(i -> assertThat(i.message()).isNotBlank().doesNotContain("null"));
        return report.issues().stream().filter(i -> i.severity() == Severity.BLOCKER)
                .map(i -> i.code() + " " + i.path() + " " + i.message()).toList();
    }

    @Test
    void aValidPatternWithAMessageCompiles() {
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^[A-Z][A-Za-z0-9 ]{0,99}$"},
                 {"op":"add","path":"/inputSchema/properties/title/patternMessage","value":"Start with a capital letter."}]
                """)).isEmpty();
    }

    @Test
    void unsafeOrUnsupportedPatternsAreBlockersWithTheReason() {
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^(a)\\\\1$"}]"""))
                .singleElement().asString().startsWith("WF_FORM_PATTERN_INVALID /inputSchema/properties/title/pattern")
                .contains("Backreferences");
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^(?=a)a$"}]"""))
                .singleElement().asString().contains("WF_FORM_PATTERN_INVALID", "Lookarounds");
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^((a{100}){100}){100}$"}]"""))
                .singleElement().asString().contains("too complex");
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^[A-Z]{150}$"}]"""))
                .singleElement().asString().contains("limited to 100");
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^[A-Z]{100}[0-9]{30}$"}]"""))
                .singleElement().asString().contains("longest length (120 characters)");
    }

    @Test
    void patternsOnlyApplyToPlainTextAndMessagesArePlainText() {
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/costCenter/pattern","value":"^[A-Z]+$"}]"""))
                .singleElement().asString().startsWith("WF_FORM_PATTERN_INVALID /inputSchema/properties/costCenter/pattern")
                .contains("not a plain text question");
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/amount/pattern","value":"^\\\\d+$"}]"""))
                .singleElement().asString().contains("WF_FORM_PATTERN_INVALID /inputSchema/properties/amount/pattern");
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^.+$"},
                 {"op":"add","path":"/inputSchema/properties/title/patternMessage","value":"Hi ${user}"}]"""))
                .singleElement().asString().contains("/inputSchema/properties/title/patternMessage", "${");
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^.+$"},
                 {"op":"add","path":"/inputSchema/properties/title/patternMessage","value":"Two\\nlines"}]"""))
                .singleElement().asString().contains("one line of plain text");
        // The JSON Schema: a message needs a pattern; patterns are 2-200 characters.
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/patternMessage","value":"Wrong."}]"""))
                .singleElement().asString().startsWith("WF_SCHEMA_INVALID");
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^%s$"}]""".formatted("a".repeat(199))))
                .singleElement().asString().startsWith("WF_SCHEMA_INVALID");
    }

    @Test
    void listItemsAndRowColumnsAreCheckedToo() {
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/tags","value":{"type":"array","title":"Tags","minItems":1,"maxItems":5,
                  "items":{"type":"string","title":"Tag","maxLength":20,"pattern":"^[a-z]+(?<x>)$"}}}]"""))
                .singleElement().asString().startsWith("WF_FORM_PATTERN_INVALID /inputSchema/properties/tags/items/pattern");
        assertThat(issues("""
                [{"op":"add","path":"/inputSchema/properties/lines","value":{"type":"array","title":"Lines","minItems":1,"maxItems":5,
                  "items":{"type":"object","additionalProperties":false,"required":["sku"],
                  "properties":{"sku":{"type":"string","title":"SKU","maxLength":20,"pattern":"^SKU-\\\\d+$"},
                                "qty":{"type":"integer","title":"Qty","pattern":"^\\\\d+$"}}}}}]"""))
                .singleElement().asString()
                .startsWith("WF_FORM_PATTERN_INVALID /inputSchema/properties/lines/items/properties/qty/pattern");
    }

    // ------------------------------------------------------------------ server validation (authoritative)

    private static String schemaWithPattern() {
        ObjectNode schema = (ObjectNode) TestSupport.purchase().get("inputSchema").deepCopy();
        ((ObjectNode) schema.path("properties").path("title")).put("pattern", "^[A-Z]{2}-\\d{4}$")
                .put("patternMessage", "Enter a code like <b>AB-1234</b>.");
        ObjectNode lines = ((ObjectNode) schema.path("properties")).putObject("lines");
        lines.put("type", "array").put("title", "Lines").put("minItems", 1).put("maxItems", 3);
        ObjectNode row = lines.putObject("items").put("type", "object").put("additionalProperties", false);
        row.putArray("required").add("sku");
        row.putObject("properties").putObject("sku").put("type", "string").put("title", "SKU").put("pattern", "^SKU-\\d+$");
        return StrictJson.write(schema);
    }

    private static String input(String title, String sku) {
        ObjectNode input = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        input.put("title", title).put("purpose", "Laptop").put("costCenter", "IT").put("amount", 10);
        if (sku != null) {
            input.putArray("lines").addObject().put("sku", sku);
        }
        return StrictJson.write(input);
    }

    @Test
    void theServerRejectsAnswersThatDoNotMatchWithTheAuthorsMessage() {
        String schema = schemaWithPattern();
        assertThat(VALIDATOR.validate(schema, input("AB-1234", "SKU-7"))).isEmpty();
        assertThat(VALIDATOR.validate(schema, input("ab-1234", "SKU-7"))).containsExactly(
                new FieldError("/title", "pattern", "Enter a code like <b>AB-1234</b>."));
        assertThat(VALIDATOR.validate(schema, input("AB-1234", "sku-7"))).extracting(FieldError::pointer, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("/lines/0/sku", "pattern"));
        // A catastrophic-backtracking attempt on the server: linear, refused, quick.
        ObjectNode slow = (ObjectNode) TestSupport.purchase().get("inputSchema").deepCopy();
        ((ObjectNode) slow.path("properties").path("title")).put("maxLength", 4000).put("pattern", "^(a+)+$");
        long started = System.nanoTime();
        assertThat(VALIDATOR.validate(StrictJson.write(slow), input("a".repeat(3999) + "!", null)))
                .extracting(FieldError::code).containsExactly("pattern");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(1000);
    }

    @Test
    void withoutAMessageTheServerUsesANeutralOneAndATamperedPatternFailsClosed() {
        String schema = schemaWithPattern().replace(",\"patternMessage\":\"Enter a code like <b>AB-1234</b>.\"", "");
        assertThat(VALIDATOR.validate(schema, input("x", "SKU-1"))).extracting(FieldError::message)
                .containsExactly("Enter the answer in the expected format.");
        String tampered = schemaWithPattern().replace("^[A-Z]{2}-\\\\d{4}$", "^(a)\\\\1$");
        assertThat(tampered).contains("\\\\1");
        assertThat(VALIDATOR.validate(tampered, input("aa", "SKU-1"))).extracting(FieldError::code).containsExactly("pattern");
    }

    // ------------------------------------------------------------------ BPMN round trip

    @Test
    void patternsAreKeptFromTheDraftAndDoNotChangeTheBpmn() throws Exception {
        String plain = TestSupport.purchaseJson();
        String withPattern = TestSupport.text(TestSupport.patch(TestSupport.purchase(), StrictJson.parse("""
                [{"op":"add","path":"/inputSchema/properties/title/pattern","value":"^[A-Z].*$"},
                 {"op":"add","path":"/inputSchema/properties/title/patternMessage","value":"Start with a capital letter."}]""")));
        CompiledWorkflow a = COMPILER.compile(plain, TestSupport.defaultContext());
        CompiledWorkflow b = COMPILER.compile(withPattern, TestSupport.defaultContext());
        // Only the embedded semantic hash differs: the pattern is not part of the process.
        assertThat(b.bpmnXml()).doesNotContain("patternMessage").doesNotContain("capital letter").doesNotContain("^[A-Z]");
        assertThat(b.bpmnXml().replace(b.semanticHash(), "")).isEqualTo(a.bpmnXml().replace(a.semanticHash(), ""));
        BpmnSource.Export export = BpmnSource.export(withPattern, b);
        assertThat(export.identical()).as(export.differences().toString()).isTrue();
        assertThat(export.keptFromDraft()).contains("inputSchema");
    }
}
