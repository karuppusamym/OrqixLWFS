package com.orqix.integration;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Request files as the AI module reads them for document extraction (CG-A06). The integration module owns the port
 * because the allowed dependencies point from ai and runtime to integration; the runtime module implements it with
 * the S25/S52 rules: the file must belong to the request, and quarantined, scan-blocked and removed files are refused.
 * Recognized text and layout are stored only in the S52 OCR result of the file, which quarantine, removal and the
 * retention purge delete.
 */
public interface RunDocuments {

    /** A readable request file. */
    record Document(UUID id, String fileName, String contentType, byte[] content, String sha256) {
    }

    /** The file cannot be read for extraction; {@code reason} is a sentence for the decision record. */
    final class Refused extends Exception {
        private final String code;

        public Refused(String code, String reason) {
            super(reason, null, false, false);
            this.code = code;
        }

        /** {@code NOT_FOUND}, {@code REMOVED}, {@code QUARANTINED}, {@code SCAN_BLOCKED} or {@code INVALID_ID}. */
        public String code() {
            return code;
        }
    }

    /** @param attachmentId the value of the mapped file field (a UUID text) */
    Document read(UUID workspaceId, UUID runId, String attachmentId) throws Refused;

    /** A stored layout of the same bytes from the same provider (a retried task repeats no OCR call). */
    Optional<JsonNode> storedLayout(UUID workspaceId, UUID attachmentId, String sha256, String provider);

    /** Stores (replaces) the file's OCR result: the text and the layout, under the S52 rules. */
    void storeOcr(UUID workspaceId, UUID attachmentId, String provider, String text, JsonNode layout, String sha256);
}
