package com.orqix.runtime.internal;

import java.util.UUID;

/**
 * Failure thrown by an Orqix job delegate. Carries the classification so the failed-job command can decide between
 * retry and dead-letter (S5 section 6.4) and describe it without parsing messages. The message is a redacted code
 * (never a body, header or credential) and is never null, because Flowable treats a null exception message as "first
 * failure" when applying the retry cycle.
 */
public class JobFailure extends RuntimeException {

    private final boolean retryable;
    private final String attentionReason;
    private final String errorCode;
    private final String classification;
    private final Integer httpStatus;
    private final int attempt;
    private final UUID effectId;
    private final String businessMessage;
    /** The server's {@code Retry-After} for a 429 or 503 (CG-W04, S131); set before the failure is thrown. */
    private java.time.Duration retryAfter;

    public JobFailure(boolean retryable, String attentionReason, String errorCode, String classification,
            Integer httpStatus, int attempt, UUID effectId, String businessMessage) {
        super("Orqix job failure " + errorCode + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")"), null, false, false);
        this.retryable = retryable;
        this.attentionReason = attentionReason;
        this.errorCode = errorCode;
        this.classification = classification;
        this.httpStatus = httpStatus;
        this.attempt = attempt;
        this.effectId = effectId;
        this.businessMessage = businessMessage;
    }

    public boolean retryable() {
        return retryable;
    }

    /** Attention reason when this failure ends automatic progress (S5 section 6.3). */
    public String attentionReason() {
        return attentionReason;
    }

    public String errorCode() {
        return errorCode;
    }

    /** retryable, permanent or unknown. */
    public String classification() {
        return classification;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public int attempt() {
        return attempt;
    }

    public UUID effectId() {
        return effectId;
    }

    public String businessMessage() {
        return businessMessage;
    }

    /** @return the server's requested delay before the next attempt, or null */
    public java.time.Duration retryAfter() {
        return retryAfter;
    }

    /** Carries the server's {@code Retry-After} to the failed-job command (S131); returns this failure. */
    public JobFailure withRetryAfter(java.time.Duration value) {
        this.retryAfter = value;
        return this;
    }

    public static JobFailure find(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof JobFailure failure) {
                return failure;
            }
        }
        return null;
    }
}
