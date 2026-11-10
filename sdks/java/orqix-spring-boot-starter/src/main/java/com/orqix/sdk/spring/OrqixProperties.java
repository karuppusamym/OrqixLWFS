package com.orqix.sdk.spring;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code orqix.*} settings of the starter. */
@ConfigurationProperties("orqix")
public class OrqixProperties {

    /** Absolute URL of the API, ending in /api/v1. The starter does nothing until this is set. */
    private String baseUrl;

    /** The workspace UUID the application works in. */
    private String workspaceId;

    /**
     * File holding the machine identity's current access token, reread for every request. Not needed when the
     * application defines an OrqixTokenProvider bean. Never put a token in the configuration itself.
     */
    private Path tokenFile;

    /** Request timeout. */
    private Duration timeout = Duration.ofSeconds(30);

    private final Worker worker = new Worker();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public Path getTokenFile() {
        return tokenFile;
    }

    public void setTokenFile(Path tokenFile) {
        this.tokenFile = tokenFile;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Worker getWorker() {
        return worker;
    }

    /** {@code orqix.worker.*}: the loop that runs the @OrqixWorker methods. */
    public static class Worker {

        /** Run the @OrqixWorker methods. Off for an application that only uses the client. */
        private boolean enabled = true;

        /** Names this process in the server's records; default the application name plus a random suffix. */
        private String id;

        /** Longest a job is held without extending, 30-3600 seconds. */
        private int leaseSeconds = 120;

        /** Jobs taken per fetch, 1-10; they are handled one after another. */
        private int maxJobs = 5;

        /** Wait after a fetch that returned nothing. */
        private Duration pollInterval = Duration.ofSeconds(2);

        /** How long shutdown waits for the job in progress. */
        private Duration shutdownTimeout = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getMaxJobs() {
            return maxJobs;
        }

        public void setMaxJobs(int maxJobs) {
            this.maxJobs = maxJobs;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
        }
    }
}
