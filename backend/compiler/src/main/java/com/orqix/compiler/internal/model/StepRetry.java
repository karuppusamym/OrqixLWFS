package com.orqix.compiler.internal.model;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A step's own retry policy (CG-W04, docs/build/S131): {@code config.retry} on http, externalTask and sendAdapter
 * steps. It overrides the workflow-wide {@code execution.maxAttempts} and {@code retryDelaySeconds} for that step only.
 * The next attempt waits {@code min(maxDelay, delay x multiplier^(attempt-1))} with 10 percent jitter; with
 * {@code honorRetryAfter} a 429 or 503 {@code Retry-After} is used instead when it is not longer than {@code maxDelay}.
 *
 * @param path        JSON Pointer of the {@code retry} object, for issues
 * @param delay       ISO-8601 text as written (hours, minutes and seconds)
 * @param maxDelay    ISO-8601 text as written; null means the same as {@code delay} times the multiplier cap
 */
public record StepRetry(String path, int maxAttempts, String delay, BigDecimal multiplier, String maxDelay,
                        boolean honorRetryAfter) {

    /** Shortest delay between attempts. */
    public static final long MIN_DELAY_SECONDS = 5;
    /** Longest first delay between attempts. */
    public static final long MAX_DELAY_SECONDS = 3600;
    /** Longest delay between attempts after backoff, and the cap of {@code Retry-After}. */
    public static final long MAX_MAX_DELAY_SECONDS = 6 * 3600;

    private static final Pattern FORMAT = Pattern.compile("^PT(?=\\d)(?:(\\d{1,2})H)?(?:(\\d{1,4})M)?(?:(\\d{1,5})S)?$");

    /** @return the length in seconds of a {@code PT..H..M..S} duration, or -1 when the text is not one */
    public static long seconds(String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = FORMAT.matcher(text);
        if (!m.matches()) {
            return -1;
        }
        long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long minutes = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long seconds = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        return hours * 3600 + minutes * 60 + seconds;
    }

    public long delaySeconds() {
        return seconds(delay);
    }

    /** The effective cap: {@code maxDelay} when set, otherwise six hours. */
    public long maxDelaySeconds() {
        return maxDelay == null ? MAX_MAX_DELAY_SECONDS : seconds(maxDelay);
    }

    /** Multiplier as plain text without trailing zeros, for the generated BPMN. */
    public String multiplierText() {
        return multiplier.stripTrailingZeros().toPlainString();
    }
}
