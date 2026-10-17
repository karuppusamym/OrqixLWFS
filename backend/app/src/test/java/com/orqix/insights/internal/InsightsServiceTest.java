package com.orqix.insights.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.orqix.shared.problem.ApiException;

/** The parts of the insights service that need no database. */
class InsightsServiceTest {

    @Test
    void aStatementTimeoutBecomesServiceUnavailableWithRetryAdvice() {
        assertThatThrownBy(() -> InsightsService.guard(() -> {
            throw new QueryTimeoutException("canceling statement due to statement timeout");
        })).isInstanceOf(ApiException.class).hasMessageContaining("DEPENDENCY_UNAVAILABLE")
                .satisfies(e -> assertThat(((ApiException) e).headers()).containsEntry("Retry-After", "30"));
    }

    @Test
    void resultsPassThrough() {
        assertThat(InsightsService.guard(() -> 42)).isEqualTo(42);
    }

    @Test
    void shareIsAFractionOfTheWholeAndNeverAboveOne() {
        assertThat(InsightsService.share(1, 3)).isEqualTo(0.3333);
        assertThat(InsightsService.share(0, 0)).isZero();
        assertThat(InsightsService.share(5, 4)).isEqualTo(1.0);
    }
}
