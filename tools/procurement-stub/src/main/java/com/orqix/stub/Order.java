package com.orqix.stub;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

/** A stored purchase order. Only {@code id} and {@code status} are part of the public contract. */
public record Order(String id, String status, String idempotencyKey, String requestHash, JsonNode request, Instant createdAt) {

    /** The public response shape: {@code {"id":"PO-n","status":"CREATED"}}. */
    public OrderView view() {
        return new OrderView(id, status);
    }

    /** Public order representation returned by {@code POST /orders} and {@code GET /orders}. */
    public record OrderView(String id, String status) {
    }
}
