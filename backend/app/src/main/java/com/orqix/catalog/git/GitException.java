package com.orqix.catalog.git;

/**
 * A Git provider call failed. The message names the provider, the step and the HTTP status only; it never carries a
 * token or a response body. {@code status} is the provider's HTTP status (0 for a transport failure).
 */
public class GitException extends RuntimeException {

    private final int status;

    public GitException(String message, int status) {
        super(message, null, false, false);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** The branch moved since it was read (a concurrent push), or the provider refused a non-fast-forward update. */
    public boolean conflict() {
        return status == 409 || status == 412;
    }

    public boolean notFound() {
        return status == 404;
    }
}
