package com.orqix.runtime.internal;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The open-request limit of a workflow (S101, S103), for top-level and child starts alike.
 *
 * <p>Far below the limit, starts do not wait for each other. Within {@code orqix.runs.quota-margin} of it, they queue
 * behind a per-workflow lock and count again, so the limit stays exact. That holds only while fewer starts than the
 * margin can be in flight at once without the lock; each in-flight start holds a database connection, so the margin
 * must exceed the connection pool size times the number of replicas. This is checked at startup with
 * {@code orqix.runs.expected-replicas}.
 */
@Component
public class RunQuota {

    private final RunStore runs;
    private final int margin;

    public RunQuota(RunStore runs, @Value("${orqix.runs.quota-margin:64}") int margin,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize,
            @Value("${orqix.runs.expected-replicas:4}") int replicas) {
        if (replicas < 1) {
            throw new IllegalArgumentException("orqix.runs.expected-replicas must be at least 1");
        }
        if ((long) poolSize * replicas >= margin) {
            throw new IllegalStateException("orqix.runs.quota-margin (" + margin + ") must exceed the connection pool size ("
                    + poolSize + ") times orqix.runs.expected-replicas (" + replicas
                    + "), or the open-request limit of a workflow can be exceeded");
        }
        this.runs = runs;
        this.margin = margin;
    }

    /** Whether one more request of the workflow fits under {@code limit}. Exact near the limit (see the class note). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasRoom(UUID workspaceId, UUID workflowId, int limit) {
        int active = runs.countActive(workspaceId, workflowId);
        if (active >= limit - margin) {
            runs.lockQuota(workflowId);
            active = runs.countActive(workspaceId, workflowId);
        }
        return active < limit;
    }
}
