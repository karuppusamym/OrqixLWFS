package com.orqix.integration;

import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

/**
 * What a signed inbound event may do with a callback token (S99 A1-A4), implemented by the runtime: resume exactly the
 * wait the token belongs to, and only when that request runs the trigger's own workflow in the same workspace.
 * Problems are reported as {@code ApiException}s (404 for any token that does not qualify).
 */
public interface InboundCallbacks {

    /** @param replayed true when the same event was already accepted and the original answer is returned */
    record Resumed(UUID waitId, UUID runId, boolean replayed) {
    }

    Resumed resume(UUID workspaceId, UUID workflowId, String token, String idempotencyKey, ObjectNode result);
}
