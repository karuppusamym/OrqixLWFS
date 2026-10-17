package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.orqix.ai.internal.ModelPolicy;

/** Routed-model check and circuit breaker of the model policy (S10 section 1), without Spring or a provider. */
class ModelPolicyTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T10:15:00Z"), ZoneOffset.UTC);
    private final AiProperties properties = new AiProperties("key", null, null, "economy", "auto", null, null, null, null,
            null, null, null, 2, 8000, null, null, null, null, null);
    private final ModelPolicy policy = new ModelPolicy(properties, null, null, clock);

    @Test
    void aRouterChoosingAnExcludedModelIsAViolationButItsOwnModelIsNot() {
        assertThat(policy.routedViolation("typesafe/jev-router", "anthropic/claude-opus-9"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("excluded model anthropic/claude-opus-9"));
        assertThat(policy.routedViolation("typesafe/jev-router", "openai/gpt-5-pro-2026"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("excluded"));
        assertThat(policy.routedViolation("typesafe/jev-router", "~anthropic/claude-fable-latest")).isPresent();
        assertThat(policy.routedViolation("typesafe/jev-router", "deepseek/deepseek-v4.1-flash")).isEmpty();
        assertThat(policy.routedViolation("typesafe/jev-router", "openai/gpt-6-luna")).isEmpty();
        assertThat(policy.routedViolation("deepseek/deepseek-v3.2", "deepseek/deepseek-v3.2")).isEmpty();
        assertThat(policy.routedViolation("typesafe/jev-router", null)).isEmpty();
    }

    @Test
    void theBreakerPausesTheProfileUntilTheNextUtcMidnight() {
        assertThat(policy.tripped("auto")).isEmpty();
        policy.tripBreaker("auto", "anthropic/claude-opus-9", "The router chose the excluded model anthropic/claude-opus-9.");
        ModelPolicy.Tripped trip = policy.tripped("auto").orElseThrow();
        assertThat(trip.until()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
        assertThat(trip.model()).isEqualTo("anthropic/claude-opus-9");
        assertThat(trip.reason()).contains("paused until 00:00 UTC");
        assertThat(policy.violation("auto")).hasValueSatisfying(reason -> assertThat(reason).contains("paused"));
        assertThat(policy.violation("economy")).isEmpty();
        assertThat(policy.trippedProfiles()).extracting(ModelPolicy.Tripped::profileId).containsExactly("auto");
        // The next day the in-memory trip has expired (no stored violation without a ledger).
        ModelPolicy tomorrow = new ModelPolicy(properties, null, null, Clock.fixed(Instant.parse("2026-10-10T00:00:01Z"),
                ZoneOffset.UTC));
        assertThat(tomorrow.tripped("auto")).isEmpty();
    }
}
