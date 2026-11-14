package com.orqix.integration;

/**
 * Why an OCR provider could not read a file (CG-A06). Classified from the HTTP status or the local engine's exit; the
 * message names the provider and the class of failure only, never a response body or file content.
 */
public final class OcrException extends RuntimeException {

    public enum Kind {
        /** The provider does not read this file type (or the local engine is not installed). */
        UNSUPPORTED,
        /** The file is over the provider's size or page limit. */
        TOO_LARGE,
        /** 401/403: the credentials were refused. */
        CREDENTIALS_REJECTED,
        /** 400/404/415/422: the service refused the request. */
        REJECTED,
        /** Timeout, 429, 5xx, a network error or an unreadable answer. */
        UNAVAILABLE,
        /** The deployment's egress rules do not allow the endpoint. */
        EGRESS_DENIED
    }

    private final Kind kind;
    private final int status;

    public OcrException(Kind kind, int status, String message) {
        super(message, null, false, false);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    /** A sentence for the decision record and the verifier (no provider internals). */
    public String reason() {
        return switch (kind) {
            case UNSUPPORTED -> "The OCR provider cannot read this file type.";
            case TOO_LARGE -> "The file is too large for the OCR provider.";
            case CREDENTIALS_REJECTED -> "The OCR provider rejected the credentials.";
            case REJECTED -> "The OCR provider refused the file.";
            case UNAVAILABLE -> "The OCR provider is unavailable.";
            case EGRESS_DENIED -> "The OCR endpoint is not allowed by the egress rules.";
        };
    }

    /** Classification shared by the HTTP adapters. */
    public static Kind classify(int status) {
        if (status == 401 || status == 403) {
            return Kind.CREDENTIALS_REJECTED;
        }
        if (status == 400 || status == 404 || status == 413 || status == 415 || status == 422) {
            return status == 413 ? Kind.TOO_LARGE : Kind.REJECTED;
        }
        return Kind.UNAVAILABLE;
    }
}
