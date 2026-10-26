package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.orqix.ai.internal.FormSuggestions;
import com.orqix.compiler.Compilers;
import com.orqix.shared.json.Json;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** G4 (docs/build/S90): untrusted model replies are validated change by change with the real compiler. */
class FormSuggestionsTest {

    private final Json json = new Json(JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build());
    private final FormSuggestions rules = new FormSuggestions(json, Compilers.workflowCompiler());

    private JsonNode purchaseForm() throws Exception {
        return json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot", "../.."), "examples",
                "purchase-approval.json"))).get("inputSchema");
    }

    @Test
    void validChangesAreKeptInOrderWithTheCurrentQuestionForReview() throws Exception {
        JsonNode reply = json.parse("""
                {"changes": [
                  {"op": "add", "key": "costCode", "field": {"type": "string", "title": "Cost code", "maxLength": 7,
                    "pattern": "^[A-Z]{2}-\\\\d{4}$", "patternMessage": "Enter a code like AB-1234."}, "required": true,
                    "reason": "Finance needs the code."},
                  {"op": "update", "key": "costCenter", "field": {"type": "string", "title": "Cost center",
                    "enum": ["OPS", "IT", "HR", "FIN"]}, "required": null, "reason": "Add Finance."},
                  {"op": "remove", "key": "purpose", "field": null, "required": null, "reason": "Not needed."}],
                 "notes": ["Line one\\nand ${two}"], "notAForm": null}""");
        FormSuggestions.Result result = rules.evaluate(reply, purchaseForm());
        assertThat(result.rejected()).isEmpty();
        assertThat(result.changes()).extracting(FormSuggestions.Change::op).containsExactly("add", "update", "remove");
        FormSuggestions.Change update = result.changes().get(1);
        assertThat(update.required()).isTrue();
        assertThat(update.wasRequired()).isTrue();
        assertThat(update.before().path("enum").size()).isEqualTo(3);
        assertThat(result.changes().get(0).before()).isNull();
        assertThat(result.notes()).containsExactly("Line one and $ {two}");
        assertThat(result.proposedSchema().path("required").toString()).contains("costCode").doesNotContain("purpose");
        assertThat(result.proposedSchema().path("properties").has("purpose")).isFalse();
    }

    @Test
    void invalidChangesAreRejectedWithTheCompilerReasonAndNotApplied() throws Exception {
        JsonNode reply = json.parse("""
                {"changes": [
                  {"op": "add", "key": "code", "field": {"type": "string", "title": "Code", "pattern": "^(a+)\\\\1$"}, "reason": "x"},
                  {"op": "add", "key": "count", "field": {"type": "integer", "title": "Count", "minimum": 5, "maximum": 1}, "reason": "x"},
                  {"op": "add", "key": "kind", "field": {"type": "colour", "title": "Kind"}, "reason": "x"},
                  {"op": "add", "key": "Bad Key", "field": {"type": "string", "title": "B"}, "reason": "x"},
                  {"op": "add", "key": "title", "field": {"type": "string", "title": "Again"}, "reason": "x"},
                  {"op": "update", "key": "missing", "field": {"type": "string", "title": "M"}, "reason": "x"},
                  {"op": "rename", "key": "amount", "reason": "x"},
                  {"op": "add", "key": "hint", "field": {"type": "string", "title": "Hint ${x}", "patternMessage": "m"}, "reason": "x"}],
                 "notes": []}""");
        FormSuggestions.Result result = rules.evaluate(reply, purchaseForm());
        assertThat(result.changes()).isEmpty();
        assertThat(result.rejected()).hasSize(8);
        assertThat(result.rejected().get(0).reasons().toString()).contains("Backreferences");
        assertThat(result.rejected().get(1).reasons().toString()).contains("minimum greater than its maximum");
        assertThat(result.rejected().get(3).reasons().toString()).contains("lowercase letter");
        assertThat(result.rejected().get(4).reasons().toString()).contains("already exists");
        assertThat(result.rejected().get(5).reasons().toString()).contains("no question");
        assertThat(result.rejected().get(6).reasons().toString()).contains("Unknown change");
        assertThat(result.proposedSchema().path("properties").size()).isEqualTo(4);
    }

    @Test
    void atMostTenChangesAreReviewed() throws Exception {
        StringBuilder changes = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            changes.append(i == 0 ? "" : ",").append("{\"op\":\"add\",\"key\":\"q").append(i)
                    .append("\",\"field\":{\"type\":\"boolean\",\"title\":\"Q").append(i).append("\"},\"reason\":\"r\"}");
        }
        FormSuggestions.Result result = rules.evaluate(json.parse("{\"changes\":[" + changes + "],\"notes\":[]}"),
                purchaseForm());
        assertThat(result.changes()).hasSize(10);
        assertThat(result.rejected()).hasSize(2).allSatisfy(r -> assertThat(r.reasons().get(0)).contains("10 changes"));
    }
}
