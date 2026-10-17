package com.orqix.runtime.engine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEventDispatcher;
import org.flowable.common.engine.impl.calendar.DurationHelper;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.delegate.event.impl.FlowableEventBuilder;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.job.api.FlowableUnrecoverableJobException;
import org.flowable.job.service.JobService;
import org.flowable.job.service.TimerJobService;
import org.flowable.job.service.impl.asyncexecutor.FailedJobCommandFactory;
import org.flowable.job.service.impl.persistence.entity.AbstractRuntimeJobEntity;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.orqix.runtime.internal.FailedJobRecorder;
import com.orqix.runtime.internal.JobFailure;

/**
 * Custom failed-job command (S5 section 6.4, BD-24). Same retry-cycle semantics as Flowable's {@code JobRetryCmd}
 * (spike S-4: {@code R<n>} yields exactly n attempts) plus: non-retryable Orqix failures dead-letter immediately with
 * retries 0, and the Orqix projection (events, needsAttention, notification) is updated in the same transaction.
 */
@Component
public class OrqixFailedJobCommandFactory implements FailedJobCommandFactory {

    private static final Logger log = LoggerFactory.getLogger(OrqixFailedJobCommandFactory.class);

    private final FailedJobRecorder recorder;

    public OrqixFailedJobCommandFactory(FailedJobRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Command<Object> getCommand(String jobId, Throwable exception) {
        return new FailedJobCommand(jobId, exception, recorder);
    }

    static final class FailedJobCommand implements Command<Object> {

        private final String jobId;
        private final Throwable exception;
        private final FailedJobRecorder recorder;

        FailedJobCommand(String jobId, Throwable exception, FailedJobRecorder recorder) {
            this.jobId = jobId;
            this.exception = exception;
            this.recorder = recorder;
        }

        @Override
        public Object execute(CommandContext commandContext) {
            ProcessEngineConfigurationImpl configuration = CommandContextUtil.getProcessEngineConfiguration(commandContext);
            JobService jobService = configuration.getJobServiceConfiguration().getJobService();
            TimerJobService timerJobService = configuration.getJobServiceConfiguration().getTimerJobService();

            JobEntity job = jobService.findJobById(jobId);
            if (job == null) {
                return null;
            }
            ExecutionEntity execution = job.getExecutionId() == null ? null
                    : CommandContextUtil.getExecutionEntityManager(commandContext).findById(job.getExecutionId());
            FlowElement element = execution == null ? null : execution.getCurrentFlowElement();
            String cycle = element instanceof ServiceTask task ? task.getFailedJobRetryTimeCycleValue() : null;
            if (cycle != null && cycle.contains("${") && execution != null) {
                // Same as Flowable's JobRetryCmd; generated BPMN only ever contains literal cycles.
                Object value = configuration.getExpressionManager().createExpression(cycle).getValue(execution);
                cycle = value == null ? null : value.toString();
            }

            JobFailure failure = JobFailure.find(exception);
            boolean nonRetryable = (failure != null && !failure.retryable())
                    || containsCause(exception, FlowableUnrecoverableJobException.class);

            int retries = job.getRetries();
            Date nextDue;
            if (cycle != null && !cycle.isBlank()) {
                try {
                    DurationHelper helper = new DurationHelper(cycle, configuration.getClock());
                    if (job.getExceptionMessage() == null) {
                        // First failure: the cycle's repetition count replaces the default retries.
                        retries = helper.getTimes();
                    }
                    nextDue = helper.getDateAfter();
                } catch (Exception e) {
                    throw new IllegalStateException("Invalid failedJobRetryTimeCycle " + cycle, e);
                }
            } else {
                nextDue = Date.from(configuration.getClock().getCurrentTime().toInstant()
                        .plusSeconds(configuration.getAsyncFailedJobWaitTime()));
            }
            if (execution != null) {
                execution.setActive(false);
            }

            boolean deadLetter = nonRetryable || retries <= 1;
            AbstractRuntimeJobEntity moved;
            if (deadLetter) {
                moved = jobService.moveJobToDeadLetterJob(job);
                moved.setRetries(0);
            } else {
                moved = timerJobService.moveJobToTimerJob(job);
                moved.setRetries(retries - 1);
                moved.setDuedate(nextDue);
            }
            moved.setExceptionMessage(exceptionMessage());
            moved.setExceptionStacktrace(stacktrace());

            FlowableEventDispatcher dispatcher = configuration.getEventDispatcher();
            if (dispatcher != null && dispatcher.isEnabled()) {
                dispatcher.dispatchEvent(FlowableEventBuilder.createEntityEvent(FlowableEngineEventType.ENTITY_UPDATED, moved),
                        configuration.getEngineCfgKey());
                dispatcher.dispatchEvent(FlowableEventBuilder.createEntityEvent(
                        FlowableEngineEventType.JOB_RETRIES_DECREMENTED, moved), configuration.getEngineCfgKey());
            }

            if (execution != null && execution.getVariable(ProcessData.RUN_ID) != null) {
                Instant next = deadLetter ? null : nextDue.toInstant();
                recorder.record(new FailedJobRecorder.Failure(ProcessData.workspaceId(execution),
                        ProcessData.runId(execution), ProcessData.initiator(execution),
                        job.getElementId() == null ? execution.getActivityId() : job.getElementId(),
                        deadLetter, next, failure));
            }
            if (failure == null) {
                log.warn("Job {} failed with an unexpected exception ({} retries left)", jobId,
                        deadLetter ? 0 : retries - 1, exception);
            }
            return null;
        }

        /** Never null: a null message would make Flowable treat the next failure as the first one. */
        private String exceptionMessage() {
            String message = exception == null ? null : exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception == null ? "Job failed" : exception.getClass().getSimpleName();
            }
            return message.length() > 3900 ? message.substring(0, 3900) : message;
        }

        private String stacktrace() {
            if (exception == null) {
                return null;
            }
            StringWriter writer = new StringWriter();
            exception.printStackTrace(new PrintWriter(writer));
            String trace = writer.toString();
            return trace.length() > 20000 ? trace.substring(0, 20000) : trace;
        }

        private static boolean containsCause(Throwable error, Class<? extends Throwable> type) {
            for (Throwable t = error; t != null; t = t.getCause()) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings("unused")
        private static UUID uuid(Object value) {
            return value == null ? null : UUID.fromString(value.toString());
        }
    }
}
