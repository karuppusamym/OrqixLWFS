package com.orqix.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.orqix.insights.internal.InsightsCache;

/** S92 F2: finished figures are shared for a short time, never when switched off, and one calculation serves a crowd. */
class InsightsCacheTest {

    @Test
    void aFigureIsComputedOnceWithinTheTimeToLiveAndAgainAfterIt() throws Exception {
        InsightsCache cache = new InsightsCache(Duration.ofMillis(300));
        AtomicInteger runs = new AtomicInteger();
        assertThat(cache.<Integer>get("a", runs::incrementAndGet)).isEqualTo(1);
        assertThat(cache.<Integer>get("a", runs::incrementAndGet)).isEqualTo(1);
        assertThat(cache.<Integer>get("b", runs::incrementAndGet)).isEqualTo(2);
        Thread.sleep(400);
        assertThat(cache.<Integer>get("a", runs::incrementAndGet)).isEqualTo(3);
    }

    @Test
    void aTimeToLiveOfZeroComputesEveryTime() {
        InsightsCache cache = new InsightsCache(Duration.ZERO);
        AtomicInteger runs = new AtomicInteger();
        cache.<Integer>get("a", runs::incrementAndGet);
        cache.<Integer>get("a", runs::incrementAndGet);
        assertThat(runs).hasValue(2);
    }

    @Test
    void aFailedCalculationIsNotKept() {
        InsightsCache cache = new InsightsCache(Duration.ofSeconds(30));
        AtomicInteger runs = new AtomicInteger();
        try {
            cache.<Integer>get("a", () -> {
                runs.incrementAndGet();
                throw new IllegalStateException("too slow");
            });
        } catch (IllegalStateException expected) {
            // the next caller tries again
        }
        assertThat(cache.<Integer>get("a", runs::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void manyCallersForOneKeyShareASingleCalculation() throws Exception {
        InsightsCache cache = new InsightsCache(Duration.ofSeconds(30));
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return cache.<Integer>get("same", () -> {
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return runs.incrementAndGet();
                    });
                }));
            }
            go.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get()).isEqualTo(1);
            }
            assertThat(runs).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
