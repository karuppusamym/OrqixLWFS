package com.orqix.operations.retention;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.runtime.RunRetention;
import com.orqix.shared.json.Json;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the archive of one ended request (CG-O07, docs/build/S144): a ZIP with the control evidence pack (JSON and
 * PDF), the request's rows in the exit-export layout (JSON Lines, same field names as {@code history/*.jsonl} of the
 * workspace exit export), its audit events, its files when the policy keeps them, a manifest and {@code SHA256SUMS}.
 * Entries are written in a fixed order with a fixed entry timestamp.
 */
@Component
public class RunArchiver {

    public static final String FORMAT = "orqix-run-archive";
    public static final int FORMAT_VERSION = 1;

    public record Policy(int keepDays, boolean includeRunData, boolean includeFiles) {
    }

    public record Built(byte[] bytes, String sha256, Map<String, Integer> counts) {
    }

    /** The archive is larger than {@code orqix.retention.max-archive-bytes}. */
    public static final class TooLarge extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TooLarge() {
            super("archive_too_large");
        }
    }

    private final JdbcClient jdbc;
    private final RunRetention runs;
    private final RetentionSettings settings;
    private final Json json;
    private final Clock clock;

    public RunArchiver(JdbcClient jdbc, RunRetention runs, RetentionSettings settings, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
    }

    /** Object key: {@code <workspace>/<workflow>/<yyyy>/<mm>/<reference>-<runId>.zip}, lower case. */
    public static String key(String workspaceKey, String workflowKey, RunRetention.Run run) {
        String ended = run.endedAt().toString();
        return (workspaceKey + "/" + workflowKey + "/" + ended.substring(0, 4) + "/" + ended.substring(5, 7) + "/"
                + run.reference() + "-" + run.id() + ".zip").toLowerCase(Locale.ROOT);
    }

    public Built build(UUID ws, String workspaceKey, String workspaceName, String workflowKey, RunRetention.Run run,
            UUID rootRunId, Policy policy) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, byte[]> pack = runs.evidencePack(ws, workspaceKey, workspaceName, run.id());
        files.put("evidence-pack.json", pack.get("json"));
        files.put("evidence-pack.pdf", pack.get("pdf"));

        Map<String, Object> p = Map.of("ws", ws, "run", run.id());
        // CG-I05 (S151): archives mask personal and sensitive answers like every export (V452 orqix_mask_input).
        String data = policy.includeRunData()
                ? ", orqix_mask_input(r.input, rel.definition) AS \"input\", r.output AS \"output\"" : "";
        lines(files, counts, "history/runs.jsonl", """
                SELECT r.id AS "runId", r.reference AS "reference", r.workflow_id AS "workflowId",
                       w.key AS "workflowKey", rel.release_number AS "releaseNumber", r.business_key AS "businessKey",
                       r.initiator_subject_id AS "initiatorId", s.display_name AS "initiatorName",
                       r.lifecycle AS "lifecycle", r.outcome AS "outcome", r.outcome_kind AS "outcomeKind",
                       r.current_steps AS "currentSteps", r.created_at AS "createdAt", r.updated_at AS "updatedAt",
                       r.ended_at AS "endedAt", r.parent_run_id AS "parentRunId", r.parent_node_id AS "parentNodeId"
                """ + data + """
                 FROM workflow_run r
                JOIN workflow w ON w.id = r.workflow_id
                JOIN workflow_release rel ON rel.id = r.release_id
                LEFT JOIN subject s ON s.id = r.initiator_subject_id
                WHERE r.workspace_id = :ws AND r.id = :run""", p);
        lines(files, counts, "history/tasks.jsonl", """
                SELECT t.engine_task_id AS "taskId", t.run_id AS "runId", t.node_id AS "nodeId", t.label AS "label",
                       t.candidate_group_key AS "candidateGroup", t.assignee_subject_id AS "assigneeId",
                       s.display_name AS "assigneeName", t.status AS "status", t.priority AS "priority",
                       t.due_at AS "dueAt", t.created_at AS "createdAt", t.claimed_at AS "claimedAt",
                       t.ended_at AS "endedAt"
                FROM task_context t LEFT JOIN subject s ON s.id = t.assignee_subject_id
                WHERE t.workspace_id = :ws AND t.run_id = :run ORDER BY t.created_at, t.engine_task_id""", p);
        lines(files, counts, "history/decisions.jsonl", """
                SELECT d.id AS "decisionId", d.engine_task_id AS "taskId", d.run_id AS "runId", d.node_id AS "nodeId",
                       d.actor_subject_id AS "actorId", s.display_name AS "actorName", d.action AS "action",
                       d.reason AS "reason", d.comment AS "comment", d.policy_flags AS "policyFlags",
                       d.decided_at AS "decidedAt"
                FROM task_decision d LEFT JOIN subject s ON s.id = d.actor_subject_id
                WHERE d.workspace_id = :ws AND d.run_id = :run ORDER BY d.decided_at, d.id""", p);
        String eventData = policy.includeRunData() ? ", e.data AS \"data\"" : "";
        lines(files, counts, "history/run-events.jsonl", """
                SELECT e.run_id AS "runId", e.seq AS "seq", e.type AS "type", e.node_id AS "nodeId",
                       e.actor_subject_id AS "actorId", s.display_name AS "actorName", e.occurred_at AS "occurredAt"
                """ + eventData + """
                 FROM run_event e LEFT JOIN subject s ON s.id = e.actor_subject_id
                WHERE e.workspace_id = :ws AND e.run_id = :run ORDER BY e.seq""", p);
        String effectData = policy.includeRunData() ? ", e.result AS \"result\"" : "";
        lines(files, counts, "history/effects.jsonl", """
                SELECT e.id AS "effectId", e.node_id AS "nodeId", e.occurrence AS "occurrence",
                       e.connection_key AS "connectionKey", e.connection_version AS "connectionVersion",
                       e.operation AS "operation", e.effect_class AS "effectClass", e.state AS "state",
                       e.attempts AS "attempts", e.last_http_status AS "lastHttpStatus",
                       e.last_error_code AS "lastErrorCode", e.remote_reference AS "remoteReference",
                       e.resolution AS "resolution", e.created_at AS "createdAt", e.updated_at AS "updatedAt"
                """ + effectData + """
                 FROM effect_journal e WHERE e.workspace_id = :ws AND e.run_id = :run ORDER BY e.created_at, e.id""", p);
        lines(files, counts, "audit/audit-events.jsonl", """
                SELECT a.id AS "auditId", a.occurred_at AS "occurredAt", a.actor_type AS "actorType",
                       a.actor_subject_id AS "actorId", s.display_name AS "actorName", a.action AS "action",
                       a.resource_type AS "resourceType", a.resource_id AS "resourceId", a.outcome AS "outcome",
                       a.correlation_id AS "correlationId", a.metadata AS "metadata"
                FROM audit_event a LEFT JOIN subject s ON s.id = a.actor_subject_id
                WHERE a.workspace_id = :ws AND (
                      (a.resource_type = 'run' AND a.resource_id = CAST(:run AS text))
                   OR (a.resource_type = 'task' AND a.resource_id IN (
                          SELECT engine_task_id FROM task_context WHERE workspace_id = :ws AND run_id = :run))
                   OR (a.resource_type = 'effect' AND a.resource_id IN (
                          SELECT id::text FROM effect_journal WHERE workspace_id = :ws AND run_id = :run)))
                ORDER BY a.occurred_at, a.id""", p);
        attachments(files, counts, ws, run.id(), policy.includeFiles());

        ObjectNode manifest = json.object();
        manifest.put("format", FORMAT).put("formatVersion", FORMAT_VERSION)
                .put("generatedAt", clock.instant().toString());
        manifest.putObject("workspace").put("id", ws.toString()).put("key", workspaceKey).put("name", workspaceName);
        manifest.putObject("run").put("id", run.id().toString()).put("reference", run.reference())
                .put("workflowId", run.workflowId().toString()).put("workflowKey", workflowKey)
                .put("lifecycle", run.lifecycle()).put("endedAt", run.endedAt().toString())
                .put("rootRunId", rootRunId.toString())
                .put("parentRunId", run.parentRunId() == null ? null : run.parentRunId().toString());
        manifest.putObject("policy").put("keepDays", policy.keepDays()).put("includeRunData", policy.includeRunData())
                .put("includeFiles", policy.includeFiles());
        ObjectNode countNode = manifest.putObject("counts");
        counts.forEach(countNode::put);
        manifest.put("excluded", "Credentials, secrets, engine-internal tables (Flowable ACT_*)"
                + (policy.includeRunData() ? "" : ", request values (input, output, event data, effect results)")
                + (policy.includeFiles() ? "" : ", file contents") + ".");
        ArrayNode list = manifest.putArray("files");
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            String sha = sha256(f.getValue());
            list.addObject().put("path", f.getKey()).put("bytes", f.getValue().length).put("sha256", sha);
            sums.append(sha).append("  ").append(f.getKey()).append('\n');
        }
        Map<String, byte[]> all = new LinkedHashMap<>();
        all.put("manifest.json", json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest)
                .getBytes(StandardCharsets.UTF_8));
        all.putAll(files);
        all.put("SHA256SUMS", sums.toString().getBytes(StandardCharsets.UTF_8));
        byte[] zip = zip(all);
        return new Built(zip, sha256(zip), counts);
    }

    private void lines(Map<String, byte[]> files, Map<String, Integer> counts, String path, String select,
            Map<String, Object> params) {
        List<String> rows = jdbc.sql("SELECT row_to_json(x)::text FROM (" + select + ") x").params(params)
                .query(String.class).list();
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            out.append(row).append('\n');
        }
        put(files, path, out.toString().getBytes(StandardCharsets.UTF_8));
        counts.put(path.substring(path.indexOf('/') + 1, path.lastIndexOf('.')), rows.size());
    }

    private record Attachment(UUID id, String fileName, boolean removed) {
    }

    private void attachments(Map<String, byte[]> files, Map<String, Integer> counts, UUID ws, UUID run,
            boolean includeFiles) {
        Map<String, Object> p = Map.of("ws", ws, "run", run);
        lines(files, counts, "files/attachments.jsonl", """
                SELECT a.id AS "attachmentId", a.field_key AS "fieldKey", a.file_name AS "fileName",
                       a.content_type AS "contentType", a.size_bytes AS "sizeBytes", a.sha256 AS "sha256",
                       a.scan_status AS "scanStatus", a.uploaded_by AS "uploadedBy", a.created_at AS "createdAt",
                       a.deleted_at AS "deletedAt", a.quarantined_at AS "quarantinedAt", a.purged_at AS "purgedAt"
                FROM run_attachment a WHERE a.workspace_id = :ws AND a.run_id = :run ORDER BY a.created_at, a.id""", p);
        if (!includeFiles) {
            return;
        }
        List<Attachment> list = jdbc.sql("""
                SELECT id, file_name, (deleted_at IS NOT NULL OR purged_at IS NOT NULL) AS removed
                FROM run_attachment WHERE workspace_id = :ws AND run_id = :run ORDER BY created_at, id
                """).params(p).query((rs, n) -> new Attachment(rs.getObject("id", UUID.class),
                        rs.getString("file_name"), rs.getBoolean("removed"))).list();
        int stored = 0;
        for (Attachment a : list) {
            if (a.removed()) {
                continue;
            }
            byte[] content = jdbc.sql("SELECT content FROM run_attachment_content WHERE attachment_id = :id")
                    .param("id", a.id()).query(byte[].class).optional().orElse(null);
            if (content == null) {
                continue;
            }
            put(files, "files/" + a.id() + "/" + safeName(a.fileName()), content);
            stored++;
        }
        counts.put("fileContents", stored);
    }

    private void put(Map<String, byte[]> files, String path, byte[] bytes) {
        long total = bytes.length;
        for (byte[] b : files.values()) {
            total += b.length;
        }
        if (total > settings.maxArchiveBytes()) {
            throw new TooLarge();
        }
        files.put(path, bytes);
    }

    static String safeName(String name) {
        String cleaned = name == null ? "file" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        cleaned = cleaned.replaceAll("^\\.+", "_");
        return cleaned.isEmpty() ? "file" : cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Names of the entries an archive contains, for tests and the runbook. */
    static List<String> entries(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        return names;
    }
}
