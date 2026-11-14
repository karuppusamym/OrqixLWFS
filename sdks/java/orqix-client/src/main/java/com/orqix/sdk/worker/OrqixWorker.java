package com.orqix.sdk.worker;

import com.orqix.sdk.OrqixApiException;
import com.orqix.sdk.OrqixClient;
import com.orqix.sdk.model.ExternalJob;
import com.orqix.sdk.model.ExternalJobAwaitCallbackRequest;
import com.orqix.sdk.model.ExternalJobCallbackFailure;
import com.orqix.sdk.model.ExternalJobCallbackRegistration;
import com.orqix.sdk.model.ExternalJobCallbackResult;
import com.orqix.sdk.model.ExternalJobCompleteRequest;
import com.orqix.sdk.model.ExternalJobExtendRequest;
import com.orqix.sdk.model.ExternalJobFailRequest;
import com.orqix.sdk.model.ExternalJobFetchRequest;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * External-worker loop (docs/build/S64): takes jobs for its topics, runs the handler and reports the result. Same
 * feature names as the Python SDK ({@code tools/worker-sdk/orqix_worker.py}): fetch-and-lock, extend lock, complete,
 * fail, fail with error, await callback, complete callback, fail callback, handle, run, stop.
 *
 * <pre>{@code
 * OrqixWorker worker = OrqixWorker.builder(client, "address-checker-1")
 *         .handler("address-check", job -> Map.of("valid", true, "score", 0.9))
 *         .build();
 * worker.run(); // until worker.stop()
 * }</pre>
 *
 * <p>While a handler runs, the lease is extended at half its length. Final reports use the idempotency key
 * {@code <job>:<fence>:complete} or {@code <job>:<fence>:fail} and are repeated on transient failures, so a lost
 * answer never completes a job twice. A job whose lease was lost ({@code JOB_LEASE_LOST}, {@code JOB_NOT_OPEN}) is
 * dropped: another worker owns it. 401 and 403 stop the loop.
 */
public final class OrqixWorker implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(OrqixWorker.class.getName());

    private final OrqixClient client;
    private final String workerId;
    private final int leaseSeconds;
    private final int maxJobs;
    private final Duration pollInterval;
    private final Duration extendEvery;
    private final int finalCallAttempts;
    private final Duration finalCallBackoff;
    private final Map<String, JobHandler> handlers;
    private final ScheduledExecutorService heartbeat;
    private volatile CountDownLatch stopSignal = new CountDownLatch(1);

    private OrqixWorker(Builder builder) {
        this.client = builder.client;
        this.workerId = builder.workerId;
        this.leaseSeconds = builder.leaseSeconds;
        this.maxJobs = builder.maxJobs;
        this.pollInterval = builder.pollInterval;
        this.extendEvery = builder.extendEvery != null ? builder.extendEvery
                : Duration.ofMillis(Math.max(1000, leaseSeconds * 500L));
        this.finalCallAttempts = builder.finalCallAttempts;
        this.finalCallBackoff = builder.finalCallBackoff;
        // Sorted, like the Python SDK: the fetch request lists topics in a stable order.
        this.handlers = Collections.unmodifiableMap(new TreeMap<>(builder.handlers));
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "orqix-lease-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** {@code workerId} names this worker process in the server's records (1-100 characters). */
    public static Builder builder(OrqixClient client, String workerId) {
        return new Builder(client, workerId);
    }

    public String workerId() {
        return workerId;
    }

    // ------------------------------------------------------------------------------------------------ protocol calls

    public List<Job> fetchAndLock(Collection<String> topics) {
        List<Job> jobs = new ArrayList<>();
        List<ExternalJob> items = client.fetchExternalJobs(new ExternalJobFetchRequest(workerId, List.copyOf(topics),
                (long) maxJobs, (long) leaseSeconds)).items();
        for (ExternalJob item : items == null ? List.<ExternalJob>of() : items) {
            jobs.add(new Job(item));
        }
        return jobs;
    }

    public OffsetDateTime extendLock(Job job) {
        return extendLock(job, leaseSeconds);
    }

    public OffsetDateTime extendLock(Job job, int seconds) {
        OffsetDateTime expires = client.extendExternalJobLock(job.id(),
                new ExternalJobExtendRequest(workerId, job.fence(), (long) seconds)).leaseExpiresAt();
        job.leaseExpiresAt(expires);
        return expires;
    }

    /**
     * Hands the job off before submitting remote work (asynchronous gateways). Use an outbox and a stable provider
     * dispatch key; neither a callback timeout nor a failure resubmits remote work automatically.
     */
    public CallbackTicket awaitCallback(Job job, int timeoutSeconds) {
        ExternalJobCallbackRegistration registration = client.awaitExternalJobCallback(job.id(),
                job.id() + ":" + job.fence() + ":await-callback",
                new ExternalJobAwaitCallbackRequest(workerId, job.fence(), (long) timeoutSeconds));
        return new CallbackTicket(job.id(), registration.callbackId(), registration.deadline());
    }

    /** Delivers the result as the subject that registered the callback; the process may have restarted since. */
    public void completeCallback(CallbackTicket ticket, Map<String, ?> result) {
        client.completeExternalJobCallback(ticket.jobId(), ticket.callbackId(), ticket.callbackId() + ":complete",
                new ExternalJobCallbackResult(new LinkedHashMap<>(result)));
    }

    /** Reports a final remote failure. An operator inspects the remote outcome before authorizing a retry. */
    public void failCallback(CallbackTicket ticket, String message) {
        client.failExternalJobCallback(ticket.jobId(), ticket.callbackId(), ticket.callbackId() + ":fail",
                new ExternalJobCallbackFailure(message(message)));
    }

    public void complete(Job job, Map<String, ?> result) {
        Map<String, Object> values = new LinkedHashMap<>(result == null ? Map.of() : result);
        finalCall(() -> client.completeExternalJob(job.id(), job.id() + ":" + job.fence() + ":complete",
                new ExternalJobCompleteRequest(workerId, job.fence(), values)));
    }

    public void fail(Job job, String message, boolean retryable, Integer retryAfterSeconds) {
        finalCall(() -> client.failExternalJob(job.id(), job.id() + ":" + job.fence() + ":fail",
                new ExternalJobFailRequest(workerId, job.fence(), message(message), retryable,
                        retryAfterSeconds == null ? null : retryAfterSeconds.longValue(), null)));
    }

    /**
     * Reports a business error (docs/build/S132). Final; the workflow's failed route or section error route decides
     * what happens next. Same idempotency key as {@link #fail}: a lease ends with one final report.
     */
    public void failWithError(Job job, String errorCode, String message) {
        BusinessErrorException.checkCode(errorCode);
        finalCall(() -> client.failExternalJob(job.id(), job.id() + ":" + job.fence() + ":fail",
                new ExternalJobFailRequest(workerId, job.fence(), message(message), false, null, errorCode)));
    }

    // ------------------------------------------------------------------------------------------------ running handlers

    /** Runs one job with automatic lease extension; returns true when the job was completed. */
    public boolean handle(Job job, JobHandler handler) {
        ScheduledFuture<?> beat = heartbeat.scheduleAtFixedRate(() -> keepAlive(job),
                extendEvery.toMillis(), extendEvery.toMillis(), TimeUnit.MILLISECONDS);
        Map<String, ?> result;
        try {
            result = handler.handle(job);
        } catch (BusinessErrorException failure) {
            beat.cancel(false);
            failWithError(job, failure.code(), failure.businessMessage());
            return false;
        } catch (JobFailedException failure) {
            beat.cancel(false);
            fail(job, failure.getMessage(), failure.retryable(), failure.retryAfterSeconds());
            return false;
        } catch (Exception unexpected) {
            beat.cancel(false);
            if (unexpected instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // The class name is all the workflow's timeline gets to see.
            fail(job, "The worker raised " + unexpected.getClass().getSimpleName(), true, null);
            return false;
        } finally {
            beat.cancel(false);
        }
        try {
            complete(job, result);
            return true;
        } catch (OrqixApiException refused) {
            if (refused.status() != 422) {
                throw refused;
            }
            // The result does not match the step's declared fields; say so on the timeline instead of waiting
            // for the lease to run out.
            fail(job, "The worker's result was refused (" + refused.code() + ")", true, null);
            return false;
        }
    }

    private void keepAlive(Job job) {
        try {
            extendLock(job);
        } catch (OrqixApiException e) {
            // The lease is gone (or the server is unreachable); the final call reports it.
            LOG.log(Level.DEBUG, "Lease extension of {0} failed: {1}", job, e.code());
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Lease extension of {0} failed: {1}", job, e.getClass().getSimpleName());
        }
    }

    /** Polls for the topics of the handlers until {@link #stop()}; returns the number of jobs completed. */
    public int run() {
        return loop(false);
    }

    /** One fetch and the jobs it returned; returns the number of jobs completed. */
    public int runOnce() {
        return loop(true);
    }

    private int loop(boolean once) {
        if (handlers.isEmpty()) {
            throw new IllegalStateException("The worker has no handlers");
        }
        if (stopSignal.getCount() == 0) {
            stopSignal = new CountDownLatch(1);
        }
        CountDownLatch signal = stopSignal;
        int completed = 0;
        List<String> topics = List.copyOf(handlers.keySet());
        while (signal.getCount() > 0) {
            List<Job> jobs;
            Duration wait = pollInterval;
            try {
                jobs = fetchAndLock(topics);
            } catch (OrqixApiException e) {
                if (e.isAuthenticationFailure()) {
                    throw e;
                }
                LOG.log(Level.WARNING, "Fetching jobs failed: {0}", e.getMessage());
                jobs = List.of();
                if (e.retryAfterSeconds().isPresent()) {
                    wait = Duration.ofSeconds(Math.min(300, e.retryAfterSeconds().get()));
                }
            }
            for (Job job : jobs) {
                try {
                    completed += handle(job, handlers.get(job.topic())) ? 1 : 0;
                } catch (OrqixApiException e) {
                    if (e.isAuthenticationFailure()) {
                        throw e;
                    }
                    if ("JOB_LEASE_LOST".equals(e.code()) || "JOB_NOT_OPEN".equals(e.code())) {
                        LOG.log(Level.INFO, "{0} dropped: {1}", job, e.code());
                    } else {
                        // The lease runs out and the job is offered again.
                        LOG.log(Level.WARNING, "Reporting {0} failed: {1}", job, e.getMessage());
                    }
                }
            }
            if (once) {
                break;
            }
            if (jobs.isEmpty()) {
                try {
                    if (signal.await(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return completed;
    }

    /** Ends {@link #run()} after the current job. */
    public void stop() {
        stopSignal.countDown();
    }

    /** Stops the loop and the lease heartbeat thread. */
    @Override
    public void close() {
        stop();
        heartbeat.shutdownNow();
    }

    private void finalCall(Runnable call) {
        long backoff = finalCallBackoff.toMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                call.run();
                return;
            } catch (OrqixApiException e) {
                if (!e.isTransient() || attempt >= finalCallAttempts) {
                    throw e;
                }
                long delay = e.retryAfterSeconds().map(s -> Math.min(60_000L, s * 1000)).orElse(backoff);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoff *= 2;
            }
        }
    }

    private static String message(String message) {
        String text = message == null || message.isBlank() ? "The worker reported a failure" : message;
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** Builds an {@link OrqixWorker}. */
    public static final class Builder {
        private final OrqixClient client;
        private final String workerId;
        private final Map<String, JobHandler> handlers = new LinkedHashMap<>();
        private int leaseSeconds = 120;
        private int maxJobs = 5;
        private Duration pollInterval = Duration.ofSeconds(2);
        private Duration extendEvery;
        private int finalCallAttempts = 3;
        private Duration finalCallBackoff = Duration.ofSeconds(1);

        private Builder(OrqixClient client, String workerId) {
            this.client = Objects.requireNonNull(client, "client");
            if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
                throw new IllegalArgumentException("workerId is 1-100 characters");
            }
            this.workerId = workerId;
        }

        /** Serves {@code topic} (lowercase words joined with dashes) with {@code handler}. */
        public Builder handler(String topic, JobHandler handler) {
            if (topic == null || !topic.matches("^[a-z][a-z0-9-]{0,62}[a-z0-9]$")) {
                throw new IllegalArgumentException("A topic is lowercase words joined with dashes: " + topic);
            }
            if (handlers.putIfAbsent(topic, Objects.requireNonNull(handler)) != null) {
                throw new IllegalArgumentException("Topic " + topic + " has two handlers");
            }
            if (handlers.size() > 10) {
                throw new IllegalArgumentException("A worker serves at most 10 topics");
            }
            return this;
        }

        /** The longest the worker holds a job without extending, 30-3600 seconds; default 120. */
        public Builder leaseSeconds(int seconds) {
            if (seconds < 30 || seconds > 3600) {
                throw new IllegalArgumentException("leaseSeconds is 30-3600");
            }
            this.leaseSeconds = seconds;
            return this;
        }

        /** Jobs taken per fetch, 1-10; default 5. They are handled one after another. */
        public Builder maxJobs(int maxJobs) {
            if (maxJobs < 1 || maxJobs > 10) {
                throw new IllegalArgumentException("maxJobs is 1-10");
            }
            this.maxJobs = maxJobs;
            return this;
        }

        /** Wait after a fetch that returned nothing; default 2 seconds. */
        public Builder pollInterval(Duration interval) {
            this.pollInterval = Objects.requireNonNull(interval);
            return this;
        }

        /** How often a running job's lease is extended; default half the lease. */
        public Builder extendEvery(Duration interval) {
            this.extendEvery = Objects.requireNonNull(interval);
            return this;
        }

        /** Attempts and first delay for complete/fail on transient failures (same idempotency key); default 3, 1 s. */
        public Builder finalCallRetries(int attempts, Duration firstDelay) {
            this.finalCallAttempts = Math.max(1, attempts);
            this.finalCallBackoff = Objects.requireNonNull(firstDelay);
            return this;
        }

        public OrqixWorker build() {
            return new OrqixWorker(this);
        }
    }
}
