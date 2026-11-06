package com.orqix.integration;

import java.util.Optional;
import java.util.UUID;

/**
 * Port to the files of a request for v1 multipart and binary bodies (S130). Implemented by the runtime module, which
 * owns attachments; a file is returned only when it belongs to this request, is not removed or quarantined and its
 * scan does not block downloads. Generated documents are request files too.
 */
public interface RequestFiles {

    /** Largest file sent in one call. */
    long MAX_BYTES = 10L * 1024 * 1024;

    record File(String name, String contentType, byte[] bytes, String sha256) {
    }

    Optional<File> read(UUID workspaceId, UUID runId, UUID attachmentId);
}
