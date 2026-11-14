package com.orqix.runtime.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Local stand-in for the SMTP, Teams and Slack transports of actionable tasks (CG-T01). It keeps a bounded in-memory
 * journal of the rendered messages so tests and local demos can follow a link or press a button; nothing is written
 * to disk or logged and nothing leaves the process. Live transports and tenant registration are an external gate.
 */
@Component
public class LocalTaskActionStub implements TaskActionTransport {

    private static final int JOURNAL_LIMIT = 200;

    /** One delivered message. */
    public record Delivered(String channel, UUID workspaceId, String recipient, JsonNode message) {
    }

    private final Deque<Delivered> journal = new ArrayDeque<>();

    @Override
    public String deliver(String channel, UUID workspaceId, String recipient, JsonNode message) {
        synchronized (journal) {
            if (journal.size() >= JOURNAL_LIMIT) {
                journal.removeFirst();
            }
            journal.addLast(new Delivered(channel, workspaceId, recipient, message.deepCopy()));
        }
        return "stubbed";
    }

    /** Snapshot of the journal, oldest first. */
    public List<Delivered> delivered() {
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
