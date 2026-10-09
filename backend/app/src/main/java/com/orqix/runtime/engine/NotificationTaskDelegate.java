package com.orqix.runtime.engine;

import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import com.orqix.notification.NotificationService;
import com.orqix.runtime.internal.RunEvents;

/**
 * Delegate {@code orqixNotificationTask} (S5 section 9): writes one in-app notification for the initiator inside the
 * current engine transaction. The dedup key {@code runId:nodeId:occurrence:initiator} makes a retried job harmless.
 */
@Component("orqixNotificationTask")
public class NotificationTaskDelegate implements JavaDelegate {

    /** Acyclic R0 graphs visit every node at most once (S2 section 6). */
    static final int OCCURRENCE = 1;

    private final NotificationService notifications;
    private final RunEvents events;

    public NotificationTaskDelegate(NotificationService notifications, RunEvents events) {
        this.notifications = notifications;
        this.events = events;
    }

    @Override
    public void execute(DelegateExecution execution) {
        FlowElement element = execution.getCurrentFlowElement();
        ExtensionElement config = OrqixModel.requireExtension(element, "notification");
        String recipientMode = OrqixModel.attribute(config, "recipient");
        if (!"initiator".equals(recipientMode)) {
            throw new IllegalStateException("Unsupported notification recipient " + recipientMode);
        }
        UUID workspaceId = ProcessData.workspaceId(execution);
        UUID runId = ProcessData.runId(execution);
        UUID initiator = ProcessData.initiator(execution);
        String dedupKey = runId + ":" + element.getId() + ":" + OCCURRENCE + ":" + initiator;
        boolean created = notifications.create(workspaceId, initiator, runId, NotificationService.Kind.WORKFLOW_MESSAGE,
                dedupKey, OrqixModel.attribute(config, "message"));
        if (created) {
            events.append(workspaceId, runId, "NOTIFICATION_CREATED", element.getId(), null,
                    Map.of("kind", NotificationService.Kind.WORKFLOW_MESSAGE.value(), "recipient", "initiator"));
        }
    }
}
