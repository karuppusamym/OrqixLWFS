package com.orqix.runtime.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.OcrProvider;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DOC08 (docs/build/S52): quarantine and release of request files, per-workspace retention and the OCR provider
 * contract. Quarantine blocks download and text extraction and keeps the bytes; only {@code run.operate} holders
 * quarantine or release. Retention is set by workspace administrators and removes the bytes of files of ended requests
 * after the configured days; quarantined files are exempt until released or removed.
 */
@Service
public class AttachmentLifecycle {

    public static final int BATCH = 200;

    public record QuarantineRequest(@NotBlank @Size(min = 3, max = 300) String reason) {
    }

    public record RetentionRequest(@Min(1) @Max(3650) Integer retentionDays, @Min(0) int expectedVersion) {
    }

    /** {@code eligibleNow} is how many files the next sweep would remove under the current setting. */
    public record Retention(Integer retentionDays, int version, int eligibleNow, Instant updatedAt) {
    }

    public record Ocr(UUID attachmentId, String provider, String status, String text, Instant extractedAt) {
    }

    private final JdbcClient jdbc;
    private final RunAccess access;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final RunEvents events;
    private final OcrProvider ocr;
    private final Clock clock;

    public AttachmentLifecycle(JdbcClient jdbc, RunAccess access, PolicyService policy, AuditWriter audit,
            RunEvents events, OcrProvider ocr, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.policy = policy;
        this.audit = audit;
        this.events = events;
        this.ocr = ocr;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- quarantine

    @Transactional
    public void quarantine(Caller caller, UUID ws, UUID runId, UUID attachmentId, QuarantineRequest request) {
        operator(caller, ws, runId, "quarantineAttachment");
        Map<String, Object> file = lockFile(ws, runId, attachmentId);
        if (file.get("deleted_at") != null) {
            throw new ApiException(ProblemCode.ATTACHMENT_BLOCKED, "A removed file cannot be quarantined.");
        }
        if (file.get("quarantined_at") != null) {
            return;
        }
        jdbc.sql("""
                UPDATE run_attachment SET quarantined_at = :now, quarantined_by = :by, quarantine_reason = :reason
                WHERE id = :id
                """).param("now", Sql.ts(clock.instant())).param("by", caller.subjectId())
                .param("reason", request.reason().trim()).param("id", attachmentId).update();
        jdbc.sql("DELETE FROM attachment_ocr_result WHERE attachment_id = :id").param("id", attachmentId).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "attachment.quarantined",
                "attachment", attachmentId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("runId", runId.toString(), "attachmentId", attachmentId.toString())));
        events.append(ws, runId, "ATTACHMENT_QUARANTINED", null, caller.subjectId(),
                Map.of("attachmentId", attachmentId.toString(), "name", (String) file.get("file_name")));
    }

    @Transactional
    public void release(Caller caller, UUID ws, UUID runId, UUID attachmentId) {
        operator(caller, ws, runId, "releaseAttachment");
        Map<String, Object> file = lockFile(ws, runId, attachmentId);
        if (file.get("quarantined_at") == null) {
            return;
        }
        jdbc.sql("""
                UPDATE run_attachment SET quarantined_at = NULL, quarantined_by = NULL, quarantine_reason = NULL
                WHERE id = :id
                """).param("id", attachmentId).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "attachment.released",
                "attachment", attachmentId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("runId", runId.toString(), "attachmentId", attachmentId.toString())));
        events.append(ws, runId, "ATTACHMENT_RELEASED", null, caller.subjectId(),
                Map.of("attachmentId", attachmentId.toString(), "name", (String) file.get("file_name")));
    }

    // ---------------------------------------------------------------- retention

    @Transactional(readOnly = true)
    public Retention retention(Caller caller, UUID ws) {
        admin(caller, ws, "getAttachmentRetention");
        return readRetention(ws);
    }

    @Transactional
    public Retention setRetention(Caller caller, UUID ws, RetentionRequest request) {
        admin(caller, ws, "setAttachmentRetention");
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).optional();
        int current = jdbc.sql("SELECT version FROM workspace_attachment_policy WHERE workspace_id = :ws")
                .param("ws", ws).query(Integer.class).optional().orElse(0);
        if (current != request.expectedVersion()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,
                    "The retention setting changed. Reload before saving.").with("currentVersion", current);
        }
        jdbc.sql("""
                INSERT INTO workspace_attachment_policy (workspace_id, retention_days, version, updated_by, updated_at)
                VALUES (:ws, :days, 1, :by, :now)
                ON CONFLICT (workspace_id) DO UPDATE
                   SET retention_days = :days, version = workspace_attachment_policy.version + 1,
                       updated_by = :by, updated_at = :now
                """).param("ws", ws).param("days", request.retentionDays()).param("by", caller.subjectId())
                .param("now", Sql.ts(clock.instant())).update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retentionDays", request.retentionDays());
        metadata.put("version", current + 1);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "attachment.retention.updated", "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return readRetention(ws);
    }

    private Retention readRetention(UUID ws) {
        var row = jdbc.sql("SELECT retention_days, version, updated_at FROM workspace_attachment_policy WHERE workspace_id = :ws")
                .param("ws", ws).query((rs, n) -> new Object[] {(Integer) rs.getObject("retention_days"),
                        rs.getInt("version"), Sql.instant(rs, "updated_at")}).optional();
        Integer days = row.map(r -> (Integer) r[0]).orElse(null);
        int eligible = days == null ? 0 : jdbc.sql("SELECT count(*) FROM (" + CANDIDATES + " AND a.workspace_id = :ws) c")
                .param("now", Sql.ts(clock.instant())).param("ws", ws).query(Integer.class).single();
        return new Retention(days, row.map(r -> (Integer) r[1]).orElse(0), eligible,
                row.map(r -> (Instant) r[2]).orElse(null));
    }

    private static final String CANDIDATES = """
            SELECT a.id, a.workspace_id, a.run_id, p.retention_days
            FROM run_attachment a
            JOIN workflow_run r ON r.workspace_id = a.workspace_id AND r.id = a.run_id
            JOIN workspace_attachment_policy p ON p.workspace_id = a.workspace_id
            WHERE p.retention_days IS NOT NULL AND a.run_id IS NOT NULL AND a.deleted_at IS NULL
              AND a.quarantined_at IS NULL AND r.ended_at IS NOT NULL
              AND r.ended_at < CAST(:now AS timestamptz) - (p.retention_days * interval '1 day')
            """;

    @Scheduled(fixedDelayString = "${orqix.attachments.retention-delay:1h}")
    @Transactional
    public void sweep() {
        purgeExpired();
    }

    /** Removes the bytes of up to {@value #BATCH} files past retention. Safe on several pods. @return files removed */
    @Transactional
    public int purgeExpired() {
        record Candidate(UUID id, UUID ws, UUID run, int days) {
        }
        List<Candidate> due = jdbc.sql(CANDIDATES + " ORDER BY r.ended_at, a.id LIMIT " + BATCH + " FOR UPDATE OF a SKIP LOCKED")
                .param("now", Sql.ts(clock.instant()))
                .query((rs, n) -> new Candidate(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"),
                        rs.getInt("retention_days"))).list();
        for (Candidate c : due) {
            jdbc.sql("UPDATE run_attachment SET deleted_at = :now, purged_at = :now WHERE id = :id AND deleted_at IS NULL")
                    .param("now", Sql.ts(clock.instant())).param("id", c.id()).update();
            jdbc.sql("DELETE FROM run_attachment_content WHERE attachment_id = :id").param("id", c.id()).update();
            jdbc.sql("DELETE FROM attachment_ocr_result WHERE attachment_id = :id").param("id", c.id()).update();
            audit.write(new AuditWriter.Entry(c.ws(), AuditWriter.ActorType.SYSTEM, null, "attachment.purged",
                    "attachment", c.id().toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("runId", c.run().toString(), "attachmentId", c.id().toString(), "retentionDays", c.days())));
            events.append(c.ws(), c.run(), "ATTACHMENT_PURGED", null, null, Map.of("attachmentId", c.id().toString()));
        }
        return due.size();
    }

    // ---------------------------------------------------------------- OCR

    /** Runs the configured provider over a file the caller can see. A new request replaces the earlier result. */
    @Transactional
    public Ocr extract(Caller caller, UUID ws, UUID runId, UUID attachmentId) {
        RunAccess.Visible visible = operator(caller, ws, runId, "requestAttachmentOcr");
        if (!visible.dataVisible()) {
            throw ApiException.forbidden();
        }
        Map<String, Object> file = lockFile(ws, runId, attachmentId);
        requireReadable(file);
        byte[] bytes = jdbc.sql("SELECT content FROM run_attachment_content WHERE attachment_id = :id")
                .param("id", attachmentId).query(byte[].class).optional().orElseThrow(ApiException::notFound);
        OcrProvider.Result result;
        try {
            result = ocr.extract(bytes, (String) file.get("content_type"));
        } catch (RuntimeException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The text extraction provider could not read this file.");
        }
        String status = result.supported() ? "extracted" : "unsupported";
        jdbc.sql("""
                INSERT INTO attachment_ocr_result (attachment_id, workspace_id, provider, status, content_text, sha256,
                                                   requested_by, created_at)
                VALUES (:id, :ws, :provider, :status, :text, :sha, :by, :now)
                ON CONFLICT (attachment_id) DO UPDATE
                   SET provider = :provider, status = :status, content_text = :text, sha256 = :sha,
                       requested_by = :by, created_at = :now
                """).param("id", attachmentId).param("ws", ws).param("provider", ocr.id()).param("status", status)
                .param("text", result.text()).param("sha", sha256(bytes)).param("by", caller.subjectId())
                .param("now", Sql.ts(clock.instant())).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "attachment.ocr.requested",
                "attachment", attachmentId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("runId", runId.toString(), "attachmentId", attachmentId.toString(), "provider", ocr.id(),
                        "status", status)));
        return readOcr(attachmentId);
    }

    @Transactional(readOnly = true)
    public Ocr ocrResult(Caller caller, UUID ws, UUID runId, UUID attachmentId) {
        RunAccess.Visible visible = access.require(caller, ws, runId);
        if (!visible.dataVisible()) {
            throw ApiException.forbidden();
        }
        Map<String, Object> file = jdbc.sql("""
                SELECT file_name, content_type, scan_status, deleted_at, quarantined_at FROM run_attachment
                WHERE workspace_id = :ws AND run_id = :run AND id = :id
                """).param("ws", ws).param("run", runId).param("id", attachmentId)
                .query((rs, n) -> row(rs)).optional().orElseThrow(ApiException::notFound);
        requireReadable(file);
        return jdbc.sql("SELECT attachment_id FROM attachment_ocr_result WHERE attachment_id = :id").param("id", attachmentId)
                .query(UUID.class).optional().map(this::readOcr).orElseThrow(ApiException::notFound);
    }

    // ---------------------------------------------------------------- internals

    private Ocr readOcr(UUID attachmentId) {
        return jdbc.sql("SELECT provider, status, content_text, created_at FROM attachment_ocr_result WHERE attachment_id = :id")
                .param("id", attachmentId).query((rs, n) -> new Ocr(attachmentId, rs.getString("provider"),
                        rs.getString("status"), rs.getString("content_text"), Sql.instant(rs, "created_at")))
                .single();
    }

    private static void requireReadable(Map<String, Object> file) {
        if (file.get("deleted_at") != null) {
            throw ApiException.notFound();
        }
        if (file.get("quarantined_at") != null) {
            throw new ApiException(ProblemCode.ATTACHMENT_BLOCKED, "This file is quarantined while it is reviewed.");
        }
        if (AttachmentScanner.Status.of((String) file.get("scan_status")).blocksDownload()) {
            throw new ApiException(ProblemCode.ATTACHMENT_BLOCKED, "This file is blocked by the scan.");
        }
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_name", rs.getString("file_name"));
        m.put("content_type", rs.getString("content_type"));
        m.put("scan_status", rs.getString("scan_status"));
        m.put("deleted_at", rs.getTimestamp("deleted_at"));
        m.put("quarantined_at", rs.getTimestamp("quarantined_at"));
        return m;
    }

    private Map<String, Object> lockFile(UUID ws, UUID runId, UUID attachmentId) {
        return jdbc.sql("""
                SELECT file_name, content_type, scan_status, deleted_at, quarantined_at FROM run_attachment
                WHERE workspace_id = :ws AND run_id = :run AND id = :id FOR UPDATE
                """).param("ws", ws).param("run", runId).param("id", attachmentId).query((rs, n) -> row(rs)).optional()
                .orElseThrow(ApiException::notFound);
    }

    private RunAccess.Visible operator(Caller caller, UUID ws, UUID runId, String operation) {
        RunAccess.Visible visible = access.require(caller, ws, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, operation, true);
        return visible;
    }

    private void admin(Caller caller, UUID ws, String operation) {
        WorkspaceAccess workspace = policy.requireVisible(caller, ws);
        policy.require(workspace, Permission.WORKSPACE_MANAGE_ACCESS, operation, true);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
