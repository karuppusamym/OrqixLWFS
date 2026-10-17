package com.orqix.ai.internal;

/**
 * Typed failure of a provider call. Never carries request or response text (descriptions and model output are not
 * logged); {@link #getMessage()} is safe for logs.
 */
public class ProviderException extends RuntimeException {

    /** Failure classes that drive the API mapping (S10 section 3, provider errors). */
    public enum Kind {
        /** 429, 5xx, 408, timeouts, connection failures, provider error objects: 503 AI_PROVIDER_UNAVAILABLE. */
        UNAVAILABLE,
        /** 401/403: AI reported as disabled with reason "credentials rejected". */
        CREDENTIALS_REJECTED,
        /** 402: the provider account has no credit left. */
        PAYMENT_REQUIRED,
        /** 400/404/422: the request was refused (unsupported parameter, unknown model). */
        REJECTED
    }

    private final Kind kind;
    private final int status;
    private final Integer retryAfterSeconds;

    public ProviderException(Kind kind, int status, Integer retryAfterSeconds, String message) {
        super(message, null, false, false);
        this.kind = kind;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status, or 0 when no response was received. */
    public int status() {
        return status;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** True when no answer arrived within the call's timeout. */
    public boolean timedOut() {
        return kind == Kind.UNAVAILABLE && status == 0 && getMessage() != null && getMessage().endsWith(": timeout");
    }
}
