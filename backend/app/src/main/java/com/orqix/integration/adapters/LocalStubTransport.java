package com.orqix.integration.adapters;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory stand-in for the four remote systems. It records a bounded journal of what would have been sent (kind,
 * adapter and a payload digest, never the payload) so tests and administrators can see that a request was accepted by the
 * rules without anything leaving the process. The JDBC stub binds parameters and returns no rows; the Kafka stub assigns
 * increasing offsets.
 */
@Component
public class LocalStubTransport implements AdapterTransport {

    private static final int JOURNAL_LIMIT = 200;

    /** One recorded call. */
    public record Sent(AdapterRules.Kind kind, UUID workspaceId, String adapterKey, String payloadSha256) {
    }

    private final Json json;
    private final Deque<Sent> journal = new ArrayDeque<>();
    private final AtomicLong offsets = new AtomicLong();

    public LocalStubTransport(Json json) {
        this.json = json;
    }

    @Override
    public Result execute(AdapterRules.Kind kind, UUID workspaceId, String adapterKey, JsonNode config, JsonNode payload) {
        synchronized (journal) {
            if (journal.size() >= JOURNAL_LIMIT) {
                journal.removeFirst();
            }
            journal.addLast(new Sent(kind, workspaceId, adapterKey, json.canonicalSha256(payload)));
        }
        ObjectNode detail = json.object();
        detail.put("stub", true);
        switch (kind) {
            case EMAIL -> detail.put("recipients", payload.path("to").size());
            case TEAMS -> detail.put("characters", payload.path("text").asString().length());
            case JDBC -> {
                detail.put("rowCount", 0).put("maxRows", config.path("maxRows").asInt());
                var bound = detail.putArray("boundParameters");
                payload.path("parameters").propertyNames().forEach(bound::add);
            }
            case KAFKA -> detail.put("topic", payload.path("topic").asString()).put("offset", offsets.getAndIncrement());
            default -> throw new IllegalStateException();
        }
        return new Result("stubbed", detail);
    }

    /** Snapshot of the journal, oldest first. */
    public List<Sent> sent() {
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
