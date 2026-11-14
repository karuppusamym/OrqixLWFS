package com.orqix.sdk.worker;

import java.util.regex.Pattern;

/**
 * Thrown by a handler for a business error (docs/build/S132): the work was done and the answer is "no", for example
 * {@code new BusinessErrorException("ADDRESS_UNKNOWN", "No such street")}. Final: never retried. The step takes its
 * failed route or raises its section error when the workflow declares one; otherwise the request needs attention.
 */
public class BusinessErrorException extends Exception {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final String code;
    private final String businessMessage;

    public BusinessErrorException(String code, String message) {
        super(checkCode(code) + ": " + message);
        this.code = code;
        this.businessMessage = message;
    }

    /** Validates a business error code: {@code ^[A-Z][A-Z0-9_]{1,39}$}, not starting with the reserved ORQIX_. */
    public static String checkCode(String code) {
        if (code == null || !CODE.matcher(code).matches() || code.startsWith("ORQIX_")) {
            throw new IllegalArgumentException("A business error code looks like ADDRESS_UNKNOWN (A-Z, 0-9, _; "
                    + "2-40 characters; not starting with ORQIX_).");
        }
        return code;
    }

    public String code() {
        return code;
    }

    public String businessMessage() {
        return businessMessage;
    }
}
