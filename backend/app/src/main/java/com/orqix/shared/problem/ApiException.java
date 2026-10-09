package com.orqix.shared.problem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raised by application code to produce an RFC 9457 problem response with an Orqix {@code code}. Extension members
 * (fieldErrors, issues, currentRevision, currentVersion) and response headers can be attached.
 */
public class ApiException extends RuntimeException {

    private final ProblemCode code;
    private final String detail;
    private final Map<String, Object> extensions = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();

    public ApiException(ProblemCode code) {
        this(code, null);
    }

    public ApiException(ProblemCode code, String detail) {
        super(code.name() + (detail == null ? "" : ": " + detail), null, false, false);
        this.code = code;
        this.detail = detail;
    }

    public static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND);
    }

    public static ApiException forbidden() {
        return new ApiException(ProblemCode.FORBIDDEN);
    }

    public static ApiException malformed(String detail) {
        return new ApiException(ProblemCode.MALFORMED_REQUEST, detail);
    }

    public ApiException with(String name, Object value) {
        extensions.put(name, value);
        return this;
    }

    public ApiException withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public ApiException withFieldErrors(List<FieldError> fieldErrors) {
        return with("fieldErrors", fieldErrors);
    }

    public ProblemCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public Map<String, String> headers() {
        return headers;
    }

    /** Problem {@code fieldErrors[]} item. */
    public record FieldError(String pointer, String code, String message) {
    }
}
