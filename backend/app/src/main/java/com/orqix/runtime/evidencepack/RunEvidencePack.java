package com.orqix.runtime.evidencepack;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.internal.RunAccess;
import com.orqix.runtime.internal.RunStore;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Control evidence pack for one run (docs/27 section 3, Create): the pinned release identity and checksum, each human
 * step with assignee, decision and timestamps, SLA breaches, guarded operator corrections, integration effect outcomes
 * and the ordered run timeline. Control metadata only, like the JSON/CSV case evidence export.
 */
@Service
public class RunEvidencePack {

    static final String PAYLOAD_POLICY = "Control metadata only. No request values, form answers, attachments, "
            + "credentials, decision reasons, comments or operator notes; those stay in the platform record.";

    /** Operator interventions on a run, its tasks or its effects (audited, guarded commands). */
    static final List<String> CORRECTIONS = List.of("run.input-corrected", "task.input-corrected",
            "run.retry-requested", "effect.reconciled", "run.suspended", "run.resumed", "run.cancelled", "run.migrated",
            "run.projection-repaired", "run.signal-sent", "task.reassigned", "task.delegated", "run.move-requested",
            "run.move-rejected", "run.moved");

    private static final int LIMIT = 10_000;

    private final JdbcClient jdbc;
    private final org.springframework.beans.factory.ObjectProvider<com.orqix.integration.AiTaskPort> aiTasks;
    private final PolicyService policy;
    private final RunAccess runAccess;
    private final ReleaseQueries releases;
    private final SubjectService subjects;
    private final EvidenceRenderer renderer;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;

    public RunEvidencePack(JdbcClient jdbc, PolicyService policy, RunAccess runAccess,
            ReleaseQueries releases, SubjectService subjects, EvidenceRenderer renderer, AuditWriter audit, Json json,
            Clock clock, org.springframework.beans.factory.ObjectProvider<com.orqix.integration.AiTaskPort> aiTasks) {
        this.aiTasks = aiTasks;
        this.jdbc = jdbc;
        this.policy = policy;
        this.runAccess = runAccess;
        this.releases = releases;
        this.subjects = subjects;
        this.renderer = renderer;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public EvidenceRenderer.Rendered export(Caller caller, UUID ws, UUID runId, String format) {
        EvidenceRenderer.requireFormat(format);
        // Whoever can see the run (404 otherwise), like the case evidence export; the pack covers every step, so it
        // also needs run.read or audit.read ("export control evidence for requests they can access").
        RunAccess.Visible visible = runAccess.require(caller, ws, runId);
        WorkspaceAccess access = visible.access();
        if (!access.has(Permission.RUN_READ)) {
            policy.require(access, Permission.AUDIT_READ, "exportRunEvidencePack", true);
        }
        RunStore.RunRow run = visible.run();
        // Stabilize the event sequence for this export, without changing business state.
        jdbc.sql("SELECT id FROM workflow_run WHERE workspace_id = :ws AND id = :run FOR SHARE")
                .param("ws", ws).param("run", runId).query(UUID.class).single();
        ObjectNode pack = build(caller.subjectId(), access.workspace().id(), access.workspace().key(),
                access.workspace().name(), run);
        EvidenceDocument document = layout(pack);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "run.evidence-pack-exported", "run", runId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("format", format, "stepCount", pack.path("steps").size(), "eventCount",
                        pack.path("timeline").size())));
        return renderer.render(format, "run-" + (run.reference() == null ? runId : run.reference()) + "-evidence-pack",
                pack, document);
    }

    /**
     * CG-O07 (docs/build/S144): the pack of a run for its retention archive, generated by the system (no caller, no
     * access check and no audit here: the retention sweep audits the archive). Returns the JSON and PDF renderings.
     */
    @Transactional(readOnly = true)
    public Map<String, byte[]> archive(UUID ws, String workspaceKey, String workspaceName, RunStore.RunRow run) {
        ObjectNode pack = build(null, ws, workspaceKey, workspaceName, run);
        EvidenceDocument document = layout(pack);
        String base = "run-" + run.reference() + "-evidence-pack";
        return Map.of("json", renderer.render("json", base, pack, document).bytes(),
                "pdf", renderer.render("pdf", base, pack, document).bytes());
    }

    private ObjectNode build(UUID generatedBy, UUID ws, String workspaceKey, String workspaceName, RunStore.RunRow run) {
        ReleaseQueries.ReleaseView release = releases.require(ws, run.releaseId());
        Set<UUID> people = new LinkedHashSet<>();
        if (generatedBy != null) {
            people.add(generatedBy);
        }
        people.add(run.initiator());

        ObjectNode pack = json.object();
        pack.put("formatVersion", "1").put("kind", "run").put("generatedAt", clock.instant().toString());
        pack.putObject("workspace").put("id", ws.toString()).put("key", workspaceKey)
                .put("name", workspaceName);
        ObjectNode r = pack.putObject("run");
        r.put("id", run.id().toString()).put("reference", run.reference()).put("workflowKey", release.workflowKey())
                .put("workflowName", release.workflowName()).put("lifecycle", run.lifecycle())
                .put("outcome", run.outcome()).put("outcomeKind", run.outcomeKind())
                .put("startedBy", run.initiator().toString()).put("startedAt", run.createdAt().toString())
                .put("endedAt", run.endedAt() == null ? null : run.endedAt().toString());
        if (run.parentRunId() != null) {
            r.put("parentRunId", run.parentRunId().toString());
        }
        ObjectNode rel = pack.putObject("release");
        jdbc.sql("""
                SELECT release_number, semantic_hash, source_hash, compiler_version, published_at, manifest->>'bpmnHash' AS bpmn
                FROM workflow_release WHERE workspace_id = :ws AND id = :id
                """).param("ws", ws).param("id", run.releaseId()).query((rs, n) -> rel
                        .put("id", run.releaseId().toString()).put("workflowId", release.workflowId().toString())
                        .put("releaseNumber", rs.getInt("release_number"))
                        .put("semanticHash", rs.getString("semantic_hash").trim())
                        .put("sourceHash", rs.getString("source_hash").trim()).put("bpmnHash", rs.getString("bpmn"))
                        .put("compilerVersion", rs.getString("compiler_version"))
                        .put("publishedAt", Sql.instant(rs, "published_at").toString()))
                .single();

        ArrayNode steps = pack.putArray("steps");
        ArrayNode breaches = pack.putArray("slaBreaches");
        List<String> taskIds = new ArrayList<>();
        jdbc.sql("""
                SELECT t.engine_task_id, t.node_id, t.label, t.candidate_group_key, t.assignee_subject_id, t.status,
                       t.due_at, t.created_at, t.claimed_at, t.ended_at, d.action, d.actor_subject_id, d.decided_at,
                       d.actor_kind, d.ai_skill_key, d.ai_skill_version,
                       d.policy_flags::text AS flags, b.due_at AS breach_due, b.detected_at
                FROM task_context t
                LEFT JOIN task_decision d ON d.workspace_id = t.workspace_id AND d.engine_task_id = t.engine_task_id
                LEFT JOIN task_sla_breach b ON b.workspace_id = t.workspace_id AND b.task_id = t.engine_task_id
                WHERE t.workspace_id = :ws AND t.run_id = :run ORDER BY t.created_at, t.engine_task_id LIMIT 10001
                """).param("ws", ws).param("run", run.id()).query((rs, n) -> {
                    String taskId = rs.getString("engine_task_id");
                    taskIds.add(taskId);
                    ObjectNode step = steps.addObject().put("taskId", taskId).put("nodeId", rs.getString("node_id"))
                            .put("step", rs.getString("label")).put("group", rs.getString("candidate_group_key"))
                            .put("status", rs.getString("status")).put("createdAt", text(Sql.instant(rs, "created_at")))
                            .put("claimedAt", text(Sql.instant(rs, "claimed_at")))
                            .put("dueAt", text(Sql.instant(rs, "due_at")))
                            .put("endedAt", text(Sql.instant(rs, "ended_at")));
                    UUID assignee = Sql.uuid(rs, "assignee_subject_id");
                    step.put("assignee", assignee == null ? null : person(people, assignee));
                    UUID decidedBy = Sql.uuid(rs, "actor_subject_id");
                    step.put("decision", rs.getString("action"))
                            .put("decidedBy", decidedBy != null ? person(people, decidedBy)
                                    // S152: the AI reviewer of the step, named with its skill version.
                                    : "aiReviewer".equals(rs.getString("actor_kind")) ? "AI reviewer ("
                                    + rs.getString("ai_skill_key") + " v" + rs.getInt("ai_skill_version") + ")" : null)
                            .put("decidedAt", text(Sql.instant(rs, "decided_at")));
                    JsonNode flags = rs.getString("flags") == null ? json.mapper().createArrayNode()
                            : json.parse(rs.getString("flags"));
                    step.set("policyFlags", flags);
                    Instant detected = Sql.instant(rs, "detected_at");
                    step.put("slaBreached", detected != null);
                    if (detected != null) {
                        breaches.addObject().put("taskId", taskId).put("step", rs.getString("label"))
                                .put("dueAt", Sql.instant(rs, "breach_due").toString())
                                .put("detectedAt", detected.toString())
                                .put("endedAt", text(Sql.instant(rs, "ended_at")));
                    }
                    return step;
                }).list();
        bound(steps.size(), "steps");

        ArrayNode effects = pack.putArray("effects");
        List<String> effectIds = new ArrayList<>();
        jdbc.sql("""
                SELECT id, node_id, occurrence, connection_key, connection_version, operation, effect_class, state,
                       attempts, last_http_status, last_error_code, remote_reference, resolution->>'kind' AS resolution,
                       resolution->>'by' AS resolved_by, resolution->>'at' AS resolved_at, created_at, updated_at
                FROM effect_journal WHERE workspace_id = :ws AND run_id = :run ORDER BY created_at, id LIMIT 10001
                """).param("ws", ws).param("run", run.id()).query((rs, n) -> {
                    effectIds.add(Sql.uuid(rs, "id").toString());
                    ObjectNode effect = effects.addObject().put("effectId", Sql.uuid(rs, "id").toString())
                            .put("nodeId", rs.getString("node_id"))
                            .put("step", label(release, rs.getString("node_id"))).put("occurrence", rs.getInt("occurrence"))
                            .put("connection", rs.getString("connection_key") + " v" + rs.getInt("connection_version"))
                            .put("operation", rs.getString("operation")).put("effectClass", rs.getString("effect_class"))
                            .put("outcome", rs.getString("state")).put("attempts", rs.getInt("attempts"))
                            .put("remoteReference", rs.getString("remote_reference"))
                            .put("lastErrorCode", rs.getString("last_error_code"))
                            .put("resolution", rs.getString("resolution"))
                            .put("resolvedAt", rs.getString("resolved_at"))
                            .put("createdAt", Sql.instant(rs, "created_at").toString())
                            .put("updatedAt", Sql.instant(rs, "updated_at").toString());
                    int status = rs.getInt("last_http_status");
                    effect.put("lastHttpStatus", rs.wasNull() ? null : Integer.valueOf(status));
                    String by = rs.getString("resolved_by");
                    if (by != null) {
                        try {
                            effect.put("resolvedBy", person(people, UUID.fromString(by)));
                        } catch (IllegalArgumentException ignored) {
                            effect.put("resolvedBy", (String) null);
                        }
                    }
                    return effect;
                }).list();
        bound(effects.size(), "effects");

        // S134 (CG-A03): AI decisions, control facts only (no input values, extracted values or summaries).
        ArrayNode aiDecisions = pack.putArray("aiDecisions");
        com.orqix.integration.AiTaskPort ai = aiTasks.getIfAvailable();
        if (ai != null) {
            for (com.orqix.integration.AiTaskPort.DecisionView d : ai.decisions(ws, run.id(), false)) {
                ObjectNode item = aiDecisions.addObject().put("decisionId", d.id().toString())
                        .put("step", label(release, d.nodeId())).put("occurrence", d.occurrence())
                        .put("skill", d.skillKey() + " v" + d.skillVersion()).put("skillHash", d.skillHash())
                        .put("autonomy", d.autonomy()).put("effectiveAutonomy", d.effectiveAutonomy())
                        .put("model", d.modelUsed()).put("profile", d.profile()).put("promptHash", d.promptHash())
                        .put("inputFields", String.join(", ", d.inputFields())).put("inputDigest", d.inputDigest())
                        .put("status", d.status()).put("route", d.route())
                        .put("confidence", d.confidence() == null ? null : d.confidence().toPlainString())
                        .put("threshold", d.threshold() == null ? null : d.threshold().toPlainString())
                        .put("reviewSampled", d.reviewSampled()).put("humanOutcome", d.humanOutcome())
                        .put("humanBy", d.humanBy() == null ? null : person(people, d.humanBy()))
                        .put("costUsd", d.costUsd() == null ? null : d.costUsd().toPlainString())
                        .put("latencyMs", d.latencyMs()).put("unavailableReason", d.unavailableReason());
                if (d.output() != null && d.output().has("label")) {
                    item.put("aiValue", d.output().path("label").asString());
                } else if (d.output() != null && d.output().has("recommendation")) {
                    item.put("aiValue", d.output().path("recommendation").asString());
                } else if (d.output() != null && d.output().has("score")) {
                    item.put("aiValue", d.output().path("score").asString());
                }
                item.set("guardrails", d.guardrails());
            }
        }

        ArrayNode corrections = pack.putArray("corrections");
        jdbc.sql("""
                SELECT occurred_at, actor_type, actor_subject_id, action, resource_type, resource_id, outcome,
                       metadata::text AS metadata
                FROM audit_event WHERE workspace_id = :ws AND action IN (:actions) AND (
                    (resource_type = 'run' AND resource_id = :run)
                 OR (resource_type = 'task' AND resource_id IN (:tasks))
                 OR (resource_type = 'effect' AND resource_id IN (:effects)))
                ORDER BY occurred_at, id LIMIT 10001
                """).param("ws", ws).param("actions", CORRECTIONS).param("run", run.id().toString())
                .param("tasks", taskIds.isEmpty() ? List.of("-") : taskIds)
                .param("effects", effectIds.isEmpty() ? List.of("-") : effectIds)
                .query((rs, n) -> {
                    ObjectNode item = corrections.addObject().put("at", Sql.instant(rs, "occurred_at").toString())
                            .put("action", rs.getString("action")).put("outcome", rs.getString("outcome"))
                            .put("target", rs.getString("resource_type"));
                    UUID actor = Sql.uuid(rs, "actor_subject_id");
                    item.put("actor", actor == null ? rs.getString("actor_type") : person(people, actor));
                    item.set("details", json.parse(rs.getString("metadata")));
                    return item;
                }).list();
        bound(corrections.size(), "corrections");

        ArrayNode timeline = pack.putArray("timeline");
        jdbc.sql("""
                SELECT seq, type, node_id, actor_subject_id, occurred_at FROM run_event
                WHERE workspace_id = :ws AND run_id = :run ORDER BY seq LIMIT 10001
                """).param("ws", ws).param("run", run.id()).query((rs, n) -> {
                    ObjectNode item = timeline.addObject().put("sequence", rs.getLong("seq"))
                            .put("at", Sql.instant(rs, "occurred_at").toString()).put("type", rs.getString("type"))
                            .put("step", label(release, rs.getString("node_id")));
                    UUID actor = Sql.uuid(rs, "actor_subject_id");
                    item.put("actor", actor == null ? null : person(people, actor));
                    return item;
                }).list();
        bound(timeline.size(), "run events");

        ObjectNode summary = pack.putObject("summary");
        summary.put("humanSteps", steps.size()).put("decisions", count(steps, "decision"))
                .put("slaBreaches", breaches.size()).put("corrections", corrections.size())
                .put("effects", effects.size()).put("aiDecisions", aiDecisions.size()).put("effectsNeedingReview", effects.findValues("outcome").stream()
                        .filter(v -> "unknown".equals(v.asString()) || "permanentFailure".equals(v.asString())).count())
                .put("events", timeline.size());

        people.remove(null);
        Map<UUID, String> names = subjects.displayNames(people);
        if (generatedBy != null) {
            pack.set("generatedBy", ReleaseEvidencePack.subject(generatedBy, names));
        } else {
            pack.putObject("generatedBy").put("id", (String) null).put("displayName", "Orqix retention (system)");
        }
        r.set("startedBy", ReleaseEvidencePack.subject(run.initiator(), names));
        resolve(steps, names, "assignee", "decidedBy");
        resolve(effects, names, "resolvedBy");
        resolve(corrections, names, "actor");
        resolve(aiDecisions, names, "humanBy");
        resolve(timeline, names, "actor");
        pack.put("payloadPolicy", PAYLOAD_POLICY);
        return ReleaseEvidencePack.seal(json, pack);
    }

    private static final String PERSON = "subject:";

    /** Marks a subject id for name resolution once all people are known. */
    private static String person(Set<UUID> people, UUID id) {
        people.add(id);
        return PERSON + id;
    }

    private static void resolve(ArrayNode items, Map<UUID, String> names, String... fields) {
        for (JsonNode item : items) {
            for (String field : fields) {
                String value = item.path(field).asString("");
                if (value.startsWith(PERSON)) {
                    ((ObjectNode) item).set(field, ReleaseEvidencePack.subject(
                            UUID.fromString(value.substring(PERSON.length())), names));
                }
            }
        }
    }

    private static long count(ArrayNode items, String field) {
        long count = 0;
        for (JsonNode item : items) {
            if (item.hasNonNull(field)) {
                count++;
            }
        }
        return count;
    }

    private static String label(ReleaseQueries.ReleaseView release, String nodeId) {
        if (nodeId == null) {
            return null;
        }
        String label = release.nodeLabel(nodeId);
        return label == null ? nodeId : label;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static void bound(int size, String what) {
        if (size > LIMIT) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "More than " + LIMIT + " " + what
                    + " in this run. Use the controlled audit extraction process.");
        }
    }

    static EvidenceDocument layout(ObjectNode pack) {
        JsonNode run = pack.path("run");
        String title = "Run evidence pack: " + run.path("workflowName").asString() + ", request "
                + run.path("reference").asString(run.path("id").asString());
        List<EvidenceDocument.Section> sections = new ArrayList<>();
        sections.add(EvidenceDocument.fields("Run", null, pack, "run.reference", "Reference", "run.id", "Run id",
                "run.workflowName", "Workflow", "run.workflowKey", "Workflow key", "run.lifecycle", "Status",
                "run.outcome", "Outcome", "run.startedBy", "Started by", "run.startedAt", "Started", "run.endedAt",
                "Ended", "run.parentRunId", "Started by run", "workspace.name", "Workspace", "generatedAt",
                "Pack generated", "generatedBy", "Generated by"));
        sections.add(EvidenceDocument.fields("Release used", "The run is pinned to this release for its whole life.",
                pack, "release.releaseNumber", "Release number", "release.id", "Release id", "release.semanticHash",
                "Semantic hash", "release.sourceHash", "Source hash", "release.bpmnHash", "BPMN hash",
                "release.compilerVersion", "Compiler version", "release.publishedAt", "Published"));
        sections.add(EvidenceDocument.fields("Summary", null, pack.path("summary"), "humanSteps", "Human steps",
                "decisions", "Decisions", "slaBreaches", "SLA breaches", "corrections", "Operator corrections",
                "effects", "Integration calls", "effectsNeedingReview", "Calls needing review", "events",
                "Timeline events"));
        sections.add(EvidenceDocument.table("Human steps and decisions", null, pack.path("steps"),
                "No human steps in this run.", "step", "Step", "group", "Group", "assignee", "Assignee", "createdAt",
                "Created", "claimedAt", "Claimed", "dueAt", "Due", "endedAt", "Ended", "status", "Status", "decision",
                "Decision", "decidedBy", "Decided by", "decidedAt", "Decided", "slaBreached", "SLA breached"));
        sections.add(EvidenceDocument.table("SLA breaches", null, pack.path("slaBreaches"), "No SLA breaches.", "step",
                "Step", "dueAt", "Due", "detectedAt", "Breach detected", "endedAt", "Step ended"));
        sections.add(EvidenceDocument.table("Guarded corrections", "Operator interventions. Each was a guarded, audited command; values changed are not shown.",
                pack.path("corrections"), "No operator corrections.", "at", "When", "action", "Action", "actor", "By",
                "target", "On", "outcome", "Outcome", "details", "Details"));
        sections.add(EvidenceDocument.table("Integration effects", "Outcome of each external call. 'unknown' means the outcome could not be confirmed; an operator must reconcile it.",
                pack.path("effects"), "No integration calls.", "step", "Step", "connection", "Connection", "operation",
                "Operation", "outcome", "Outcome", "attempts", "Attempts", "lastHttpStatus", "Last HTTP", "remoteReference",
                "Remote reference", "resolution", "Reconciled as", "resolvedBy", "Reconciled by", "updatedAt", "Updated"));
        sections.add(EvidenceDocument.table("AI decisions", "Each AI task: pinned skill version, model, input digest"
                + " (no values), result, confidence and threshold, route, and the person who accepted or overrode it.",
                pack.path("aiDecisions"), "No AI decisions.", "step", "Step", "skill", "Skill", "model", "Model",
                "status", "Status", "aiValue", "AI result", "confidence", "Confidence", "route", "Route",
                "humanOutcome", "Human", "inputDigest", "Input digest"));
        sections.add(EvidenceDocument.table("Timeline", null, pack.path("timeline"), "No events.", "sequence", "#", "at",
                "When", "type", "Event", "step", "Step", "actor", "Actor"));
        return new EvidenceDocument(title, "Workspace " + pack.path("workspace").path("name").asString() + ". "
                + PAYLOAD_POLICY, List.copyOf(sections), ReleaseEvidencePack.integrity(pack));
    }
}
