package com.orqix.runtime.internal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.orqix.integration.McpToolDriftDetected;
import com.orqix.notification.NotificationService;

/**
 * Tells the administrator who pinned a tool server's catalog that one of its tools is refused (CG-M01, S135). Runs in
 * the transaction that records the drift; one notification per drift row (the dedup key).
 */
@Component
public class AgentToolNotifications {

    private final NotificationService notifications;

    public AgentToolNotifications(NotificationService notifications) {
        this.notifications = notifications;
    }

    @EventListener
    public void driftDetected(McpToolDriftDetected drift) {
        if (drift.notifySubject() == null) {
            return;
        }
        String what = switch (drift.kind()) {
            case "changed" -> "changed its input schema since it was pinned";
            case "missing" -> "is no longer offered by the server";
            default -> "has no pin";
        };
        notifications.create(drift.workspaceId(), drift.notifySubject(), null,
                NotificationService.Kind.AGENT_TOOL_CHANGED,
                "agent-tool:" + drift.workspaceId() + ":" + drift.connectionKey() + ":" + drift.toolName() + ":"
                        + drift.kind() + ":" + java.time.LocalDate.now(),
                "The tool " + drift.toolName() + " of tool server " + drift.connectionKey() + " " + what
                        + ". Agents no longer use it; check the server, then take a new tool snapshot.");
    }
}
