package com.orqix.integration.alerts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Local stand-in for the Slack and PagerDuty transports of alert notifications (CG-O06). It keeps a bounded in-memory
 * journal of the rendered bodies so tests and demos can see what would have been sent; nothing leaves the process.
 */
@Component
public class LocalAlertHookStub implements AlertHookTransport {

    private static final int JOURNAL_LIMIT = 200;

    /** One posted body. */
    public record Posted(String kind, UUID workspaceId, String secretRef, JsonNode body) {
    }

    private final Deque<Posted> journal = new ArrayDeque<>();

    @Override
    public String post(String kind, UUID workspaceId, String secretRef, JsonNode body) {
        synchronized (journal) {
            if (journal.size() >= JOURNAL_LIMIT) {
                journal.removeFirst();
            }
            journal.addLast(new Posted(kind, workspaceId, secretRef, body.deepCopy()));
        }
        return "stubbed";
    }

    public List<Posted> posted() {
        synchronized (journal) {
            return new ArrayList<>(journal);
        }
    }

    public void reset() {
        synchronized (journal) {
            journal.clear();
        }
    }
}
