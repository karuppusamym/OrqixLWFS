package com.orqix.sdk;

import java.util.Optional;

/**
 * The server refused a call. {@link #code()} is the problem code of the RFC 9457 answer, for example
 * {@code JOB_LEASE_LOST}; {@code HTTP_ERROR} when the answer had none, {@code TRANSPORT_ERROR} when no answer arrived.
 */
public class OrqixApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String detail;
    private final String correlationId;
    private final Long retryAfterSeconds;

    public OrqixApiException(int status, String code, String detail, String correlationId, Long retryAfterSeconds) {
        super(status + " " + code + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.correlationId = correlationId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static OrqixApiException transport(Exception cause) {
        OrqixApiException e = new OrqixApiException(0, "TRANSPORT_ERROR", cause.getClass().getSimpleName(), null, null);
        e.initCause(cause);
        return e;
    }

    /** HTTP status; 0 when no answer arrived. */
    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    /** Quote this to an Orqix operator: it finds the server log entries of the call. */
    public Optional<String> correlationId() {
        return Optional.ofNullable(correlationId);
    }

    /** The server's {@code Retry-After} in seconds, for 429 and 503 answers. */
    public Optional<Long> retryAfterSeconds() {
        return Optional.ofNullable(retryAfterSeconds);
    }

    /** True for answers worth repeating with the same idempotency key: no answer, 429, 502, 503 or 504. */
    public boolean isTransient() {
        return status == 0 || status == 429 || status == 502 || status == 503 || status == 504;
    }

    /** True when the server no longer accepts this caller: 401 or 403. */
    public boolean isAuthenticationFailure() {
        return status == 401 || status == 403;
    }
}
