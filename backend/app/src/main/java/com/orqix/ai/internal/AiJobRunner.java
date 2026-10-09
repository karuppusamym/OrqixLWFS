package com.orqix.ai.internal;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.orqix.ai.AiProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Bounded executor for async draft generations (S10 section 3) and the reaper of orphaned jobs. Generations are
 * blocking provider calls, so threads and queue are capped per pod ({@code orqix.ai.jobs}); work beyond that is refused
 * up front with 429 instead of piling up. The job state itself lives in the database ({@link AiJobStore}).
 */
@Component
public class AiJobRunner {

    private static final Logger log = LoggerFactory.getLogger(AiJobRunner.class);

    private final AiJobStore store;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(daemon("orqix-ai-reaper"));

    public AiJobRunner(AiProperties properties, AiJobStore store) {
        this.store = store;
        AiProperties.Jobs jobs = properties.jobs();
        this.executor = new ThreadPoolExecutor(jobs.threads(), jobs.threads(), 30, TimeUnit.SECONDS,
                jobs.queue() == 0 ? new java.util.concurrent.SynchronousQueue<>() : new ArrayBlockingQueue<>(jobs.queue()), daemon("orqix-ai-gen"),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @PostConstruct
    void startReaper() {
        reaper.scheduleWithFixedDelay(() -> {
            try {
                int failed = store.expireOrphans();
                store.purgeExpired();
                if (failed > 0) {
                    log.warn("AI generation reaper failed {} orphaned job(s) (deadline passed)", failed);
                }
            } catch (RuntimeException e) {
                log.debug("AI generation reaper skipped: {}", e.getClass().getSimpleName());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        reaper.shutdownNow();
        executor.shutdownNow();
    }

    /** True when another job can be accepted without waiting behind a full queue. */
    public boolean hasCapacity() {
        return executor.getQueue().remainingCapacity() > 0 || executor.getActiveCount() < executor.getMaximumPoolSize();
    }

    /** @throws RejectedExecutionException when threads and queue are full */
    public void submit(Runnable job) {
        executor.execute(job);
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
