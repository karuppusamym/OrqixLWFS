package com.orqix.runtime.internal;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.integration.RunDocuments;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Request files for document extraction (CG-A06) under the S25/S52 rules: only files of this request, never a
 * removed, quarantined or scan-blocked one. The OCR text and layout are kept only in the file's S52 OCR result, which
 * quarantine, removal and the retention purge delete.
 */
@Component
class RunDocumentsAdapter implements RunDocuments {

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    RunDocumentsAdapter(JdbcClient jdbc, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Document read(UUID workspaceId, UUID runId, String attachmentId) throws Refused {
        UUID id;
        try {
            id = UUID.fromString(attachmentId.strip());
        } catch (IllegalArgumentException e) {
            throw new Refused("INVALID_ID", "The document input does not name a request file.");
        }
        record Row(String name, String type, String sha, String scan, boolean deleted, boolean quarantined) {
        }
        Row row = jdbc.sql("""
                SELECT file_name, content_type, sha256, scan_status, deleted_at IS NOT NULL AS deleted,
                       quarantined_at IS NOT NULL AS quarantined
                FROM run_attachment WHERE workspace_id = :ws AND run_id = :run AND id = :id
                """).param("ws", workspaceId).param("run", runId).param("id", id)
                .query((rs, n) -> new Row(rs.getString("file_name"), rs.getString("content_type"), rs.getString("sha256"),
                        rs.getString("scan_status"), rs.getBoolean("deleted"), rs.getBoolean("quarantined")))
                .optional().orElseThrow(() -> new Refused("NOT_FOUND", "The document is not a file of this request."));
        if (row.deleted()) {
            throw new Refused("REMOVED", "The document was removed from the request.");
        }
        if (row.quarantined()) {
            throw new Refused("QUARANTINED", "The document is quarantined while it is reviewed.");
        }
        if (AttachmentScanner.Status.of(row.scan()).blocksDownload()) {
            throw new Refused("SCAN_BLOCKED", "The document is blocked by the virus scan.");
        }
        byte[] content = jdbc.sql("SELECT content FROM run_attachment_content WHERE attachment_id = :id")
                .param("id", id).query(byte[].class).optional()
                .orElseThrow(() -> new Refused("REMOVED", "The document's content is no longer stored."));
        return new Document(id, row.name(), row.type(), content, row.sha());
    }

    @Override
    public Optional<JsonNode> storedLayout(UUID workspaceId, UUID attachmentId, String sha256, String provider) {
        return jdbc.sql("""
                SELECT o.layout::text FROM attachment_ocr_result o JOIN run_attachment a ON a.id = o.attachment_id
                WHERE o.workspace_id = :ws AND o.attachment_id = :id AND o.sha256 = :sha AND o.provider = :provider
                  AND o.status = 'extracted' AND o.layout IS NOT NULL
                  AND a.deleted_at IS NULL AND a.quarantined_at IS NULL
                """).param("ws", workspaceId).param("id", attachmentId).param("sha", sha256).param("provider", provider)
                .query(String.class).optional().map(json::parse);
    }

    @Override
    public void storeOcr(UUID workspaceId, UUID attachmentId, String provider, String text, JsonNode layout,
            String sha256) {
        // Written only while the file is still readable: a quarantine or removal in between wins.
        jdbc.sql("""
                INSERT INTO attachment_ocr_result (attachment_id, workspace_id, provider, status, content_text, sha256,
                                                   requested_by, source, layout, created_at)
                SELECT :id, :ws, :provider, 'extracted', :text, :sha, NULL, 'aiTask', CAST(:layout AS jsonb), :now
                FROM run_attachment a
                WHERE a.id = :id AND a.workspace_id = :ws AND a.deleted_at IS NULL AND a.quarantined_at IS NULL
                ON CONFLICT (attachment_id) DO UPDATE
                   SET provider = EXCLUDED.provider, status = EXCLUDED.status, content_text = EXCLUDED.content_text,
                       sha256 = EXCLUDED.sha256, requested_by = NULL, source = 'aiTask', layout = EXCLUDED.layout,
                       created_at = EXCLUDED.created_at
                """).param("id", attachmentId).param("ws", workspaceId).param("provider", provider).param("text", text)
                .param("sha", sha256).param("layout", json.write(layout)).param("now", Sql.ts(clock.instant())).update();
    }
}
