package com.orqix.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The {@code Retry-After} response header of a 429 or 503 (RFC 9110 section 10.2.3): either a number of seconds or an
 * HTTP date (CG-W04, docs/build/S131). Anything else is ignored. A date in the past means "now".
 */
public final class RetryAfter {

    /** Longer values are not useful for a step (the step cap is 6 hours) and are clamped here already. */
    static final Duration CEILING = Duration.ofDays(1);

    private RetryAfter() {
    }

    /** @return the delay the server asked for, or null when the header is missing or not valid */
    public static Duration parse(String header, Instant now) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.isEmpty() || value.length() > 64) {
            return null;
        }
        if (value.chars().allMatch(Character::isDigit)) {
            if (value.length() > 9) {
                return CEILING;
            }
            Duration seconds = Duration.ofSeconds(Long.parseLong(value));
            return seconds.compareTo(CEILING) > 0 ? CEILING : seconds;
        }
        try {
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(now, at);
            if (delay.isNegative()) {
                return Duration.ZERO;
            }
            return delay.compareTo(CEILING) > 0 ? CEILING : delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
