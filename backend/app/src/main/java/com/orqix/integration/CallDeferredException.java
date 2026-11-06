package com.orqix.integration;

import java.time.Instant;

/**
 * The call was not made because the binding's circuit is open or its request rate is used up (CG-W04, S131). Nothing
 * was sent and no effect attempt was registered. The failed-job command reschedules the job to {@code until} without
 * using one of the step's attempts.
 */
public final class CallDeferredException extends RuntimeException {

    /** Why the call waits: {@code CIRCUIT_OPEN}, {@code CIRCUIT_TRIAL_RUNNING} or {@code RATE_LIMITED}. */
    private final String reason;
    private final String connectionKey;
    private final Instant until;

    public CallDeferredException(String reason, String connectionKey, Instant until) {
        super("Call deferred " + reason + " for connection " + connectionKey + " until " + until, null, false, false);
        this.reason = reason;
        this.connectionKey = connectionKey;
        this.until = until;
    }

    public String reason() {
        return reason;
    }

    public String connectionKey() {
        return connectionKey;
    }

    public Instant until() {
        return until;
    }

    public static CallDeferredException find(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof CallDeferredException deferred) {
                return deferred;
            }
        }
        return null;
    }
}
