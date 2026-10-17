package com.orqix.compiler;

import java.util.List;

/**
 * Validates request input against a release inputSchema (approved keyword subset) with BigDecimal numerics, so that
 * 1800.07 is valid and 1800.071 is not for multipleOf 0.01 (S5 section 3, fixtures purchase-scenarios.json).
 */
public interface FormValidator {

    /** @return empty when valid; otherwise one error per failed keyword, pointer relative to the input object */
    List<FieldError> validate(String inputSchemaJson, String inputJson);

    /** Validates visibility and conditional requiredness against the same immutable form presentation as execution. */
    List<FieldError> validate(String inputSchemaJson,String inputJson,String inputUiJson);

    /**
     * @param pointer JSON Pointer such as /amount
     * @param code    failed keyword: required, type, enum, minimum, maximum, minLength, maxLength, multipleOf,
     *                additionalProperties
     */
    record FieldError(String pointer, String code, String message) {
    }
}
