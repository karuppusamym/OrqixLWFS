package com.orqix.sdk.spring;

import com.orqix.sdk.OrqixClient;
import com.orqix.sdk.worker.JobHandler;
import java.lang.System.Logger.Level;
import java.util.Map;
import org.springframework.context.SmartLifecycle;

/**
 * Runs one {@link com.orqix.sdk.worker.OrqixWorker} loop for the application's {@link OrqixWorker} methods. Starts
 * after the rest of the application and stops first, waiting for the job in progress up to
 * {@code orqix.worker.shutdown-timeout}.
 */
public class OrqixWorkerContainer implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(OrqixWorkerContainer.class.getName());

    private final OrqixClient client;
    private final OrqixWorkerMethods methods;
    private final OrqixProperties.Worker settings;
    private final String workerId;
    private volatile com.orqix.sdk.worker.OrqixWorker worker;
    private volatile Thread thread;

    public OrqixWorkerContainer(OrqixClient client, OrqixWorkerMethods methods, OrqixProperties.Worker settings,
            String workerId) {
        this.client = client;
        this.methods = methods;
        this.settings = settings;
        this.workerId = workerId;
    }

    public String workerId() {
        return workerId;
    }

    @Override
    public synchronized void start() {
        Map<String, JobHandler> handlers = methods.handlers();
        if (handlers.isEmpty() || thread != null) {
            return;
        }
        com.orqix.sdk.worker.OrqixWorker.Builder builder = com.orqix.sdk.worker.OrqixWorker.builder(client, workerId)
                .leaseSeconds(settings.getLeaseSeconds())
                .maxJobs(settings.getMaxJobs())
                .pollInterval(settings.getPollInterval());
        handlers.forEach(builder::handler);
        worker = builder.build();
        Thread loop = new Thread(this::runLoop, "orqix-worker-" + workerId);
        thread = loop;
        loop.start();
        LOG.log(Level.INFO, "Orqix worker {0} serves {1}", workerId, handlers.keySet());
    }

    private void runLoop() {
        try {
            worker.run();
        } catch (RuntimeException e) {
            // 401/403: the machine identity lost job.work or its token is invalid; the loop cannot recover alone.
            LOG.log(Level.ERROR, "Orqix worker " + workerId + " stopped", e);
        } finally {
            thread = null;
        }
    }

    @Override
    public synchronized void stop() {
        com.orqix.sdk.worker.OrqixWorker current = worker;
        Thread loop = thread;
        if (current == null) {
            return;
        }
        current.stop();
        if (loop != null) {
            try {
                loop.join(settings.getShutdownTimeout().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        current.close();
        worker = null;
        thread = null;
    }

    @Override
    public boolean isRunning() {
        return thread != null;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }
}
