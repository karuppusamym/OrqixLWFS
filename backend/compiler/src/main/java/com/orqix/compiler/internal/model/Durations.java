package com.orqix.compiler.internal.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ISO-8601 duration subset of workflows (S12 section 3): days, hours and minutes, for example {@code PT1M},
 * {@code PT48H}, {@code P2D}, {@code P1DT12H}. Years, months, weeks and seconds are not accepted because their length
 * is ambiguous or too fine for a business wait. The same pattern is in {@code workflow-v0.schema.json}.
 */
public final class Durations {

    /** Shortest wait or time limit: one minute. */
    public static final long MIN_MINUTES = 1;
    /** Longest wait or time limit: 90 days. */
    public static final long MAX_MINUTES = 90L * 24 * 60;

    private static final Pattern FORMAT = Pattern.compile("^P(?=\\d|T\\d)(?:(\\d{1,3})D)?(?:T(?=\\d)(?:(\\d{1,4})H)?(?:(\\d{1,5})M)?)?$");

    private Durations() {
    }

    /** @return the length in minutes, or -1 when the text is not a duration of the supported subset */
    public static long minutes(String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = FORMAT.matcher(text);
        if (!m.matches()) {
            return -1;
        }
        long days = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long hours = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long minutes = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        return days * 24 * 60 + hours * 60 + minutes;
    }

    /** @return plain-language length such as "2 days", "1 day 12 hours" or "1 minute" */
    public static String describe(long totalMinutes) {
        long days = totalMinutes / (24 * 60);
        long hours = totalMinutes % (24 * 60) / 60;
        long minutes = totalMinutes % 60;
        StringBuilder sb = new StringBuilder();
        append(sb, days, "day");
        append(sb, hours, "hour");
        append(sb, minutes, "minute");
        return sb.length() == 0 ? "0 minutes" : sb.toString();
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
