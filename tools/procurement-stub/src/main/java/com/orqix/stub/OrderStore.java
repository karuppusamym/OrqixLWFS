package com.orqix.stub;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Thread-safe in-memory order store keyed by idempotency key. All mutations are serialised on one lock. */
@Component
public class OrderStore {

    /** Result of a create attempt. */
    public sealed interface Outcome permits Created, Replayed, Conflict {
        Order order();
    }

    /** New key: a new order was stored (HTTP 201). */
    public record Created(Order order) implements Outcome {
    }

    /** Same key and same body: the original order (HTTP 200). */
    public record Replayed(Order order) implements Outcome {
    }

    /** Same key but a different body (HTTP 422). {@code order} is the original order. */
    public record Conflict(Order order) implements Outcome {
    }

    /** Counters for {@code GET /__orders}. */
    public record Stats(long createRequests, long created, long replays, long conflicts) {
    }

    private final Object lock = new Object();
    private final Map<String, Order> byKey = new LinkedHashMap<>();
    private final Clock clock;
    private long sequence;
    private long createRequests;
    private long created;
    private long replays;
    private long conflicts;

    public OrderStore() {
        this(Clock.systemUTC());
    }

    OrderStore(Clock clock) {
        this.clock = clock;
    }

    public Outcome createOrReplay(String idempotencyKey, String requestHash, JsonNode request) {
        synchronized (lock) {
            createRequests++;
            Order existing = byKey.get(idempotencyKey);
            if (existing != null) {
                if (existing.requestHash().equals(requestHash)) {
                    replays++;
                    return new Replayed(existing);
                }
                conflicts++;
                return new Conflict(existing);
            }
            sequence++;
            Order order = new Order("PO-" + sequence, "CREATED", idempotencyKey, requestHash, request, Instant.now(clock));
            byKey.put(idempotencyKey, order);
            created++;
            return new Created(order);
        }
    }

    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        synchronized (lock) {
            return Optional.ofNullable(byKey.get(idempotencyKey));
        }
    }

    public List<Order> all() {
        synchronized (lock) {
            return new ArrayList<>(byKey.values());
        }
    }

    public Stats stats() {
        synchronized (lock) {
            return new Stats(createRequests, created, replays, conflicts);
        }
    }

    public void reset() {
        synchronized (lock) {
            byKey.clear();
            sequence = 0;
            createRequests = 0;
            created = 0;
            replays = 0;
            conflicts = 0;
        }
    }
}
