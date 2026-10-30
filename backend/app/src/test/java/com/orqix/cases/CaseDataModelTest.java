package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.orqix.compiler.Compilers;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** S125: case data fields carry the single-value request-question checks; older fields and keys keep working. */
class CaseDataModelTest {
    private final Json json = new Json(JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build());
    private final CaseDataModel model = new CaseDataModel(json, Compilers.formValidator());

    private static CaseDataModel.Field text(String key, Integer min, Integer max, String pattern, String message) {
        return new CaseDataModel.Field(key, "Reference", "text", true, max, null, null, null, "Ask the customer", min, pattern, message, null, null, null, null);
    }

    @Test void olderFieldsReadAndValidateAsBefore() {
        var stored = "[{\"key\":\"Customer_Name\",\"label\":\"Customer\",\"type\":\"text\",\"required\":true,\"maxLength\":80,\"minimum\":null,\"maximum\":null,\"options\":null},"
                + "{\"key\":\"amount\",\"label\":\"Amount\",\"type\":\"number\",\"required\":false,\"maxLength\":null,\"minimum\":0,\"maximum\":100,\"options\":null}]";
        var fields = model.readFields(stored);
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).pattern()).isNull();
        assertThat(model.values(fields, json.mapper().valueToTree(Map.of("Customer_Name", "Ada", "amount", 12.5))).path("Customer_Name").asString()).isEqualTo("Ada");
        // Without the S125 properties the stored JSON has the same shape as before.
        assertThat(json.write(fields)).doesNotContain("pattern", "description", "widget", "multipleOf");
        assertThatThrownBy(() -> model.values(fields, json.mapper().valueToTree(Map.of("amount", 1)))).isInstanceOf(ApiException.class);
    }

    @Test void legacyKeysAreWarningsWithASuggestedKey() {
        var fields = model.fields(List.of(
                new CaseDataModel.Field("Customer_Name", "Customer", "text", false, null, null, null, null),
                new CaseDataModel.Field("claim_2", "Claim", "text", false, null, null, null, null),
                new CaseDataModel.Field("summary", "Summary", "text", false, null, null, null, null)));
        var warnings = CaseDataModel.warnings(fields);
        assertThat(warnings).extracting(CaseDataModel.Warning::field).containsExactly("Customer_Name", "claim_2");
        assertThat(warnings).extracting(CaseDataModel.Warning::code).containsOnly(CaseDataModel.LEGACY_KEY);
        assertThat(warnings.get(0).message()).contains("keeps working").contains("customerName");
        assertThat(warnings.get(1).message()).contains("claim2");
        assertThat(CaseDataModel.suggestKey("_9")).isNull();
        assertThatThrownBy(() -> model.fields(List.of(new CaseDataModel.Field("9lives", "Lives", "text", false, null, null, null, null))))
                .isInstanceOf(ApiException.class);
    }

    @Test void patternLengthAndHelpTextAreCheckedLikeARequestQuestion() {
        var fields = model.fields(List.of(text("reference", 4, 12, "^CASE-\\d{4}$", "Enter a reference like CASE-1234.")));
        assertThat(fields.get(0).description()).isEqualTo("Ask the customer");
        assertThat(model.values(fields, json.mapper().valueToTree(Map.of("reference", "CASE-0042"))).path("reference").asString()).isEqualTo("CASE-0042");
        assertThatThrownBy(() -> model.values(fields, json.mapper().valueToTree(Map.of("reference", "CASE-42"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("/reference (pattern)");
        var shortest = model.fields(List.of(text("reference", 4, 12, null, null)));
        assertThatThrownBy(() -> model.values(shortest, json.mapper().valueToTree(Map.of("reference", "abc"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("minLength");
        assertThatThrownBy(() -> model.fields(List.of(text("reference", null, null, "^(a+)+[$", null))))
                .isInstanceOf(ApiException.class).hasMessageContaining("answer pattern");
        assertThatThrownBy(() -> model.fields(List.of(text("reference", 20, 10, null, null)))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> model.fields(List.of(text("reference", null, null, null, "A message without a pattern")))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> model.fields(List.of(new CaseDataModel.Field("count", "Count", "integer", false, null, null, null, null, null, null, "^\\d+$", null, null, null, null, null))))
                .isInstanceOf(ApiException.class).hasMessageContaining("Only text fields");
    }

    @Test void datesMoneyAndLongTextFollowTheRequestFormRules() {
        var fields = model.fields(List.of(
                new CaseDataModel.Field("due", "Due", "date", false, null, null, null, null, null, null, null, null, "2026-01-01", "2026-12-31", null, null),
                new CaseDataModel.Field("amount", "Amount", "number", false, null, new BigDecimal("0.01"), null, null, null, null, null, null, null, null, new BigDecimal("0.010"), "money"),
                new CaseDataModel.Field("notes", "Notes", "text", false, 4000, null, null, null, null, null, null, null, null, null, null, "textarea")));
        assertThat(fields.get(1).multipleOf()).isEqualByComparingTo("0.01");
        assertThat(model.values(fields, json.mapper().valueToTree(Map.of("due", "2026-06-30", "amount", 1800.07, "notes", "Long notes"))).size()).isEqualTo(3);
        assertThatThrownBy(() -> model.values(fields, json.mapper().valueToTree(Map.of("due", "2027-01-01")))).hasMessageContaining("formatMaximum");
        assertThatThrownBy(() -> model.values(fields, json.mapper().valueToTree(Map.of("amount", 1800.071)))).hasMessageContaining("multipleOf");
        assertThatThrownBy(() -> model.fields(List.of(new CaseDataModel.Field("due", "Due", "date", false, null, null, null, null, null, null, null, null, "2026-12-31", "2026-01-01", null, null))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> model.fields(List.of(new CaseDataModel.Field("due", "Due", "date", false, null, null, null, null, null, null, null, null, "2026-02-30", null, null, null))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> model.fields(List.of(new CaseDataModel.Field("amount", "Amount", "integer", false, null, null, null, null, null, null, null, null, null, null, null, "money"))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> model.fields(List.of(new CaseDataModel.Field("amount", "Amount", "number", false, null, null, null, null, null, null, null, null, null, null, new BigDecimal("0.5"), null))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> model.fields(List.of(new CaseDataModel.Field("notes", "Notes", "text", false, null, null, null, null, "x".repeat(501), null, null, null, null, null, null, null))))
                .isInstanceOf(ApiException.class);
    }
}
