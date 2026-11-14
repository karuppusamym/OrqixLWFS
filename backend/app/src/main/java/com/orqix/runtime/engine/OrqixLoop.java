package com.orqix.runtime.engine;

import java.util.Map;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.springframework.stereotype.Component;

import com.orqix.runtime.internal.RunEvents;

/**
 * Expression bean {@code orqixLoop} (CG-E01, docs/build/S138). The compiler writes it only into workflows with bounded
 * loops: on the flows into steps inside a loop body ({@link #entered}), on the guard's flow back ({@link #traversed}
 * and the condition {@link #again}) and on the limitReached flow ({@link #limitReached}). Counters are process
 * variables on the process instance, written in the transaction that takes the flow, so a retried job always sees the
 * same occurrence number.
 */
@Component("orqixLoop")
public class OrqixLoop {

    private final RunEvents events;

    public OrqixLoop(@org.springframework.context.annotation.Lazy RunEvents events) {
        this.events = events;
    }

    /** @return the visit number of the step for the current visit (1 outside loops and on the first visit) */
    public static int occurrence(org.flowable.variable.api.delegate.VariableScope scope, String nodeId) {
        Object value = scope.getVariable(ProcessData.OCCURRENCE_PREFIX + nodeId);
        return value instanceof Number n ? Math.max(1, n.intValue()) : 1;
    }

    /** @return how many times the bounded path was taken so far */
    public static int traversals(org.flowable.variable.api.delegate.VariableScope scope, String transitionId) {
        Object value = scope.getVariable(ProcessData.TRAVERSAL_PREFIX + transitionId);
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** A path into a step inside a loop body was taken: the step's next visit has the next occurrence number. */
    public void entered(DelegateExecution execution, String nodeId) {
        Object value = execution.getVariable(ProcessData.OCCURRENCE_PREFIX + nodeId);
        int next = value instanceof Number n ? n.intValue() + 1 : 1;
        processInstance(execution).setVariable(ProcessData.OCCURRENCE_PREFIX + nodeId, next);
    }

    /** Condition of the guard's flow back: the bounded path still has traversals left. */
    public boolean again(DelegateExecution execution, String transitionId, int maxTraversals) {
        return traversals(execution, transitionId) < maxTraversals;
    }

    /** The guard sent the request back: one traversal of the bounded path is used. */
    public void traversed(DelegateExecution execution, String transitionId) {
        int used = traversals(execution, transitionId) + 1;
        processInstance(execution).setVariable(ProcessData.TRAVERSAL_PREFIX + transitionId, used);
        events.append(ProcessData.workspaceId(execution), ProcessData.runId(execution), "LOOP_TRAVERSED",
                stepOf(execution, transitionId), null, Map.of("transitionId", transitionId, "traversal", used));
    }

    /** The guard took the limitReached path: the bounded path is used up. */
    public void limitReached(DelegateExecution execution, String transitionId) {
        events.append(ProcessData.workspaceId(execution), ProcessData.runId(execution), "LOOP_LIMIT_REACHED",
                stepOf(execution, transitionId), null,
                Map.of("transitionId", transitionId, "traversals", traversals(execution, transitionId)));
    }

    /** The step the bounded path leaves: the source of the canonical flow, or its owner for a generated helper. */
    private static String stepOf(DelegateExecution execution, String transitionId) {
        var element = OrqixModel.flowElement(execution.getProcessDefinitionId(), transitionId);
        if (element instanceof org.flowable.bpmn.model.SequenceFlow flow) {
            String source = flow.getSourceRef();
            int at = source.indexOf("__");
            return at > 0 ? source.substring(0, at) : source;
        }
        return null;
    }

    private static ExecutionEntity processInstance(DelegateExecution execution) {
        ExecutionEntity entity = (ExecutionEntity) execution;
        return entity.getProcessInstance() != null ? entity.getProcessInstance() : entity;
    }
}
