package com.orqix.sdk.worker;

import com.orqix.sdk.model.ExternalJob;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A job leased to this worker. {@link #fence()} numbers the lease: every later call presents it, so a worker whose
 * lease expired can never overwrite a newer lease.
 */
public final class Job {

    private final ExternalJob raw;
    private final Map<String, Object> input;
    private volatile OffsetDateTime leaseExpiresAt;

    public Job(ExternalJob raw) {
        this.raw = raw;
        this.input = Collections.unmodifiableMap(raw.input() == null ? Map.of() : new LinkedHashMap<>(raw.input()));
        this.leaseExpiresAt = raw.leaseExpiresAt();
    }

    public String id() {
        return raw.id();
    }

    public String topic() {
        return raw.topic();
    }

    /** The values the step maps, by name. */
    public Map<String, Object> input() {
        return input;
    }

    public long fence() {
        return raw.fence();
    }

    public long attempt() {
        return raw.attempt();
    }

    public long maxAttempts() {
        return raw.maxAttempts();
    }

    /** The human request reference, for example ORQ-1043; use it in logs instead of the job ID. */
    public String runReference() {
        return raw.runReference();
    }

    public String nodeId() {
        return raw.nodeId();
    }

    public OffsetDateTime leaseExpiresAt() {
        return leaseExpiresAt;
    }

    void leaseExpiresAt(OffsetDateTime value) {
        this.leaseExpiresAt = value;
    }

    /** The job as the server sent it. */
    public ExternalJob raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "Job[" + topic() + " " + runReference() + "/" + nodeId() + " attempt " + attempt() + "]";
    }
}
