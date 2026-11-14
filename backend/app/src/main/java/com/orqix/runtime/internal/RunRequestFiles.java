package com.orqix.runtime.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.integration.RequestFiles;

/**
 * Files of a request for v1 multipart and binary bodies (S130): only a file attached to this very request, not
 * removed, purged or quarantined, whose scan does not block downloads, and within the size limit. Read inside the
 * job transaction that sends the call; never logged.
 */
@Component
class RunRequestFiles implements RequestFiles {

    private final JdbcClient jdbc;

    RunRequestFiles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<File> read(UUID workspaceId, UUID runId, UUID attachmentId) {
        return jdbc.sql("""
                SELECT a.file_name, a.content_type, a.sha256, a.scan_status, c.content
                FROM run_attachment a JOIN run_attachment_content c ON c.attachment_id = a.id
                WHERE a.workspace_id = :ws AND a.run_id = :run AND a.id = :id AND a.deleted_at IS NULL
                  AND a.quarantined_at IS NULL AND a.size_bytes <= :max
                """)
                .param("ws", workspaceId).param("run", runId).param("id", attachmentId).param("max", MAX_BYTES)
                .query((rs, n) -> {
                    boolean blocked = "infected".equals(rs.getString("scan_status"))
                            || "error".equals(rs.getString("scan_status"));
                    return blocked ? null : new File(rs.getString("file_name"), rs.getString("content_type"),
                            rs.getBytes("content"), rs.getString("sha256"));
                })
                .list().stream().filter(java.util.Objects::nonNull).findFirst();
    }
}
