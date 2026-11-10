package com.orqix.runtime.engine;

import java.util.HashMap;
import java.util.Map;

import org.flowable.job.api.Job;
import org.flowable.job.api.JobInfo;
import org.flowable.job.service.impl.asyncexecutor.JobExecutionObservation;
import org.flowable.job.service.impl.asyncexecutor.JobExecutionObservationProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.shared.tracing.TraceIds;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * One span per engine job (CG-P06, docs/build/S150), plugged into Flowable's async executor. A job of a run continues
 * the trace of the request that started the run: the start stores its W3C traceparent in the process variable
 * {@link TraceIds#PROCESS_VARIABLE}, read here with one indexed query per job. Connector calls, AI calls, poll checks
 * and external worker jobs made by the job's delegate become child spans, and the effect journal and timeline rows the
 * job writes carry its trace id.
 *
 * <p>Decision (S150): the traceparent is written once, at the start. Updating it at every decision or message would let
 * parallel branches of one run conflict on the same variable row (optimistic locking), so jobs after a decision still
 * join the start trace; the decision's own HTTP trace id is on its timeline event.
 */
@Component
public class JobTracing implements JobExecutionObservationProvider {

    private final ObjectProvider<Tracer> tracers;
    private final ObjectProvider<Propagator> propagators;
    private final JdbcClient jdbc;

    public JobTracing(ObjectProvider<Tracer> tracers, ObjectProvider<Propagator> propagators, JdbcClient jdbc) {
        this.tracers = tracers;
        this.propagators = propagators;
        this.jdbc = jdbc;
    }

    @Override
    public JobExecutionObservation create(JobInfo job) {
        Tracer tracer = tracers.getIfAvailable();
        if (tracer == null || tracer == Tracer.NOOP) {
            return JobExecutionObservation.NOOP;
        }
        return new Observed(tracer, job);
    }

    /** Variables of the process instance that name the run and the start's trace, read without the engine. */
    Map<String, String> runVariables(String processInstanceId) {
        Map<String, String> found = new HashMap<>();
        try {
            jdbc.sql("""
                    SELECT name_, text_ FROM act_ru_variable
                    WHERE proc_inst_id_ = :pi AND execution_id_ = :pi
                      AND name_ IN ('orqix_traceparent', 'orqix_runId', 'orqix_workspaceId')
                    """).param("pi", processInstanceId)
                    .query((rs, n) -> found.put(rs.getString(1), rs.getString(2))).list();
        } catch (RuntimeException e) {
            // Tracing must never fail a job: without the variables the job starts its own trace.
        }
        return found;
    }

    private final class Observed implements JobExecutionObservation {

        private final Tracer tracer;
        private final JobInfo job;
        private Span span;

        Observed(Tracer tracer, JobInfo job) {
            this.tracer = tracer;
            this.job = job;
        }

        @Override
        public void start() {
            String processInstanceId = job instanceof Job j ? j.getProcessInstanceId() : null;
            Map<String, String> variables = processInstanceId == null ? Map.of() : runVariables(processInstanceId);
            String parent = variables.get(TraceIds.PROCESS_VARIABLE);
            Propagator propagator = propagators.getIfAvailable();
            Span.Builder builder = TraceIds.isTraceparent(parent) && propagator != null
                    ? propagator.extract(Map.of("traceparent", parent), Map::get)
                    : tracer.spanBuilder();
            builder.name("orqix.job").kind(Span.Kind.CONSUMER)
                    .tag("orqix.job.handler", String.valueOf(job.getJobHandlerType()))
                    .tag("orqix.job.retries", String.valueOf(job.getRetries()));
            if (job instanceof Job j) {
                if (j.getElementId() != null) {
                    builder.tag("orqix.node.id", j.getElementId());
                }
                if (processInstanceId != null) {
                    builder.tag("orqix.engine.process_instance", processInstanceId);
                }
            }
            if (variables.get("orqix_runId") != null) {
                builder.tag("orqix.run.id", variables.get("orqix_runId"));
            }
            if (variables.get("orqix_workspaceId") != null) {
                builder.tag("orqix.workspace.id", variables.get("orqix_workspaceId"));
            }
            span = builder.start();
        }

        @Override
        public void stop() {
            if (span != null) {
                span.end();
            }
        }

        @Override
        public Scope lockScope() {
            return () -> { };
        }

        @Override
        public void lockError(Throwable lockException) {
            if (span != null) {
                span.event("lock-failed");
            }
        }

        @Override
        public Scope executionScope() {
            if (span == null) {
                return () -> { };
            }
            // Log lines written by the job carry its trace id: Micrometer puts it in the MDC while the scope is open.
            Tracer.SpanInScope inScope = tracer.withSpan(span);
            return inScope::close;
        }

        @Override
        public void executionError(Throwable exception) {
            if (span != null) {
                span.error(exception);
            }
        }
    }
}
