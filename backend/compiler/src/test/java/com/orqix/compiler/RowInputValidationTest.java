package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Public request validation: bounded line-item rows retain scalar type/bounds and reject unknown columns. */
class RowInputValidationTest {
    private static final String SCHEMA="""
        {"type":"object","additionalProperties":false,"required":["lines"],"properties":{
          "lines":{"type":"array","title":"Line items","minItems":1,"maxItems":2,"items":{
            "type":"object","additionalProperties":false,"required":["label","quantity","approved"],"properties":{
              "label":{"type":"string","title":"Label","minLength":1,"maxLength":8},
              "quantity":{"type":"integer","title":"Quantity","minimum":1,"maximum":10},
              "price":{"type":"number","title":"Price","minimum":0,"multipleOf":0.01},
              "approved":{"type":"boolean","title":"Approved"}
            }}}}}
        """;
    private java.util.List<FormValidator.FieldError> errors(String input) { return Compilers.formValidator().validate(SCHEMA,input); }
    @Test void acceptsTypedRowsWithOptionalCellsAndExactCents() {
        assertThat(errors("{\"lines\":[{\"label\":\"Laptop\",\"quantity\":2,\"approved\":false,\"price\":1800.07},{\"label\":\"Monitor\",\"quantity\":1,\"approved\":true}]}")).isEmpty();
    }
    @Test void reportsMissingRequiredCellsWithTheRowIndex() {
        assertThat(errors("{\"lines\":[{\"label\":\"Laptop\",\"approved\":false}]}"))
                .extracting(FormValidator.FieldError::pointer).containsExactly("/lines/0/quantity");
    }
    @Test void rejectsUnknownColumnsAndCoercedScalarTypes() {
        var errors=errors("{\"lines\":[{\"label\":\"Laptop\",\"quantity\":\"2\",\"approved\":\"true\",\"secret\":1}]}");
        assertThat(errors).extracting(FormValidator.FieldError::pointer).contains("/lines/0/secret","/lines/0/quantity","/lines/0/approved");
        assertThat(errors).extracting(FormValidator.FieldError::code).contains("additionalProperties","type");
    }
    @Test void keepsPerCellBoundsAndRejectsNonObjectRows() {
        var errors=errors("{\"lines\":[{\"label\":\"Too long label\",\"quantity\":11,\"approved\":false,\"price\":1.001},\"wrong\"]}");
        assertThat(errors).extracting(FormValidator.FieldError::pointer).contains("/lines/0/label","/lines/0/quantity","/lines/0/price","/lines/1");
        assertThat(errors).extracting(FormValidator.FieldError::code).contains("maxLength","maximum","multipleOf","type");
    }
    @Test void enforcesRowCountBoundsWithoutAcceptingEmptyRequiredCollections() {
        assertThat(errors("{\"lines\":[]}")).extracting(FormValidator.FieldError::code).containsExactly("minItems");
        assertThat(errors("{\"lines\":[{\"label\":\"A\",\"quantity\":1,\"approved\":false},{\"label\":\"B\",\"quantity\":1,\"approved\":false},{\"label\":\"C\",\"quantity\":1,\"approved\":false}]}"))
                .extracting(FormValidator.FieldError::code).containsExactly("maxItems");
    }
}
