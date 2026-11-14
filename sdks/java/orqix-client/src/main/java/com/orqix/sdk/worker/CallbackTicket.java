package com.orqix.sdk.worker;

import java.time.OffsetDateTime;

/**
 * The hand-off of a job to an asynchronous gateway (docs/build/S64, durable asynchronous callbacks). Persist the three
 * fields in the gateway's outbox and rebuild the ticket after a restart. The callback ID is a correlation identifier,
 * not a credential: delivery still authenticates as the subject that registered it.
 */
public record CallbackTicket(String jobId, String callbackId, OffsetDateTime deadline) {
}
