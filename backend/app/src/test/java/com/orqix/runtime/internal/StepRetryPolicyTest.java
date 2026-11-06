package com.orqix.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

import com.orqix.integration.RetryAfter;

/** CG-W04 (docs/build/S131): the backoff formula, its jitter and cap, and Retry-After parsing. */
class StepRetryPolicyTest {

    private static final StepRetryPolicy POLICY = StepRetryPolicy.of("6", "PT10S", "3", "PT30M", null);

    @Test
    void backoffGrowsByTheMultiplierUpToMaxDelay() {
        assertThat(POLICY.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(POLICY.backoff(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(POLICY.backoff(3)).isEqualTo(Duration.ofSeconds(90));
        assertThat(POLICY.backoff(4)).isEqualTo(Duration.ofSeconds(270));
        assertThat(POLICY.backoff(5)).isEqualTo(Duration.ofSeconds(810));
        assertThat(POLICY.backoff(6)).isEqualTo(Duration.ofMinutes(30));
        assertThat(POLICY.backoff(500)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void jitterIsTenPercentAndNeverPassesMaxDelay() {
        assertThat(POLICY.nextDelay(1, null, () -> 0.0)).isEqualTo(Duration.ofSeconds(9));
        assertThat(POLICY.nextDelay(1, null, () -> 0.5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(POLICY.nextDelay(1, null, () -> 0.999999)).isBetween(Duration.ofMillis(10_999), Duration.ofSeconds(11));
        assertThat(POLICY.nextDelay(6, null, () -> 0.999999)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void retryAfterReplacesTheBackoffAndIsCappedUnlessIgnored() {
        assertThat(POLICY.nextDelay(1, Duration.ofSeconds(45), () -> 0.0)).isEqualTo(Duration.ofSeconds(45));
        assertThat(POLICY.nextDelay(1, Duration.ofHours(2), () -> 0.0)).isEqualTo(Duration.ofMinutes(30));
        StepRetryPolicy ignoring = StepRetryPolicy.of("3", "PT10S", null, null, "false");
        assertThat(ignoring.nextDelay(1, Duration.ofSeconds(45), () -> 0.5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(ignoring.maxDelay()).isEqualTo(Duration.ofHours(6));
        assertThat(ignoring.multiplier()).isEqualByComparingTo("1");
    }

    @Test
    void retryAfterIsSecondsOrAnHttpDate() {
        Instant now = Instant.parse("2026-11-05T10:00:00Z");
        assertThat(RetryAfter.parse("120", now)).isEqualTo(Duration.ofSeconds(120));
        assertThat(RetryAfter.parse(" 0 ", now)).isEqualTo(Duration.ZERO);
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(90).atOffset(ZoneOffset.UTC));
        assertThat(RetryAfter.parse(date, now)).isEqualTo(Duration.ofSeconds(90));
        assertThat(RetryAfter.parse("Wed, 21 Oct 2015 07:28:00 GMT", now)).as("a date in the past").isEqualTo(Duration.ZERO);
        assertThat(RetryAfter.parse("99999999999", now)).isEqualTo(Duration.ofDays(1));
        assertThat(RetryAfter.parse("-5", now)).isNull();
        assertThat(RetryAfter.parse("soon", now)).isNull();
        assertThat(RetryAfter.parse(null, now)).isNull();
    }
}
