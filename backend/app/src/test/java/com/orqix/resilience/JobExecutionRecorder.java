package com.orqix.resilience;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.job.api.Job;
import org.flowable.job.service.impl.persistence.entity.JobInfoEntity;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only engine listener recording which lock owner executed which job (JOB_EXECUTION_SUCCESS / FAILURE). The
 * resilience tests use it to show that jobs are spread over the executors of several application contexts that share
 * one database. Registered next to the product's own listener; the product configuration is not changed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JobExecutionRecorder {

    /**
     * One engine job event. Flowable dispatches JOB_EXECUTION_SUCCESS both when a job command commits and when the
     * failed-job command that handled a failure commits, so a raw success event can belong to a failed attempt: use
     * {@link #completedJobs()} for genuinely successful executions.
     */
    public record Execution(String jobId, String processInstanceId, String lockOwner, boolean success, Instant at) {
    }

    private static final List<Execution> EXECUTIONS = new CopyOnWriteArrayList<>();

    public static void clear() {
        EXECUTIONS.clear();
    }

    public static List<Execution> executions() {
        return List.copyOf(EXECUTIONS);
    }

    /** Failed attempts (JOB_EXECUTION_FAILURE: the job transaction rolled back). */
    public static List<Execution> failedAttempts() {
        return EXECUTIONS.stream().filter(e -> !e.success()).toList();
    }

    /**
     * Attempts whose job transaction committed. A JOB_EXECUTION_FAILURE for a job is followed by the SUCCESS event of
     * the failed-job command; that one is not a completed job.
     */
    public static List<Execution> completedJobs() {
        Map<String, Integer> pendingFailures = new java.util.HashMap<>();
        List<Execution> completed = new ArrayList<>();
        for (Execution e : EXECUTIONS) {
            if (!e.success()) {
                pendingFailures.merge(e.jobId(), 1, Integer::sum);
            } else if (pendingFailures.getOrDefault(e.jobId(), 0) > 0) {
                pendingFailures.merge(e.jobId(), -1, Integer::sum);
            } else {
                completed.add(e);
            }
        }
        return completed;
    }

    /** Completed jobs per lock owner. */
    public static Map<String, Long> successesByOwner() {
        return completedJobs().stream().collect(
                Collectors.groupingBy(e -> String.valueOf(e.lockOwner()), ConcurrentHashMap::new, Collectors.counting()));
    }

    @Bean
    EngineConfigurationConfigurer<SpringProcessEngineConfiguration> orqixJobExecutionRecorder() {
        return configuration -> {
            List<FlowableEventListener> listeners = configuration.getEventListeners() == null ? new ArrayList<>()
                    : new ArrayList<>(configuration.getEventListeners());
            listeners.add(new Listener());
            configuration.setEnableEventDispatcher(true);
            configuration.setEventListeners(listeners);
        };
    }

    private static final class Listener implements FlowableEventListener {

        @Override
        public void onEvent(FlowableEvent event) {
            if (event.getType() != FlowableEngineEventType.JOB_EXECUTION_SUCCESS
                    && event.getType() != FlowableEngineEventType.JOB_EXECUTION_FAILURE) {
                return;
            }
            if (!(event instanceof FlowableEntityEvent entityEvent)) {
                return;
            }
            Object entity = entityEvent.getEntity();
            if (entity instanceof JobInfoEntity job) {
                String processInstanceId = job instanceof Job j ? j.getProcessInstanceId() : null;
                EXECUTIONS.add(new Execution(job.getId(), processInstanceId, job.getLockOwner(),
                        event.getType() == FlowableEngineEventType.JOB_EXECUTION_SUCCESS, Instant.now()));
            }
        }

        @Override
        public boolean isFailOnException() {
            return false;
        }

        @Override
        public boolean isFireOnTransactionLifecycleEvent() {
            return false;
        }

        @Override
        public String getOnTransaction() {
            return null;
        }
    }
}
