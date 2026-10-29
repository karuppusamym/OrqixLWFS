package com.orqix.apps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orqix.cases.CaseService;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/** S116: how a plan-item step picks among several available occurrences of its CMMN element. */
class ChainOccurrenceTest {
    private static CaseService.PlanItem item(String id, Instant created) {
        return new CaseService.PlanItem(id, "review", "Review", "humantask", "enabled", null, created, null, false,
                Map.of("start", true), null);
    }

    private static final List<CaseService.PlanItem> TWO = List.of(item("b", Instant.parse("2026-10-29T10:00:00Z")),
            item("a", Instant.parse("2026-10-29T11:00:00Z")));

    @Test
    void singleStopsOnSeveralAndTakesTheOnlyOne() {
        assertThat(ChainRunner.pick(TWO.subList(0, 1), "review", "single", null)).isEqualTo("b");
        assertThatThrownBy(() -> ChainRunner.pick(TWO, "review", "single", null)).isInstanceOf(ApiException.class)
                .hasMessageContaining("More than one review");
    }

    @Test
    void newestTakesTheMostRecentlyCreatedOccurrence() {
        assertThat(ChainRunner.pick(TWO, "review", "newest", null)).isEqualTo("a");
    }

    @Test
    void chooseTakesOnlyAnAvailableOccurrenceThePersonPicked() {
        assertThat(ChainRunner.pick(TWO, "review", "choose", "b")).isEqualTo("b");
        assertThatThrownBy(() -> ChainRunner.pick(TWO, "review", "choose", "elsewhere")).isInstanceOf(ApiException.class)
                .hasMessageContaining("Choose which review");
        assertThatThrownBy(() -> ChainRunner.pick(TWO, "review", "choose", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void noAvailableOccurrenceIsNotOpen() {
        assertThatThrownBy(() -> ChainRunner.pick(List.of(), "review", "newest", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCode.TASK_NOT_OPEN));
    }
}
