package com.orqix.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.catalog.internal.SimulationLimiter;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/** The dry run limit: 30 runs a minute per person, then 429 RATE_LIMITED with Retry-After. */
class SimulationLimiterTest {

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void the31stRunInAMinuteIsRefusedAndTheWindowSlides() {
        MovableClock clock = new MovableClock();
        SimulationLimiter limiter = new SimulationLimiter(clock);
        UUID person = UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            limiter.check(person);
        }
        assertThatThrownBy(() -> limiter.check(person)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ProblemCode.RATE_LIMITED);
            assertThat(e.headers()).containsKey("Retry-After");
        });
        limiter.check(UUID.randomUUID());
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        limiter.check(person);
    }
    @Test
    void idleIdentitiesAreEvictedAndActiveIdentityCountIsBounded() {
        MovableClock clock=new MovableClock();
        SimulationLimiter limiter=new SimulationLimiter(clock);
        for(int i=0;i<10000;i++) limiter.check(new UUID(1,i));
        assertThatThrownBy(() -> limiter.check(new UUID(2,0))).isInstanceOf(ApiException.class);
        clock.now=clock.now.plusSeconds(61);
        limiter.check(new UUID(2,0));
    }
}
