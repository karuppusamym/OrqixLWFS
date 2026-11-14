package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Guest response forms (CG-F05, docs/build/S140). No view contains a link token except the one-time issue answer; the
 * guest views never contain the request's own data, only the step's title, instructions and questions.
 */
public final class GuestDtos {

    private GuestDtos() {
    }

    /** A question of the guest form: one declared payload field of the waiting step. */
    public record GuestQuestion(String name, String type, String title, boolean required) {
    }

    /** What files the guest may add. */
    public record GuestFileRules(int maxFiles, boolean required, List<String> acceptTypes, long maxFileBytes) {
    }

    /**
     * Operator view of one guest link.
     *
     * @param state     {@code waiting}, {@code submitted}, {@code expired}, {@code revoked} or {@code cancelled}
     * @param reason    for {@code cancelled}: {@code timedOut}, {@code requestEnded} or {@code answeredElsewhere}
     * @param sendState {@code manual} (an operator shares the link), {@code pending}, {@code sent}, {@code refused}
     *                  (the address is not in an approved domain) or {@code failed}
     */
    public record GuestLink(UUID id, UUID runId, String runReference, String nodeId, String stepLabel, String title,
            String state, String reason, String sendState, String sentDomain, boolean tokenIssued,
            Instant tokenExpiresAt, int tokensIssued, String linkValidFor, Instant firstViewedAt, Instant submittedAt,
            Instant createdAt) {
    }

    public record GuestLinkList(List<GuestLink> items) {
    }

    /** The only answer that contains the link (with its token); it cannot be read again. */
    public record GuestLinkIssued(UUID linkId, String url, Instant expiresAt) {
    }

    /** A file the guest added and has not sent yet. */
    public record GuestFile(UUID id, String name, String contentType, long sizeBytes) {
    }

    /**
     * The guest's view of the form.
     *
     * @param state {@code open}, {@code submitted}, {@code expired} or {@code closed}
     */
    public record GuestForm(String title, String instructions, String state, Instant expiresAt,
            List<GuestQuestion> questions, GuestFileRules files, List<GuestFile> uploaded) {
    }

    /** {@code result}: the answers by question name; {@code files}: ids of uploaded files to send with them. */
    public record GuestSubmit(JsonNode result, List<UUID> files) {
    }

    public record GuestSubmitted(String state, Instant submittedAt, int fileCount) {
    }

    /** Email domains guest links may be sent to (D-CG7). */
    public record GuestDomains(List<String> domains) {
    }
}
