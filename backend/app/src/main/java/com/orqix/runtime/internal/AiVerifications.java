package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.compiler.DocumentTemplates;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.AiTaskPort;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Verification tasks of document extraction (CG-A06, doc 29 section 7.11). An extraction below the threshold (or one
 * that {@code suggest} autonomy routes to a person) waits at its step; a person sees the document beside the fields
 * with confidence and source highlights and confirms or corrects each field. Built on the AI decision outcome API
 * (S134) rather than a Flowable user task: no BPMN changes, the step stays the triggerable AI task, and the submission
 * records the decision's human outcome, queues the corrections as an evaluation candidate and triggers the step with
 * the verified values as its outputs ({@code next}), or, when the person escalates, the AI values on
 * {@code needsReview}.
 *
 * <p>Who verifies: whoever may record AI decision outcomes on the run (a participant, {@code run.operate} or
 * {@code ai.evaluate}), never the request's initiator (a person does not verify the values of their own request).
 */
@Service
public class AiVerifications {

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final RunAccess access;
    private final PolicyService policy;
    private final RunStore runs;
    private final RunEvents events;
    private final EngineCommands engine;
    private final AiTaskPort ai;
    private final AuditWriter audit;

    public AiVerifications(JdbcClient jdbc, Json json, Clock clock, RunAccess access, PolicyService policy, RunStore runs,
            RunEvents events, EngineCommands engine, AiTaskPort ai, AuditWriter audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.access = access;
        this.policy = policy;
        this.runs = runs;
        this.events = events;
        this.engine = engine;
        this.ai = ai;
        this.audit = audit;
    }

    private record Row(UUID id, UUID runId, String nodeId, int occurrence, UUID decisionId, UUID attachmentId,
            String skillKey, int skillVersion, String documentType, String provider, BigDecimal threshold,
            ObjectNode payload, ObjectNode aiOutputs, String status, int version, List<String> corrected, UUID decidedBy,
            Instant decidedAt, Instant createdAt) {
    }

    private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Array corrected = rs.getArray("corrected");
        List<String> names = new ArrayList<>();
        if (corrected != null) {
            for (Object o : (Object[]) corrected.getArray()) {
                names.add((String) o);
            }
        }
        return new Row(Sql.uuid(rs, "id"), Sql.uuid(rs, "run_id"), rs.getString("node_id"), rs.getInt("occurrence"),
                Sql.uuid(rs, "decision_id"), Sql.uuid(rs, "attachment_id"), rs.getString("skill_key"),
                rs.getInt("skill_version"), rs.getString("document_type"), rs.getString("provider"),
                rs.getBigDecimal("threshold"), (ObjectNode) json.parse(rs.getString("payload")),
                (ObjectNode) json.parse(rs.getString("ai_outputs")), rs.getString("status"), rs.getInt("version"), names,
                Sql.uuid(rs, "decided_by"), Sql.instant(rs, "decided_at"), Sql.instant(rs, "created_at"));
    }

    // ================================================================== reads

    /** Open verifications of the workspace for people who verify across requests (run.operate or ai.evaluate). */
    @Transactional(readOnly = true)
    public ObjectNode queue(Caller caller, UUID workspaceId) {
        WorkspaceAccess workspace = policy.requireVisible(caller, workspaceId);
        if (!workspace.has(Permission.AI_EVALUATE)) {
            policy.require(workspace, Permission.RUN_OPERATE, "listAiVerifications", false);
        }
        ObjectNode result = json.object();
        ArrayNode items = result.putArray("items");
        jdbc.sql("""
                SELECT v.*, r.reference, r.initiator_subject_id FROM ai_document_verification v
                JOIN workflow_run r ON r.workspace_id = v.workspace_id AND r.id = v.run_id
                WHERE v.workspace_id = :ws AND v.status = 'open' ORDER BY v.created_at LIMIT 200
                """).param("ws", workspaceId).query((rs, n) -> {
            Row row = row(rs);
            ObjectNode o = summary(row);
            o.put("runReference", rs.getString("reference"));
            o.put("ownRequest", caller.subjectId().equals(Sql.uuid(rs, "initiator_subject_id")));
            return o;
        }).list().forEach(items::add);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode list(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = access.require(caller, workspaceId, runId);
        ObjectNode result = json.object();
        ArrayNode items = result.putArray("items");
        jdbc.sql("SELECT * FROM ai_document_verification WHERE workspace_id = :ws AND run_id = :run ORDER BY created_at")
                .param("ws", workspaceId).param("run", runId).query((rs, n) -> row(rs)).list()
                .forEach(r -> items.add(summary(r)));
        result.put("canVerify", mayVerify(visible));
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode get(Caller caller, UUID workspaceId, UUID runId, UUID verificationId) {
        RunAccess.Visible visible = access.require(caller, workspaceId, runId);
        if (!visible.dataVisible()) {
            throw ApiException.forbidden();
        }
        Row row = find(workspaceId, runId, verificationId, false);
        return detail(row, visible);
    }

    private Row find(UUID workspaceId, UUID runId, UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM ai_document_verification WHERE workspace_id = :ws AND run_id = :run AND id = :id"
                + (lock ? " FOR UPDATE" : "")).param("ws", workspaceId).param("run", runId).param("id", id)
                .query((rs, n) -> row(rs)).optional().orElseThrow(ApiException::notFound);
    }

    private ObjectNode summary(Row r) {
        ObjectNode o = json.object();
        o.put("id", r.id().toString()).put("runId", r.runId().toString()).put("nodeId", r.nodeId())
                .put("occurrence", r.occurrence()).put("decisionId", r.decisionId().toString())
                .put("skillKey", r.skillKey()).put("skillVersion", r.skillVersion())
                .put("documentType", r.documentType()).put("provider", r.provider()).put("status", r.status())
                .put("version", r.version());
        int attention = 0;
        for (JsonNode f : r.payload().path("fields")) {
            attention += f.path("needsAttention").asBoolean(false) ? 1 : 0;
        }
        o.put("fieldCount", r.payload().path("fields").size()).put("needsAttention", attention);
        o.put("createdAt", r.createdAt().toString());
        return o;
    }

    private boolean mayVerify(RunAccess.Visible visible) {
        return !visible.initiator() && (visible.participant() || visible.access().has(Permission.RUN_OPERATE)
                || visible.access().has(Permission.AI_EVALUATE));
    }

    private ObjectNode detail(Row r, RunAccess.Visible visible) {
        ObjectNode o = summary(r);
        o.put("threshold", r.threshold());
        o.put("canSubmit", "open".equals(r.status()) && mayVerify(visible) && visible.run().isActive());
        o.put("ownRequest", visible.initiator());
        record File(String name, String type, String scan, boolean deleted, boolean quarantined) {
        }
        File file = jdbc.sql("""
                SELECT file_name, content_type, scan_status, deleted_at IS NOT NULL AS deleted,
                       quarantined_at IS NOT NULL AS quarantined FROM run_attachment WHERE id = :id
                """).param("id", r.attachmentId()).query((rs, n) -> new File(rs.getString("file_name"),
                        rs.getString("content_type"), rs.getString("scan_status"), rs.getBoolean("deleted"),
                        rs.getBoolean("quarantined"))).single();
        boolean readable = !file.deleted() && !file.quarantined()
                && !AttachmentScanner.Status.of(file.scan()).blocksDownload();
        ObjectNode attachment = o.putObject("attachment");
        attachment.put("id", r.attachmentId().toString()).put("name", file.name()).put("contentType", file.type())
                .put("available", readable);
        if (!readable) {
            attachment.put("unavailableReason", file.deleted() ? "removed" : file.quarantined() ? "quarantined"
                    : "scanBlocked");
        } else {
            attachment.putNull("unavailableReason");
        }
        o.set("pages", r.payload().path("pages"));
        // Source snippets come from the S52 OCR result while it exists (quarantine and removal delete it).
        List<String> lines = new ArrayList<>();
        if (readable) {
            jdbc.sql("SELECT layout::text FROM attachment_ocr_result WHERE attachment_id = :id AND layout IS NOT NULL")
                    .param("id", r.attachmentId()).query(String.class).optional().ifPresent(text -> {
                        for (JsonNode p : json.parse(text).path("pages")) {
                            p.path("lines").forEach(l -> lines.add(l.path("text").asString("")));
                        }
                    });
        }
        ArrayNode fields = o.putArray("fields");
        for (JsonNode f : r.payload().path("fields")) {
            ObjectNode field = ((ObjectNode) f).deepCopy();
            int line = f.path("source").path("line").asInt(0);
            if (line >= 1 && line <= lines.size()) {
                field.put("sourceText", lines.get(line - 1));
            } else {
                field.putNull("sourceText");
            }
            fields.add(field);
        }
        ObjectNode decided = o.putObject("decision");
        if (r.decidedBy() == null) {
            decided.putNull("decidedBy");
            decided.putNull("decidedAt");
        } else {
            decided.put("decidedBy", r.decidedBy().toString()).put("decidedAt", r.decidedAt().toString());
        }
        ArrayNode corrected = decided.putArray("corrected");
        r.corrected().forEach(corrected::add);
        return o;
    }

    // ================================================================== submission

    @Transactional
    public ObjectNode submit(Caller caller, UUID workspaceId, UUID runId, UUID verificationId, JsonNode body) {
        RunAccess.Visible visible = access.require(caller, workspaceId, runId);
        if (!visible.dataVisible()) {
            throw ApiException.forbidden();
        }
        if (visible.initiator()) {
            throw new ApiException(ProblemCode.SELF_APPROVAL_DENIED, "You cannot verify the document of your own"
                    + " request.");
        }
        if (!mayVerify(visible)) {
            policy.require(visible.access(), Permission.RUN_OPERATE, "submitAiVerification", true);
        }
        if (body == null || !body.isObject()) {
            throw invalid("", "Send the verified fields.");
        }
        Row row = find(workspaceId, runId, verificationId, true);
        if (!"open".equals(row.status()) || body.path("expectedVersion").asInt(-1) != row.version()) {
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT, "open".equals(row.status())
                    ? "The verification changed since you loaded it." : "The verification is already closed.");
        }
        String outcome = body.path("outcome").asString("verified");
        if (!List.of("verified", "escalated").contains(outcome)) {
            throw invalid("/outcome", "Use verified or escalated.");
        }
        String reason = body.path("reason").isString() ? body.path("reason").asString().strip() : null;
        if (reason != null && reason.length() > 1000) {
            throw invalid("/reason", "Keep the reason under 1000 characters.");
        }
        if ("escalated".equals(outcome) && (reason == null || reason.isEmpty())) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Say why the document needs a further review.");
        }
        Map<String, JsonNode> declared = new LinkedHashMap<>();
        Map<String, DocumentTemplates.Field> types = new LinkedHashMap<>();
        Set<String> attention = new LinkedHashSet<>();
        for (JsonNode f : row.payload().path("fields")) {
            String name = f.path("name").asString();
            declared.put(name, f.path("value"));
            types.put(name, new DocumentTemplates.Field(name, f.path("type").asString("string"),
                    f.path("required").asBoolean(false), null, f.path("hint").asString(null)));
            if (f.path("needsAttention").asBoolean(false)) {
                attention.add(name);
            }
        }
        JsonNode answers = body.path("fields");
        if (!answers.isObject() && !answers.isMissingNode()) {
            throw invalid("/fields", "fields maps each field name to {action, value}.");
        }
        List<ApiException.FieldError> errors = new ArrayList<>();
        Map<String, JsonNode> finalValues = new LinkedHashMap<>();
        declared.forEach((name, value) -> {
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                finalValues.put(name, value);
            }
        });
        List<String> corrected = new ArrayList<>();
        List<String> confirmed = new ArrayList<>();
        for (var e : answers.properties()) {
            String name = e.getKey();
            String at = "/fields/" + name;
            DocumentTemplates.Field field = types.get(name);
            if (field == null) {
                errors.add(new ApiException.FieldError(at, "unknown", "The document has no field " + name + "."));
                continue;
            }
            String action = e.getValue().path("action").asString("");
            switch (action) {
                case "confirm" -> confirmed.add(name);
                case "correct", "clear" -> {
                    JsonNode value = "clear".equals(action) ? JsonNodeFactory.instance.nullNode()
                            : e.getValue().path("value");
                    if (value.isNull() || value.isMissingNode()) {
                        if (field.required() && "verified".equals(outcome)) {
                            errors.add(new ApiException.FieldError(at, "required", "This field is required."));
                            continue;
                        }
                        finalValues.remove(name);
                    } else {
                        String problem = typeProblem(field.type(), value);
                        if (problem != null) {
                            errors.add(new ApiException.FieldError(at + "/value", "type", problem));
                            continue;
                        }
                        finalValues.put(name, normalize(field.type(), value));
                    }
                    if (!same(declared.get(name), finalValues.get(name))) {
                        corrected.add(name);
                    } else {
                        confirmed.add(name);
                    }
                }
                default -> errors.add(new ApiException.FieldError(at + "/action", "invalid",
                        "Use confirm, correct or clear."));
            }
        }
        if ("verified".equals(outcome)) {
            for (String name : attention) {
                if (!answers.has(name)) {
                    errors.add(new ApiException.FieldError("/fields/" + name, "required",
                            "Confirm or correct this field: its value needs attention."));
                }
            }
            for (DocumentTemplates.Field f : types.values()) {
                if (f.required() && !finalValues.containsKey(f.name()) && !answers.has(f.name())) {
                    errors.add(new ApiException.FieldError("/fields/" + f.name(), "required",
                            "This required field has no value."));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some fields cannot be accepted.")
                    .withFieldErrors(errors);
        }
        List<DocumentTemplates.Field> fields = new ArrayList<>(types.values());
        List<DocumentTemplates.Problem> problems = "verified".equals(outcome) ? DocumentTemplates.validate(fields,
                DocumentTemplates.builtIn(row.documentType()).map(DocumentTemplates.Template::checks)
                        .orElse(List.of()).stream().filter(c -> types.containsKey(c.target())).toList(),
                finalValues, List.of(), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)) : List.of();
        if (!problems.isEmpty() && !body.path("acknowledgeProblems").asBoolean(false)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some values fail the document's checks. Correct"
                    + " them or acknowledge the problems.").withFieldErrors(problems.stream()
                    .map(p -> new ApiException.FieldError("/fields/" + p.field(), p.code(), p.message())).toList());
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The request is no longer active.");
        }
        Instant now = clock.instant();
        ObjectNode verifiedFields = json.object();
        finalValues.forEach(verifiedFields::set);
        jdbc.sql("""
                UPDATE ai_document_verification SET status = :status, version = version + 1, corrected = :corrected,
                       decided_by = :by, decided_at = :now
                WHERE id = :id
                """).param("status", outcome).param("corrected", corrected.toArray(String[]::new))
                .param("by", caller.subjectId()).param("now", Sql.ts(now)).param("id", row.id()).update();
        ai.recordVerification(workspaceId, runId, row.decisionId(), verifiedFields, corrected, reason,
                caller.subjectId());
        ObjectNode outputs = row.aiOutputs().deepCopy();
        types.keySet().forEach(outputs::remove);
        finalValues.forEach(outputs::set);
        String route = "verified".equals(outcome) ? "next" : "needsReview";
        outputs.put("status", "verified".equals(outcome) ? "verified" : "needsReview");
        runs.mutate(workspaceId, runId, m -> {
            m.removeStep(row.nodeId());
            m.settle();
        });
        events.append(workspaceId, runId, "AI_VERIFICATION_COMPLETED", row.nodeId(), caller.subjectId(),
                Map.of("verificationId", row.id().toString(), "outcome", outcome, "corrected", corrected.size(),
                        "confirmed", confirmed.size()));
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.verification.submitted", "aiVerification", row.id().toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("runId", runId.toString(), "verificationId", row.id().toString(), "outcome", outcome,
                        "corrected", corrected.size(), "confirmed", confirmed.size())));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(ProcessData.stepVariable(row.nodeId()), json.write(outputs));
        variables.put(AiTaskJobs.routeVariable(row.nodeId()), route);
        engine.resumeChild(run.engineProcessInstanceId(), row.nodeId(), variables);
        return detail(find(workspaceId, runId, verificationId, false), access.require(caller, workspaceId, runId));
    }

    private static String typeProblem(String type, JsonNode value) {
        boolean ok = switch (type) {
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "date" -> value.isString() && value.asString().matches("^\\d{4}-\\d{2}-\\d{2}$") && date(value);
            default -> value.isString() && value.asString().length() <= 2000;
        };
        return ok ? null : "date".equals(type) ? "Write the date as YYYY-MM-DD." : "The value must be a " + type + ".";
    }

    private static boolean date(JsonNode value) {
        try {
            LocalDate.parse(value.asString());
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private static JsonNode normalize(String type, JsonNode value) {
        return "string".equals(type) ? JsonNodeFactory.instance.stringNode(value.asString().strip()) : value;
    }

    private static boolean same(JsonNode a, JsonNode b) {
        boolean aEmpty = a == null || a.isNull() || a.isMissingNode();
        boolean bEmpty = b == null || b.isNull() || b.isMissingNode();
        if (aEmpty || bEmpty) {
            return aEmpty && bEmpty;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.equals(b);
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }
}
