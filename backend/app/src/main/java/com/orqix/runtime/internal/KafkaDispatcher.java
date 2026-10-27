package com.orqix.runtime.internal;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.identity.Caller;
import com.orqix.identity.SubjectService;
import com.orqix.integration.KafkaDispatch;
import com.orqix.runtime.api.MessageDtos;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.problem.ApiException;

import tools.jackson.databind.node.ObjectNode;

/**
 * Runtime side of Kafka triggers (docs/build/S99 K8): the record becomes a normal start or a normal message, as the
 * trigger's run-as person, so start permission, input validation, quotas, idempotency, message buffering and payload
 * checks are exactly the manual ones. Problems are sorted into "never" ({@link KafkaDispatch.Refused}) and "later"
 * ({@link KafkaDispatch.Retry}).
 */
@Service
public class KafkaDispatcher implements KafkaDispatch {

    private final SubjectService subjects;
    private final RunService runs;
    private final MessageService messages;

    public KafkaDispatcher(SubjectService subjects, RunService runs, MessageService messages) {
        this.subjects = subjects;
        this.runs = runs;
        this.messages = messages;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Dispatched start(UUID workspaceId, UUID runAs, UUID workflowId, String idempotencyKey, String businessKey,
            ObjectNode input) {
        Caller caller = caller(runAs);
        try {
            RunService.Started started = runs.start(caller, workspaceId, idempotencyKey,
                    new RuntimeDtos.StartRunRequest(workflowId, null, businessKey, input));
            return new Dispatched(Outcome.STARTED, started.run().id(), null);
        } catch (ApiException e) {
            throw classify(e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Dispatched resume(UUID workspaceId, UUID runAs, String messageName, String correlationKey,
            String idempotencyKey, ObjectNode payload) {
        Caller caller = caller(runAs);
        try {
            MessageService.Sent sent = messages.send(caller, workspaceId, idempotencyKey,
                    new MessageDtos.SendMessageRequest(messageName, correlationKey, payload));
            boolean delivered = "delivered".equals(sent.body().status());
            return new Dispatched(delivered ? Outcome.RESUMED : Outcome.BUFFERED,
                    delivered && !sent.body().delivered().isEmpty() ? sent.body().delivered().get(0).runId() : null,
                    sent.body().id());
        } catch (ApiException e) {
            throw classify(e);
        }
    }

    private Caller caller(UUID runAs) {
        SubjectService.Subject subject = subjects.find(runAs).orElse(null);
        if (subject == null || subject.disabled()) {
            throw new Refused("run_as_unavailable");
        }
        return new Caller(subject.id(), subject.displayName(), subject.email(), subject.issuer(),
                subject.externalSubject(), subjects.storedGroups(runAs), false);
    }

    private static RuntimeException classify(ApiException e) {
        String code = e.code().name().toLowerCase(Locale.ROOT);
        return switch (e.code()) {
            case RUN_BUSY, RATE_LIMITED, QUOTA_EXCEEDED, DEPENDENCY_UNAVAILABLE -> new Retry(code);
            default -> new Refused(code.length() > 40 ? code.substring(0, 40) : code);
        };
    }
}
