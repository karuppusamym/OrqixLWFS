package com.orqix.compiler.internal.bpmn;

/** The single source of the Flowable retry cycle for asynchronous http tasks (S4 section 6). */
public final class RetryCycle {

    private RetryCycle() {
    }

    /**
     * Builds the ISO 8601 repeating interval for {@code flowable:failedJobRetryTimeCycle}.
     * <p>
     * Assumption: Flowable treats {@code R} as the total number of attempts; confirmed or corrected by spike S-4 in
     * the app module. If S-4 shows that {@code R} counts retries after the first attempt, this function (and only
     * this function) changes to {@code maxAttempts - 1}, and the compiler version is bumped.
     *
     * @param maxAttempts       total attempts of the external call (1..5)
     * @param retryDelaySeconds delay between attempts (5..3600)
     * @return for example {@code R3/PT30S}
     */
    public static String forAttempts(int maxAttempts, int retryDelaySeconds) {
        if (maxAttempts < 1 || retryDelaySeconds < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 1 and retryDelaySeconds >= 0");
        }
        return "R" + maxAttempts + "/PT" + retryDelaySeconds + "S";
    }
}
