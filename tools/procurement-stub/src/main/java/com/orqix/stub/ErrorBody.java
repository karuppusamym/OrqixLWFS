package com.orqix.stub;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** JSON error body used for every non-2xx response of the stub. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String error, String message, List<ContractValidator.Violation> violations, String existingOrderId) {

    public static ErrorBody of(String error, String message) {
        return new ErrorBody(error, message, null, null);
    }
}
