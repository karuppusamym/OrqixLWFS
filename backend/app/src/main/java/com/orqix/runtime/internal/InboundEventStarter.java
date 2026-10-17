package com.orqix.runtime.internal;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.identity.Caller;
import com.orqix.identity.PolicyService;
import com.orqix.integration.InboundEvents;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.problem.ApiException;

/**
 * Turns one received inbound event into one run. The caller's own {@code workflow.start} rights decide (RunService
 * enforces them, including 404 for people without read access); the event row is locked so two people cannot start it
 * twice, and a repeat returns the run that already exists.
 */
@Service
public class InboundEventStarter {

    public record Started(UUID eventId, UUID runId, boolean alreadyStarted) {
    }

    private final PolicyService policy;
    private final InboundEvents inbound;
    private final RunService runs;

    public InboundEventStarter(PolicyService policy, InboundEvents inbound, RunService runs) {
        this.policy = policy;
        this.inbound = inbound;
        this.runs = runs;
    }

    @Transactional
    public Started start(Caller caller, UUID workspaceId, UUID eventId) {
        policy.requireVisible(caller, workspaceId);
        InboundEvents.Event event = inbound.lockEvent(workspaceId, eventId).orElseThrow(ApiException::notFound);
        if ("started".equals(event.status())) {
            // Only people who may start the workflow learn which run it became.
            if (!policy.requireVisible(caller, workspaceId).canStart(event.workflowId())) {
                throw ApiException.notFound();
            }
            return new Started(eventId, event.runId(), true);
        }
        RunService.Started started = runs.start(caller, workspaceId, "inbound-" + eventId, new RuntimeDtos.StartRunRequest(
                event.workflowId(), null, "in-" + eventId.toString().substring(0, 8), event.input()));
        inbound.markStarted(workspaceId, eventId, started.run().id(), caller.subjectId());
        return new Started(eventId, started.run().id(), false);
    }
}
