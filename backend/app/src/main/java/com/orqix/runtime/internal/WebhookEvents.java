package com.orqix.runtime.internal;

import java.time.Clock;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.orqix.integration.OutboundWebhooks;

/**
 * Turns four timeline events into outbound webhook events (docs/build/S99 B1-B3), inside the transaction that appended
 * them (transactional outbox). Only ids and states leave: never the event data, labels, input or output.
 */
@Component
public class WebhookEvents {

    private final OutboundWebhooks webhooks;
    private final RunStore runs;
    private final Clock clock;

    public WebhookEvents(OutboundWebhooks webhooks, RunStore runs, Clock clock) {
        this.webhooks = webhooks;
        this.runs = runs;
        this.clock = clock;
    }

    @EventListener
    public void onEvent(RunEvents.Appended event) {
        String type = switch (event.type()) {
            case "RUN_STARTED" -> "request.started";
            case "TASK_CREATED" -> "task.opened";
            case "RUN_COMPLETED" -> "request.completed";
            case "RUN_NEEDS_ATTENTION" -> "request.needs-attention";
            default -> null;
        };
        if (type == null || !webhooks.wanted(event.workspaceId(), type)) {
            return;
        }
        RunStore.RunRow run = runs.find(event.workspaceId(), event.runId()).orElse(null);
        if (run == null) {
            return;
        }
        String state = switch (type) {
            case "request.completed" -> "completed";
            case "request.needs-attention" -> RunStore.NEEDS_ATTENTION;
            default -> run.lifecycle();
        };
        webhooks.record(new OutboundWebhooks.RequestEvent(event.workspaceId(), type,
                event.runId() + ":" + event.sequence(), run.id(), run.reference(), run.workflowId(), state,
                event.nodeId(), clock.instant()));
    }
}
