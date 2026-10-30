package com.orqix.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.support.MutableClock;

/** S104: the per-token sliding window for callback polls and resumes. */
class CallbackRateLimiterTest {

    @Test
    void aTokenGetsItsLimitPerMinuteThenRetryAfterAndTheWindowSlides() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-28T10:00:00Z"));
        CallbackRateLimiter limiter = new CallbackRateLimiter(clock, 3);
        limiter.check("a");
        clock.set(clock.instant().plusSeconds(20));
        limiter.check("a");
        limiter.check("a");
        assertThatThrownBy(() -> limiter.check("a")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ProblemCode.RATE_LIMITED);
            assertThat(e.headers()).containsEntry("Retry-After", "40");
        });
        limiter.check("b"); // another token has its own window
        clock.set(clock.instant().plus(Duration.ofSeconds(40)));
        limiter.check("a"); // the first call left the window
        assertThatThrownBy(() -> limiter.check("a")).isInstanceOf(ApiException.class);
    }

    @Test
    void settingsRejectValuesOutsideTheirBounds() {
        assertThatThrownBy(() -> new CallbackSettings(Duration.ofMinutes(5), 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallbackSettings(Duration.ofDays(30), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CallbackSettings(Duration.ofDays(30), 60).requestsPerMinute()).isEqualTo(60);
    }
}
