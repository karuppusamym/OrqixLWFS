package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.orqix.integration.AiTaskPort;
import com.orqix.runtime.engine.EngineCommands;

import jakarta.annotation.PreDestroy;

/**
 * Runs AI tasks (CG-A05, docs/build/S134 section 5): claims queued jobs while it has free threads, runs the guardrail
 * pipeline outside any transaction with a lease it renews between turns, and completes the job. A busy engine is
 * retried a few times; when that does not help the lease expires and the job is retried from its turn state. Expired
 * leases and deadlines are recovered every 15 seconds.
 */
@Component
public class AiTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(AiTaskWorker.class);

    private final AiTaskJobs jobs;
    private final AiTaskPort ai;
    private final AiTaskQueueSettings settings;
    private final Clock clock;
    private final String owner = "ai-" + UUID.randomUUID().toString().substring(0, 8);
    private final Semaphore free;
    private final ExecutorService executor;

    public AiTaskWorker(AiTaskJobs jobs, AiTaskPort ai, AiTaskQueueSettings settings, Clock clock) {
        this.jobs = jobs;
        this.ai = ai;
        this.settings = settings;
        this.clock = clock;
        this.free = new Semaphore(settings.threads());
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("orqix-ai-task-", 0).factory());
    }

    @Scheduled(fixedDelayString = "${orqix.ai.tasks.poll-interval:500ms}", initialDelayString = "PT5S")
    public void poll() {
        if (!settings.workerEnabled()) {
            return;
        }
        while (free.tryAcquire()) {
            Optional<AiTaskJobs.Claimed> claimed;
            try {
                claimed = jobs.claim(owner);
            } catch (RuntimeException e) {
                free.release();
                log.warn("AI task claim failed: {}", e.getClass().getSimpleName());
                return;
            }
            if (claimed.isEmpty()) {
                free.release();
                return;
            }
            AiTaskJobs.Claimed job = claimed.get();
            executor.submit(() -> {
                try {
                    process(job);
                } finally {
                    free.release();
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${orqix.ai.tasks.recover-interval:15s}", initialDelayString = "PT10S")
    public void recover() {
        if (!settings.workerEnabled()) {
            return;
        }
        try {
            for (AiTaskJobs.Claimed job : jobs.recoverExpired(owner)) {
                AiTaskPort.TaskRequest request = request(job);
                finish(job, ai.giveUp(request, job.deadline().isAfter(clock.instant())
                        ? "The AI task stopped too often and was given up." : "The AI task reached its deadline."));
            }
        } catch (RuntimeException e) {
            log.warn("AI task recovery failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Runs one claimed job to completion (visible for tests). */
    public void process(AiTaskJobs.Claimed job) {
        AiTaskPort.TaskRequest request;
        try {
            request = jobs.request(job);
        } catch (RuntimeException e) {
            // The sealed input cannot be opened (another pod's key): the task cannot run, a person takes over.
            AiTaskPort.TaskRequest empty = request(job);
            finish(job, ai.giveUp(empty, "The AI task input could not be opened."));
            return;
        }
        long renewEveryMs = settings.leaseSeconds() * 1000L / 3;
        long[] lastRenew = {System.currentTimeMillis()};
        AiTaskPort.Lease lease = new AiTaskPort.Lease() {
            @Override
            public boolean renew() {
                if (System.currentTimeMillis() - lastRenew[0] < renewEveryMs) {
                    return true;
                }
                boolean held = jobs.renew(job);
                lastRenew[0] = System.currentTimeMillis();
                return held;
            }

            @Override
            public Instant deadline() {
                return job.deadline();
            }
        };
        AiTaskPort.Outcome outcome;
        try {
            outcome = ai.run(request, lease);
        } catch (RuntimeException e) {
            log.warn("AI task pipeline failed: {}", e.getClass().getSimpleName(), e);
            outcome = ai.giveUp(request, "The AI task failed unexpectedly.");
        }
        if (outcome.route() == null) {
            return; // lease lost: another worker owns the job
        }
        finish(job, outcome);
    }

    private void finish(AiTaskJobs.Claimed job, AiTaskPort.Outcome outcome) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                jobs.complete(job, outcome);
                return;
            } catch (EngineCommands.EngineConflictException | org.springframework.dao.ConcurrencyFailureException e) {
                sleep(200L * (attempt + 1));
            } catch (RuntimeException e) {
                log.warn("AI task completion failed: {}", e.getClass().getSimpleName(), e);
                return;
            }
        }
        log.warn("AI task {} could not be completed: the request stayed busy; the lease will expire and it retries.",
                job.id());
    }

    private AiTaskPort.TaskRequest request(AiTaskJobs.Claimed job) {
        try {
            return jobs.request(job);
        } catch (RuntimeException e) {
            return jobs.requestWithoutInput(job);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
