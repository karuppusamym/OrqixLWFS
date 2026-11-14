package com.orqix.compiler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Timing of a "Keep checking until..." step (docs/build/S129, CG-W01), shared by the compiler (bounds and the summary
 * shown in Studio), the simulator and the runtime scheduler so all three agree on when checks happen.
 *
 * <p>Interval {@code i} (0-based) is {@code min(maxInterval, every x multiplier^i)}. With an immediate first check the
 * checks are at 0, +interval0, +interval1, ...; otherwise the first check is at interval0. Every check is capped at
 * the deadline, and with {@code finalCheckAtDeadline} one last check runs exactly at the deadline. Jitter is applied
 * by the runtime only; the schedule here is the jitter-free plan.
 *
 * @param every         seconds before the second check
 * @param multiplier    backoff factor, 1 for a constant interval
 * @param maxInterval   cap of an interval in seconds (equals {@code every} without backoff)
 * @param deadline      seconds from the step start until it gives up
 * @param immediate     true when the first check runs when the step starts
 * @param finalCheck    true for one last check exactly at the deadline
 * @param maxChecks     hard cap on the number of checks
 */
public record PollSchedule(long every, BigDecimal multiplier, long maxInterval, long deadline, boolean immediate,
        boolean finalCheck, int maxChecks) {

    /** Highest number of checks one step occurrence may make (WF_POLL_TOO_MANY_CHECKS). */
    public static final int CHECK_LIMIT = 1000;
    /** Shortest interval of a check-until step (WF_POLL_INTERVAL). */
    public static final long MIN_EVERY = 10;
    /** Shortest interval of a safety-net check of a message wait (WF_POLL_INTERVAL). */
    public static final long MIN_FALLBACK_EVERY = 60;
    /** Longest interval, and longest backoff cap. */
    public static final long MAX_INTERVAL = 86_400;
    /** Shortest and longest deadline. */
    public static final long MIN_DEADLINE = 60;
    public static final long MAX_DEADLINE = 90L * 86_400;

    private static final Pattern FORMAT = Pattern.compile(
            "^P(?=\\d|T\\d)(\\d{1,3}D)?(T(?=\\d)(\\d{1,4}H)?(\\d{1,5}M)?(\\d{1,6}S)?)?$");

    /** @return the length of an ISO-8601 poll duration (days to seconds) in seconds, or -1 when it is not one */
    public static long seconds(String text) {
        if (text == null || !FORMAT.matcher(text).matches()) {
            return -1;
        }
        return Duration.parse(text).getSeconds();
    }

    /** @return the interval in seconds after the check with this 1-based number (no jitter) */
    public long intervalAfter(int check) {
        int index = immediate ? check - 1 : check;
        return interval(index);
    }

    /** @return interval {@code i} (0-based) in seconds */
    public long interval(int i) {
        if (multiplier.compareTo(BigDecimal.ONE) <= 0) {
            return Math.min(every, maxInterval);
        }
        BigDecimal value = BigDecimal.valueOf(every);
        BigDecimal cap = BigDecimal.valueOf(maxInterval);
        for (int k = 0; k < i && value.compareTo(cap) < 0; k++) {
            value = value.multiply(multiplier);
        }
        return value.min(cap).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** @return seconds after the step start of the first check */
    public long firstCheckOffset() {
        return immediate ? 0 : Math.min(interval(0), deadline);
    }

    /**
     * The jitter-free plan, capped by {@code limit} entries.
     *
     * @return seconds after the step start of each check, in order
     */
    public List<Long> offsets(int limit) {
        List<Long> out = new ArrayList<>();
        long at = firstCheckOffset();
        int check = 0;
        while (out.size() < limit) {
            if (at >= deadline) {
                if (finalCheck && (out.isEmpty() || out.get(out.size() - 1) < deadline)) {
                    out.add(deadline);
                }
                break;
            }
            out.add(at);
            check++;
            at += intervalAfter(check);
        }
        return out;
    }

    /** @return the worst-case number of checks from the timing alone (may exceed {@link #CHECK_LIMIT}) */
    public static int worstCase(long every, BigDecimal multiplier, long maxInterval, long deadline, boolean immediate,
            boolean finalCheck) {
        PollSchedule s = new PollSchedule(every, multiplier, maxInterval, deadline, immediate, finalCheck, Integer.MAX_VALUE);
        return s.offsets(CHECK_LIMIT + 1).size();
    }

    /** @return the number of checks this step makes at most: the timing, capped by {@code maxChecks} */
    public int plannedChecks() {
        return offsets(Math.max(1, maxChecks)).size();
    }

    /** @return seconds after the step start from which the interval stays at its cap, or -1 without backoff */
    public long cappedFrom() {
        if (multiplier.compareTo(BigDecimal.ONE) <= 0 || every >= maxInterval) {
            return -1;
        }
        long at = firstCheckOffset();
        int check = 0;
        while (at < deadline) {
            check++;
            if (intervalAfter(check) >= maxInterval) {
                return at;
            }
            at += intervalAfter(check);
        }
        return -1;
    }

    /**
     * Plain-language summary shown before publishing, for example: "Checks up to 52 times over 2 days; from 2 hours 15
     * minutes on, once an hour. With 500 requests waiting this is about 500 calls an hour to ServiceNow."
     */
    public String summary(String systemName, int waitingRequests) {
        int checks = plannedChecks();
        StringBuilder sb = new StringBuilder("Checks up to ").append(checks).append(checks == 1 ? " time" : " times")
                .append(" over ").append(describe(deadline));
        long capped = cappedFrom();
        if (capped > 0) {
            sb.append("; from ").append(describe(capped)).append(" on, ").append(once(maxInterval));
        } else if (capped == 0 || multiplier.compareTo(BigDecimal.ONE) <= 0 || every >= maxInterval) {
            sb.append("; ").append(once(Math.min(every, maxInterval)));
        }
        sb.append('.');
        if (waitingRequests > 0) {
            double perHour = waitingRequests * 3600.0 / maxInterval;
            sb.append(" With ").append(waitingRequests).append(waitingRequests == 1 ? " request" : " requests")
                    .append(" waiting this is about ").append(rate(perHour)).append(" to ").append(systemName).append('.');
        }
        return sb.toString();
    }

    private static String once(long seconds) {
        return switch ((int) Math.min(seconds, Integer.MAX_VALUE)) {
            case 60 -> "once a minute";
            case 3600 -> "once an hour";
            case 86_400 -> "once a day";
            default -> "every " + describe(seconds);
        };
    }

    private static String rate(double perHour) {
        if (perHour >= 1) {
            long n = Math.round(perHour);
            return n + (n == 1 ? " call an hour" : " calls an hour");
        }
        long perDay = Math.max(1, Math.round(perHour * 24));
        return perDay + (perDay == 1 ? " call a day" : " calls a day");
    }

    /** @return plain-language length such as "2 days", "1 hour 15 minutes" or "30 seconds" */
    public static String describe(long seconds) {
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        append(sb, days, "day");
        append(sb, hours, "hour");
        append(sb, minutes, "minute");
        append(sb, secs, "second");
        return sb.length() == 0 ? "0 seconds" : sb.toString();
    }

    private static void append(StringBuilder sb, long value, String unit) {
        if (value == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value).append(' ').append(unit).append(value == 1 ? "" : "s");
    }
}
