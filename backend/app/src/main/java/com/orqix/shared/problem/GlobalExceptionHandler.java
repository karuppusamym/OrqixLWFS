package com.orqix.shared.problem;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.orqix.shared.web.CorrelationId;

import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Maps every failure to RFC 9457 problem details with {@code code} and {@code correlationId} (S3 section 4). Never
 * exposes stack traces, SQL, class names or values of submitted fields.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final MediaType PROBLEM = MediaType.parseMediaType(ProblemWriter.MEDIA_TYPE);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Problem> api(ApiException e) {
        return build(e.code(), e.code().status(), e.detail(), e.extensions(), e.headers());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> unreadable(HttpMessageNotReadableException e) {
        String detail = "The request body is not valid JSON for this operation.";
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof com.orqix.shared.web.RequestLimitsFilter.PayloadTooLargeException) {
                return build(ProblemCode.PAYLOAD_TOO_LARGE, 413, "The request body is too large.", Map.of(), Map.of());
            }
            if (t instanceof tools.jackson.core.exc.StreamConstraintsException) {
                return build(ProblemCode.MALFORMED_REQUEST, 400, "The request body is nested too deeply.", Map.of(),
                        Map.of());
            }
        }
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof UnrecognizedPropertyException upe) {
            detail = "Unknown property '" + safe(upe.getPropertyName()) + "'.";
        } else if (cause instanceof DatabindException de && de.getPath() != null && !de.getPath().isEmpty()) {
            detail = "Invalid value at '" + pointer(de) + "'.";
        }
        return build(ProblemCode.MALFORMED_REQUEST, 400, detail, Map.of(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> invalid(MethodArgumentNotValidException e) {
        List<ApiException.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiException.FieldError("/" + fe.getField().replace('.', '/'),
                        fe.getCode() == null ? "invalid" : fe.getCode(), "Invalid value"))
                .toList();
        return build(ProblemCode.MALFORMED_REQUEST, 400, "The request does not match the operation schema.",
                Map.of("fieldErrors", errors), Map.of());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Problem> invalidParameters(HandlerMethodValidationException e) {
        List<ApiException.FieldError> errors = e.getParameterValidationResults().stream()
                .map(ParameterValidationResult::getMethodParameter)
                .map(p -> new ApiException.FieldError("/" + p.getParameterName(), "invalid", "Invalid value"))
                .toList();
        return build(ProblemCode.MALFORMED_REQUEST, 400, "A parameter is not valid.", Map.of("fieldErrors", errors),
                Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Problem> typeMismatch(MethodArgumentTypeMismatchException e) {
        if (e.getParameter().hasParameterAnnotation(PathVariable.class)) {
            // A malformed identifier cannot name an existing resource.
            return build(ProblemCode.NOT_FOUND, 404, null, Map.of(), Map.of());
        }
        return build(ProblemCode.MALFORMED_REQUEST, 400, "Invalid value for parameter '" + safe(e.getName()) + "'.",
                Map.of(), Map.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    ResponseEntity<Problem> missing(Exception e) {
        return build(ProblemCode.MALFORMED_REQUEST, 400, "A required parameter is missing.", Map.of(), Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Problem> noResource(NoResourceFoundException e) {
        return build(ProblemCode.NOT_FOUND, 404, null, Map.of(), Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Problem> method(HttpRequestMethodNotSupportedException e) {
        return build(ProblemCode.MALFORMED_REQUEST, 405, "Method not supported.", Map.of(), Map.of());
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    ResponseEntity<Problem> mediaType(Exception e) {
        int status = e instanceof HttpMediaTypeNotSupportedException ? 415 : 406;
        return build(ProblemCode.MALFORMED_REQUEST, status, "Unsupported media type.", Map.of(), Map.of());
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class, QueryTimeoutException.class, CannotAcquireLockException.class,
            PessimisticLockingFailureException.class})
    ResponseEntity<Problem> unavailable(Exception e) {
        log.warn("Dependency unavailable: {}", e.getClass().getSimpleName());
        return build(ProblemCode.DEPENDENCY_UNAVAILABLE, 503, null, Map.of(), Map.of(HttpHeaders.RETRY_AFTER, "5"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unexpected(Exception e) {
        if (isDatabaseUnavailable(e)) {
            return unavailable(e);
        }
        log.error("Unexpected error", e);
        return build(ProblemCode.INTERNAL_ERROR, 500, null, Map.of(), Map.of());
    }

    /** Connection loss, lock_timeout, statement_timeout or server shutdown: nothing was committed. */
    public static boolean isDatabaseUnavailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (state.startsWith("08") || state.equals("55P03") || state.equals("57014") || state.startsWith("57P")) {
                    return true;
                }
            }
            if (t instanceof CannotGetJdbcConnectionException || t instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<Problem> build(ProblemCode code, int status, String detail, Map<String, Object> extensions,
            Map<String, String> headers) {
        Problem body = new Problem(code, status, detail, null, CorrelationId.current(), extensions);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatusCode.valueOf(status)).contentType(PROBLEM)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.forEach(builder::header);
        return builder.body(body);
    }

    private static String pointer(DatabindException e) {
        StringBuilder sb = new StringBuilder();
        e.getPath().forEach(ref -> {
            if (ref.getPropertyName() != null) {
                sb.append('/').append(safe(ref.getPropertyName()));
            } else if (ref.getIndex() >= 0) {
                sb.append('/').append(ref.getIndex());
            }
        });
        return sb.isEmpty() ? "/" : sb.toString();
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.length() > 64 ? value.substring(0, 64) : value;
        return trimmed.replaceAll("[^A-Za-z0-9_.-]", "?");
    }
}
