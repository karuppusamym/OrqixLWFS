package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * A step's own retry policy at run time (CG-W04, docs/build/S131), as the compiler wrote it to {@code <orqix:retry>}.
 * Attempt {@code n} (1 for the first failure) waits {@code min(maxDelay, delay x multiplier^(n-1))} with plus or minus
 * 10 percent jitter, never more than {@code maxDelay}. With {@code honorRetryAfter} a server's {@code Retry-After} is
 * used as it is when it is not longer than {@code maxDelay}, and {@code maxDelay} otherwise.
 */
public record StepRetryPolicy(int maxAttempts, Duration delay, BigDecimal multiplier, Duration maxDelay,
        boolean honorRetryAfter) {

    /** The cap when the step sets no {@code maxDelay}: 6 hours, as the compiler bounds it. */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofHours(6);
    static final double JITTER = 0.10;

    public StepRetryPolicy {
        if (maxAttempts < 1 || delay == null || delay.isNegative() || multiplier == null
                || multiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("Invalid step retry policy");
        }
        if (maxDelay == null) {
            maxDelay = DEFAULT_MAX_DELAY;
        }
    }

    /** Parses the attributes of {@code <orqix:retry>}; null when the step has none. */
    public static StepRetryPolicy of(String maxAttempts, String delay, String multiplier, String maxDelay,
            String honorRetryAfter) {
        if (maxAttempts == null || delay == null) {
            return null;
        }
        return new StepRetryPolicy(Integer.parseInt(maxAttempts), Duration.parse(delay),
                multiplier == null ? BigDecimal.ONE : new BigDecimal(multiplier),
                maxDelay == null ? null : Duration.parse(maxDelay), !"false".equals(honorRetryAfter));
    }

    /** The backoff before the next attempt after failed attempt {@code attempt} (1-based), without jitter. */
    public Duration backoff(int attempt) {
        double factor = Math.pow(multiplier.doubleValue(), Math.max(0, attempt - 1));
        double millis = delay.toMillis() * factor;
        if (!Double.isFinite(millis) || millis >= maxDelay.toMillis()) {
            return maxDelay;
        }
        return Duration.ofMillis(Math.round(millis));
    }

    /**
     * The delay before the next attempt.
     *
     * @param attempt    the attempt that just failed, 1-based
     * @param retryAfter the server's {@code Retry-After}, or null
     * @param random     uniform in [0, 1), for the jitter
     */
    public Duration nextDelay(int attempt, Duration retryAfter, DoubleSupplier random) {
        if (honorRetryAfter && retryAfter != null) {
            return retryAfter.compareTo(maxDelay) <= 0 ? retryAfter : maxDelay;
        }
        Duration base = backoff(attempt);
        double jitter = 1 + JITTER * (2 * random.getAsDouble() - 1);
        long millis = Math.round(base.toMillis() * jitter);
        return Duration.ofMillis(Math.min(maxDelay.toMillis(), Math.max(0, millis)));
    }
}
