package com.orqix.sdk.worker;

/**
 * Thrown by a handler to report a technical failure. Retryable by default: the job returns to the queue after
 * {@code retryAfterSeconds} (or the workflow's retry delay) until the attempt limit. {@code retryable = false} fails
 * the job for good and the request needs attention.
 */
public class JobFailedException extends Exception {

    private final boolean retryable;
    private final Integer retryAfterSeconds;

    public JobFailedException(String message) {
        this(message, true, null);
    }

    public JobFailedException(String message, boolean retryable) {
        this(message, retryable, null);
    }

    public JobFailedException(String message, boolean retryable, Integer retryAfterSeconds) {
        super(message);
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public boolean retryable() {
        return retryable;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
