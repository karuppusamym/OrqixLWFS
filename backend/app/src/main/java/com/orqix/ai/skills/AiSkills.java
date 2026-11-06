package com.orqix.ai.skills;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.ai.provider.ProviderRegistry;
import com.orqix.audit.AuditWriter;
import com.orqix.compiler.AiSkillContracts;
import com.orqix.compiler.CompileContext;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI skills as shared, versioned assets (CG-A01, docs/build/S134 section 2): a designer ({@code workflow.edit}) writes
 * and submits a version; a different person holding the grant-only {@code ai.approve-skill} approves it. Approved
 * versions are immutable; a change is a new version. Also evaluation suites (shared test assets) and the workspace AI
 * policy (residency, retention, low-risk labels, field classification actions).
 */
@Service
public class AiSkills {

    /** Days an evaluation run counts for the publish gate (doc 29 section 7.7). */
    public static final Duration GATE_WINDOW = Duration.ofDays(30);
    /** Default actions per field classification (section 7.4 stage 2). */
    public static final Map<String, String> DEFAULT_ACTIONS = Map.of("public", "send", "internal", "send",
            "customer-text", "mask", "personal", "hash", "sensitive", "drop");
    private static final Set<String> ACTIONS = Set.of("send", "mask", "hash", "drop");

    /** The workspace AI policy. */
    public record Policy(boolean regulated, List<String> residency, String inputRetention, int retentionDays,
            List<String> lowRiskLabels, boolean allowAutoWithReview, Map<String, String> classificationActions,
            int version, Instant updatedAt) {

        public String action(String classification) {
            if (classification == null) {
                return "send";
            }
            return classificationActions.getOrDefault(classification, DEFAULT_ACTIONS.getOrDefault(classification,
                    "send"));
        }
    }

    /** A stored skill version. */
    public record Version(UUID id, UUID skillId, String skillKey, String skillName, int version, String status,
            JsonNode document, String contentHash, String mode, String modelProfile, UUID authorId, Instant submittedAt,
            UUID approverId, Instant approvedAt, String autonomyCeiling, boolean driftFallback, String driftReason,
            Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final ProviderRegistry providers;

    public AiSkills(JdbcClient jdbc, Json json, Clock clock, PolicyService policy, AuditWriter audit,
            ProviderRegistry providers) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.audit = audit;
        this.providers = providers;
    }

    // ================================================================== access

    WorkspaceAccess readable(Caller caller, UUID workspaceId, String operationId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.WORKFLOW_READ) && !access.has(Permission.AI_EVALUATE)
                && !access.has(Permission.AI_APPROVE_SKILL)) {
            policy.require(access, Permission.WORKFLOW_READ, operationId, false);
        }
        return access;
    }

    WorkspaceAccess editor(Caller caller, UUID workspaceId, String operationId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, operationId, true);
        return access;
    }

    // ================================================================== skills and versions

    @Transactional(readOnly = true)
    public ObjectNode list(Caller caller, UUID workspaceId) {
        readable(caller, workspaceId, "listAiSkills");
        ObjectNode result = json.object();
        ArrayNode items = result.putArray("items");
        List<UUID> skills = jdbc.sql("SELECT id FROM ai_skill WHERE workspace_id = :ws ORDER BY key")
                .param("ws", workspaceId).query(UUID.class).list();
        for (UUID id : skills) {
            items.add(skillView(workspaceId, id, false));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode get(Caller caller, UUID workspaceId, String key) {
        readable(caller, workspaceId, "getAiSkill");
        return skillView(workspaceId, skillId(workspaceId, key).orElseThrow(ApiException::notFound), true);
    }

    /** Creates a skill with its first draft version. */
    @Transactional
    public ObjectNode create(Caller caller, UUID workspaceId, JsonNode body) {
        editor(caller, workspaceId, "createAiSkill");
        JsonNode document = checkedSkill(body == null ? null : body.path("document"));
        String key = document.path("key").asString();
        UUID skillId = Ids.newId();
        Instant now = now();
        try {
            jdbc.sql("""
                    INSERT INTO ai_skill (id, workspace_id, key, name, owner_id, created_at)
                    VALUES (:id, :ws, :key, :name, :owner, :now)
                    """).param("id", skillId).param("ws", workspaceId).param("key", key)
                    .param("name", document.path("name").asString()).param("owner", caller.subjectId())
                    .param("now", Sql.ts(now)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "An AI skill with this key exists already.");
        }
        insertVersion(workspaceId, skillId, 1, document, caller.subjectId(), now);
        audit(workspaceId, caller, "ai.skill.version.created", key, 1, Map.of("mode", document.path("mode").asString()));
        return skillView(workspaceId, skillId, true);
    }

    /** Adds a draft version (copy and change); at most one draft at a time. */
    @Transactional
    public ObjectNode newVersion(Caller caller, UUID workspaceId, String key, JsonNode body) {
        editor(caller, workspaceId, "createAiSkillVersion");
        UUID skillId = lockSkill(workspaceId, key);
        JsonNode document = checkedSkill(body == null ? null : body.path("document"));
        sameKey(document, key);
        if (jdbc.sql("SELECT count(*) FROM ai_skill_version WHERE skill_id = :id AND status IN ('draft', 'submitted')")
                .param("id", skillId).query(Integer.class).single() > 0) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT,
                    "This skill already has a version in progress. Change it, or withdraw it first.");
        }
        int next = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM ai_skill_version WHERE skill_id = :id")
                .param("id", skillId).query(Integer.class).single();
        insertVersion(workspaceId, skillId, next, document, caller.subjectId(), now());
        audit(workspaceId, caller, "ai.skill.version.created", key, next, Map.of("mode", document.path("mode").asString()));
        return versionView(requireVersion(workspaceId, key, next), true);
    }

    /** Replaces the document of a draft (or a submitted version, which returns it to draft). */
    @Transactional
    public ObjectNode update(Caller caller, UUID workspaceId, String key, int version, JsonNode body) {
        editor(caller, workspaceId, "updateAiSkillVersion");
        lockSkill(workspaceId, key);
        Version v = requireVersion(workspaceId, key, version);
        if (!Set.of("draft", "submitted").contains(v.status())) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT,
                    "Approved versions do not change. Create a new version instead.");
        }
        JsonNode document = checkedSkill(body == null ? null : body.path("document"));
        sameKey(document, key);
        jdbc.sql("""
                UPDATE ai_skill_version SET document = CAST(:doc AS jsonb), content_hash = :hash, mode = :mode,
                       model_profile = :profile, status = 'draft', submitted_at = NULL, author_id = :author,
                       updated_at = :now
                WHERE id = :id
                """).param("doc", json.write(document)).param("hash", AiSkillContracts.contentHash(document))
                .param("mode", document.path("mode").asString()).param("profile", document.path("modelProfile").asString())
                .param("author", caller.subjectId()).param("now", Sql.ts(now())).param("id", v.id()).update();
        jdbc.sql("UPDATE ai_skill SET name = :name WHERE id = :id").param("name", document.path("name").asString())
                .param("id", v.skillId()).update();
        audit(workspaceId, caller, "ai.skill.version.updated", key, version, Map.of());
        return versionView(requireVersion(workspaceId, key, version), true);
    }

    @Transactional
    public ObjectNode submit(Caller caller, UUID workspaceId, String key, int version) {
        editor(caller, workspaceId, "submitAiSkillVersion");
        lockSkill(workspaceId, key);
        Version v = requireVersion(workspaceId, key, version);
        if (!"draft".equals(v.status())) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT, "Only a draft can be submitted for approval.");
        }
        try {
            providers.resolve(v.modelProfile(), List.of());
        } catch (ProviderRegistry.ProfileRefusedException e) {
            throw invalid("/document/modelProfile", e.getMessage());
        }
        jdbc.sql("UPDATE ai_skill_version SET status = 'submitted', submitted_at = :now, updated_at = :now WHERE id = :id")
                .param("now", Sql.ts(now())).param("id", v.id()).update();
        audit(workspaceId, caller, "ai.skill.version.submitted", key, version, Map.of());
        return versionView(requireVersion(workspaceId, key, version), true);
    }

    /** Approves a submitted version: fresh {@code ai.approve-skill}, never the author (four eyes), reason required. */
    @Transactional
    public ObjectNode approve(Caller caller, UUID workspaceId, String key, int version, JsonNode body) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.AI_APPROVE_SKILL, "approveAiSkillVersion", true);
        lockSkill(workspaceId, key);
        Version v = requireVersion(workspaceId, key, version);
        String reason = body == null ? "" : body.path("reason").asString("").strip();
        if (reason.isEmpty()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Say why this skill version may be used.");
        }
        if (!"submitted".equals(v.status())) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT, "Only a submitted version can be approved.");
        }
        if (v.authorId().equals(caller.subjectId())) {
            throw new ApiException(ProblemCode.SELF_APPROVAL_DENIED,
                    "A skill version must be approved by someone other than its author.");
        }
        Instant now = now();
        // Earlier approved versions stay approved: releases that pinned them keep running. New publishes pin the
        // highest approved version unless the step pins one; retiring a version stops it everywhere (kill switch).
        jdbc.sql("""
                UPDATE ai_skill_version SET status = 'approved', approver_id = :by, approved_at = :now,
                       approval_reason = :reason, updated_at = :now
                WHERE id = :id
                """).param("by", caller.subjectId()).param("now", Sql.ts(now)).param("reason", truncate(reason, 1000))
                .param("id", v.id()).update();
        audit(workspaceId, caller, "ai.skill.version.approved", key, version, Map.of("contentHash", v.contentHash()));
        return versionView(requireVersion(workspaceId, key, version), true);
    }

    /** Retires a version (no new publish may pin it; running releases keep it). */
    @Transactional
    public ObjectNode retire(Caller caller, UUID workspaceId, String key, int version) {
        editor(caller, workspaceId, "retireAiSkillVersion");
        lockSkill(workspaceId, key);
        Version v = requireVersion(workspaceId, key, version);
        if ("retired".equals(v.status())) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT, "This version is already retired.");
        }
        jdbc.sql("UPDATE ai_skill_version SET status = 'retired', updated_at = :now WHERE id = :id")
                .param("now", Sql.ts(now())).param("id", v.id()).update();
        audit(workspaceId, caller, "ai.skill.version.retired", key, version, Map.of());
        return versionView(requireVersion(workspaceId, key, version), true);
    }

    // ================================================================== evaluation suites

    @Transactional(readOnly = true)
    public ObjectNode listSuites(Caller caller, UUID workspaceId) {
        readable(caller, workspaceId, "listAiEvalSuites");
        ObjectNode result = json.object();
        ArrayNode items = result.putArray("items");
        jdbc.sql("""
                SELECT key, skill_key, name, case_count, revision, content_hash, updated_at FROM ai_eval_suite
                WHERE workspace_id = :ws ORDER BY key
                """).param("ws", workspaceId).query((rs, n) -> {
            ObjectNode item = items.addObject();
            item.put("key", rs.getString("key")).put("skillKey", rs.getString("skill_key"))
                    .put("name", rs.getString("name")).put("caseCount", rs.getInt("case_count"))
                    .put("revision", rs.getInt("revision")).put("contentHash", rs.getString("content_hash"))
                    .put("updatedAt", Sql.instant(rs, "updated_at").toString());
            return item;
        }).list();
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode getSuite(Caller caller, UUID workspaceId, String key) {
        readable(caller, workspaceId, "getAiEvalSuite");
        return suiteView(workspaceId, key).orElseThrow(ApiException::notFound);
    }

    /**
     * Creates or replaces a suite. Cases are synthetic or deliberately redacted; nothing copies production data in.
     */
    @Transactional
    public ObjectNode saveSuite(Caller caller, UUID workspaceId, String key, JsonNode body) {
        editor(caller, workspaceId, "saveAiEvalSuite");
        JsonNode document = body == null ? null : body.path("document");
        if (document == null || !document.isObject()) {
            throw invalid("/document", "Send the evaluation suite document.");
        }
        List<AiSkillContracts.Problem> problems = AiSkillContracts.checkSuite(document);
        if (problems.isEmpty() && !key.equals(document.path("key").asString())) {
            problems = List.of(new AiSkillContracts.Problem("/key", "The suite key must be " + key + "."));
        }
        refuse(problems);
        Instant now = now();
        int updated = jdbc.sql("""
                UPDATE ai_eval_suite SET document = CAST(:doc AS jsonb), content_hash = :hash, name = :name,
                       skill_key = :skill, case_count = :cases, revision = revision + 1, updated_by = :by, updated_at = :now
                WHERE workspace_id = :ws AND key = :key
                """).param("doc", json.write(document)).param("hash", AiSkillContracts.contentHash(document))
                .param("name", document.path("name").asString()).param("skill", document.path("skillKey").asString())
                .param("cases", document.path("cases").size()).param("by", caller.subjectId()).param("now", Sql.ts(now))
                .param("ws", workspaceId).param("key", key).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO ai_eval_suite (id, workspace_id, key, skill_key, name, document, content_hash, case_count,
                                               updated_by, created_at, updated_at)
                    VALUES (:id, :ws, :key, :skill, :name, CAST(:doc AS jsonb), :hash, :cases, :by, :now, :now)
                    """).param("id", Ids.newId()).param("ws", workspaceId).param("key", key)
                    .param("skill", document.path("skillKey").asString()).param("name", document.path("name").asString())
                    .param("doc", json.write(document)).param("hash", AiSkillContracts.contentHash(document))
                    .param("cases", document.path("cases").size()).param("by", caller.subjectId())
                    .param("now", Sql.ts(now)).update();
        }
        ObjectNode view = suiteView(workspaceId, key).orElseThrow();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.eval-suite.saved", "aiEvalSuite", key, AuditWriter.Outcome.SUCCESS, Map.of("suiteKey", key,
                "skillKey", document.path("skillKey").asString(), "caseCount", document.path("cases").size(),
                "revision", view.path("revision").asInt())));
        return view;
    }

    Optional<ObjectNode> suiteView(UUID workspaceId, String key) {
        return jdbc.sql("""
                SELECT key, skill_key, name, case_count, revision, content_hash, document, updated_at FROM ai_eval_suite
                WHERE workspace_id = :ws AND key = :key
                """).param("ws", workspaceId).param("key", key).query((rs, n) -> {
            ObjectNode item = json.object();
            item.put("key", rs.getString("key")).put("skillKey", rs.getString("skill_key"))
                    .put("name", rs.getString("name")).put("caseCount", rs.getInt("case_count"))
                    .put("revision", rs.getInt("revision")).put("contentHash", rs.getString("content_hash"))
                    .put("updatedAt", Sql.instant(rs, "updated_at").toString());
            item.set("document", json.parse(rs.getString("document")));
            return item;
        }).optional();
    }

    // ================================================================== workspace AI policy

    @Transactional(readOnly = true)
    public ObjectNode getPolicy(Caller caller, UUID workspaceId) {
        readable(caller, workspaceId, "getAiPolicy");
        return policyView(policy(workspaceId));
    }

    /**
     * Updates the policy (workspace administrators: {@code workspace.manage-access}). Regulated workspaces cannot be
     * loosened below the default: adverse outcomes always need a person, whatever the low-risk list says.
     */
    @Transactional
    public ObjectNode updatePolicy(Caller caller, UUID workspaceId, JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, "updateAiPolicy", true);
        if (body == null || !body.isObject()) {
            throw invalid("", "Send the AI policy.");
        }
        Policy current = policy(workspaceId);
        int expected = body.path("expectedVersion").asInt(-1);
        if (expected != current.version()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The AI policy changed since you loaded it.");
        }
        String retention = body.path("inputRetention").asString(current.inputRetention());
        if (!Set.of("digest", "redactedValues", "fullValues").contains(retention)) {
            throw invalid("/inputRetention", "Use digest, redactedValues or fullValues.");
        }
        int days = body.path("retentionDays").asInt(current.retentionDays());
        if (days < 1 || days > 3650) {
            throw invalid("/retentionDays", "Keep decision records between 1 and 3650 days.");
        }
        List<String> residency = strings(body.path("residency"), current.residency(), "^[a-z][a-z0-9-]{0,30}$",
                "/residency");
        List<String> lowRisk = strings(body.path("lowRiskLabels"), current.lowRiskLabels(),
                "^[a-z][a-z0-9-]{0,62}[a-z0-9]:[a-z][A-Za-z0-9]{0,39}$", "/lowRiskLabels");
        Map<String, String> actions = new LinkedHashMap<>(current.classificationActions());
        if (body.has("classificationActions")) {
            actions.clear();
            for (var e : body.path("classificationActions").properties()) {
                if (!DEFAULT_ACTIONS.containsKey(e.getKey()) || !ACTIONS.contains(e.getValue().asString(""))) {
                    throw invalid("/classificationActions/" + e.getKey(), "Classifications are public, internal,"
                            + " customer-text, personal and sensitive; actions are send, mask, hash and drop.");
                }
                actions.put(e.getKey(), e.getValue().asString());
            }
        }
        if ("send".equals(actions.get("sensitive")) && body.path("regulated").asBoolean(current.regulated())) {
            throw invalid("/classificationActions/sensitive", "A regulated workspace never sends sensitive fields.");
        }
        boolean regulated = body.path("regulated").asBoolean(current.regulated());
        boolean allowReview = body.path("allowAutoWithReview").asBoolean(current.allowAutoWithReview());
        Instant now = now();
        jdbc.sql("""
                INSERT INTO ai_workspace_policy (workspace_id, regulated, residency, input_retention, retention_days,
                    low_risk_labels, allow_auto_with_review, classification_actions, version, updated_by, updated_at)
                VALUES (:ws, :regulated, :residency, :retention, :days, :lowRisk, :review, CAST(:actions AS jsonb), :version,
                        :by, :now)
                ON CONFLICT (workspace_id) DO UPDATE SET regulated = EXCLUDED.regulated, residency = EXCLUDED.residency,
                    input_retention = EXCLUDED.input_retention, retention_days = EXCLUDED.retention_days,
                    low_risk_labels = EXCLUDED.low_risk_labels, allow_auto_with_review = EXCLUDED.allow_auto_with_review,
                    classification_actions = EXCLUDED.classification_actions, version = EXCLUDED.version,
                    updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """).param("ws", workspaceId).param("regulated", regulated)
                .param("residency", residency.toArray(String[]::new)).param("retention", retention).param("days", days)
                .param("lowRisk", lowRisk.toArray(String[]::new)).param("review", allowReview)
                .param("actions", json.write(actions)).param("version", current.version() + 1)
                .param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("regulated", regulated);
        metadata.put("residency", String.join(",", residency));
        metadata.put("inputRetention", retention);
        metadata.put("version", current.version() + 1);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.policy.updated", "aiPolicy", workspaceId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return policyView(policy(workspaceId));
    }

    /** The policy (defaults when none is stored: version 0). */
    public Policy policy(UUID workspaceId) {
        return jdbc.sql("""
                SELECT regulated, residency, input_retention, retention_days, low_risk_labels, allow_auto_with_review,
                       classification_actions, version, updated_at
                FROM ai_workspace_policy WHERE workspace_id = :ws
                """).param("ws", workspaceId).query((rs, n) -> {
            Map<String, String> actions = new LinkedHashMap<>();
            json.parse(rs.getString("classification_actions")).properties()
                    .forEach(e -> actions.put(e.getKey(), e.getValue().asString()));
            return new Policy(rs.getBoolean("regulated"), array(rs.getArray("residency")), rs.getString("input_retention"),
                    rs.getInt("retention_days"), array(rs.getArray("low_risk_labels")),
                    rs.getBoolean("allow_auto_with_review"), Map.copyOf(actions), rs.getInt("version"),
                    Sql.instant(rs, "updated_at"));
        }).optional().orElse(new Policy(false, List.of(), "digest", 365, List.of(), true, Map.of(), 0, null));
    }

    private ObjectNode policyView(Policy p) {
        ObjectNode view = json.object();
        view.put("regulated", p.regulated());
        ArrayNode residency = view.putArray("residency");
        p.residency().forEach(residency::add);
        view.put("inputRetention", p.inputRetention()).put("retentionDays", p.retentionDays());
        ArrayNode lowRisk = view.putArray("lowRiskLabels");
        p.lowRiskLabels().forEach(lowRisk::add);
        view.put("allowAutoWithReview", p.allowAutoWithReview());
        ObjectNode actions = view.putObject("classificationActions");
        DEFAULT_ACTIONS.keySet().stream().sorted().forEach(c -> actions.put(c, p.action(c)));
        view.put("version", p.version());
        if (p.updatedAt() == null) {
            view.putNull("updatedAt");
        } else {
            view.put("updatedAt", p.updatedAt().toString());
        }
        ArrayNode endpoints = view.putArray("endpoints");
        providers.endpoints().forEach((id, endpoint) -> {
            ObjectNode e = endpoints.addObject();
            e.put("id", id).put("kind", endpoint.providerKind().json());
            ArrayNode tags = e.putArray("residency");
            endpoint.residency().forEach(tags::add);
        });
        return view;
    }

    // ================================================================== compile facts

    /** Compile facts: every version with its approval, contract and publish gate (CompileContextFactory). */
    @Transactional(readOnly = true)
    public Map<String, CompileContext.AiSkillInfo> facts(UUID workspaceId) {
        Instant since = now().minus(GATE_WINDOW);
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Map<Integer, CompileContext.AiSkillVersion>> versions = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT s.key, s.name, v.version, v.status, v.document, v.content_hash, v.autonomy_ceiling,
                       (SELECT r.id FROM ai_eval_run r WHERE r.skill_version_id = v.id AND r.model_profile = v.model_profile
                          AND r.status = 'passed' AND r.finished_at >= :since ORDER BY r.finished_at DESC LIMIT 1) AS run_id
                FROM ai_skill s JOIN ai_skill_version v ON v.skill_id = s.id
                WHERE s.workspace_id = :ws ORDER BY s.key, v.version
                """).param("ws", workspaceId).param("since", Sql.ts(since)).query((rs, n) -> {
            String key = rs.getString("key");
            names.put(key, rs.getString("name"));
            UUID run = Sql.uuid(rs, "run_id");
            versions.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(rs.getInt("version"),
                    AiSkillContracts.facts(rs.getInt("version"), rs.getString("status"),
                            json.parse(rs.getString("document")), rs.getString("content_hash"),
                            run == null ? null : run.toString(), rs.getString("autonomy_ceiling")));
            return key;
        }).list();
        Map<String, CompileContext.AiSkillInfo> result = new LinkedHashMap<>();
        versions.forEach((key, byVersion) -> result.put(key, new CompileContext.AiSkillInfo(key, names.get(key),
                byVersion)));
        return result;
    }

    // ================================================================== reads used by the engine

    public Optional<Version> version(UUID workspaceId, String key, int version) {
        return jdbc.sql(VERSION_SELECT + " WHERE s.workspace_id = :ws AND s.key = :key AND v.version = :v")
                .param("ws", workspaceId).param("key", key).param("v", version).query(this::versionRow).optional();
    }

    Version requireVersion(UUID workspaceId, String key, int version) {
        return version(workspaceId, key, version).orElseThrow(ApiException::notFound);
    }

    private static final String VERSION_SELECT = """
            SELECT v.id, v.skill_id, s.key, s.name, v.version, v.status, v.document, v.content_hash, v.mode,
                   v.model_profile, v.author_id, v.submitted_at, v.approver_id, v.approved_at, v.autonomy_ceiling,
                   v.drift_fallback, v.drift_reason, v.created_at, v.updated_at
            FROM ai_skill_version v JOIN ai_skill s ON s.id = v.skill_id
            """;

    private Version versionRow(ResultSet rs, int n) throws SQLException {
        return new Version(Sql.uuid(rs, "id"), Sql.uuid(rs, "skill_id"), rs.getString("key"), rs.getString("name"),
                rs.getInt("version"), rs.getString("status"), json.parse(rs.getString("document")),
                rs.getString("content_hash"), rs.getString("mode"), rs.getString("model_profile"),
                Sql.uuid(rs, "author_id"), Sql.instant(rs, "submitted_at"), Sql.uuid(rs, "approver_id"),
                Sql.instant(rs, "approved_at"), rs.getString("autonomy_ceiling"), rs.getBoolean("drift_fallback"),
                rs.getString("drift_reason"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));
    }

    // ================================================================== views

    ObjectNode skillView(UUID workspaceId, UUID skillId, boolean documents) {
        ObjectNode view = jdbc.sql("SELECT key, name, owner_id, created_at FROM ai_skill WHERE id = :id")
                .param("id", skillId).query((rs, n) -> {
                    ObjectNode o = json.object();
                    o.put("key", rs.getString("key")).put("name", rs.getString("name"))
                            .put("ownerId", Sql.uuid(rs, "owner_id").toString())
                            .put("createdAt", Sql.instant(rs, "created_at").toString());
                    return o;
                }).single();
        List<Version> all = jdbc.sql(VERSION_SELECT + " WHERE v.skill_id = :id ORDER BY v.version DESC")
                .param("id", skillId).query(this::versionRow).list();
        Version approved = all.stream().filter(v -> "approved".equals(v.status())).findFirst().orElse(null);
        if (approved == null) {
            view.putNull("approvedVersion");
        } else {
            view.put("approvedVersion", approved.version());
        }
        view.put("mode", all.get(0).mode());
        ArrayNode list = view.putArray("versions");
        all.forEach(v -> list.add(versionView(v, documents)));
        return view;
    }

    ObjectNode versionView(Version v, boolean document) {
        ObjectNode o = json.object();
        o.put("skillKey", v.skillKey()).put("version", v.version()).put("status", v.status()).put("mode", v.mode())
                .put("modelProfile", v.modelProfile()).put("contentHash", v.contentHash())
                .put("authorId", v.authorId().toString());
        putInstant(o, "submittedAt", v.submittedAt());
        if (v.approverId() == null) {
            o.putNull("approverId");
        } else {
            o.put("approverId", v.approverId().toString());
        }
        putInstant(o, "approvedAt", v.approvedAt());
        o.put("autonomyCeiling", v.autonomyCeiling()).put("driftFallback", v.driftFallback());
        if (v.driftReason() == null) {
            o.putNull("driftReason");
        } else {
            o.put("driftReason", v.driftReason());
        }
        ObjectNode gate = o.putObject("evaluationGate");
        Instant since = now().minus(GATE_WINDOW);
        Optional<UUID> run = jdbc.sql("""
                SELECT id FROM ai_eval_run WHERE skill_version_id = :v AND model_profile = :p AND status = 'passed'
                  AND finished_at >= :since ORDER BY finished_at DESC LIMIT 1
                """).param("v", v.id()).param("p", v.modelProfile()).param("since", Sql.ts(since)).query(UUID.class)
                .optional();
        gate.put("passing", run.isPresent());
        if (run.isPresent()) {
            gate.put("runId", run.get().toString());
        } else {
            gate.putNull("runId");
        }
        o.put("createdAt", v.createdAt().toString()).put("updatedAt", v.updatedAt().toString());
        if (document) {
            o.set("document", v.document());
        }
        return o;
    }

    // ================================================================== helpers

    private void insertVersion(UUID workspaceId, UUID skillId, int version, JsonNode document, UUID author,
            Instant now) {
        jdbc.sql("""
                INSERT INTO ai_skill_version (id, workspace_id, skill_id, version, status, document, content_hash, mode,
                                              model_profile, author_id, created_at, updated_at)
                VALUES (:id, :ws, :skill, :version, 'draft', CAST(:doc AS jsonb), :hash, :mode, :profile, :author, :now,
                        :now)
                """).param("id", Ids.newId()).param("ws", workspaceId).param("skill", skillId).param("version", version)
                .param("doc", json.write(document)).param("hash", AiSkillContracts.contentHash(document))
                .param("mode", document.path("mode").asString()).param("profile", document.path("modelProfile").asString())
                .param("author", author).param("now", Sql.ts(now)).update();
    }

    private JsonNode checkedSkill(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw invalid("/document", "Send the AI skill document.");
        }
        refuse(AiSkillContracts.checkSkill(document));
        return document;
    }

    private void refuse(List<AiSkillContracts.Problem> problems) {
        if (problems.isEmpty()) {
            return;
        }
        List<ApiException.FieldError> errors = new ArrayList<>();
        for (AiSkillContracts.Problem p : problems.subList(0, Math.min(20, problems.size()))) {
            errors.add(new ApiException.FieldError("/document" + p.pointer(), "invalid", truncate(p.message(), 300)));
        }
        throw new ApiException(ProblemCode.VALIDATION_FAILED, "The document does not match the AI skill contract.")
                .withFieldErrors(errors);
    }

    private static void sameKey(JsonNode document, String key) {
        if (!key.equals(document.path("key").asString())) {
            throw invalid("/document/key", "The document key must stay " + key + ".");
        }
    }

    Optional<UUID> skillId(UUID workspaceId, String key) {
        return jdbc.sql("SELECT id FROM ai_skill WHERE workspace_id = :ws AND key = :key").param("ws", workspaceId)
                .param("key", key).query(UUID.class).optional();
    }

    private UUID lockSkill(UUID workspaceId, String key) {
        return jdbc.sql("SELECT id FROM ai_skill WHERE workspace_id = :ws AND key = :key FOR UPDATE")
                .param("ws", workspaceId).param("key", key).query(UUID.class).optional()
                .orElseThrow(ApiException::notFound);
    }

    private void audit(UUID workspaceId, Caller caller, String action, String key, int version, Map<String, ?> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>(extra);
        metadata.put("skillKey", key);
        metadata.put("version", version);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), action,
                "aiSkill", key, AuditWriter.Outcome.SUCCESS, metadata));
    }

    private List<String> strings(JsonNode node, List<String> current, String pattern, String pointer) {
        if (node.isMissingNode() || node.isNull()) {
            return current;
        }
        if (!node.isArray() || node.size() > 200) {
            throw invalid(pointer, "Send a list of at most 200 values.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode v : node) {
            String text = v.asString("");
            if (!text.matches(pattern)) {
                throw invalid(pointer, "The value " + truncate(text, 60) + " is not valid here.");
            }
            if (!values.contains(text)) {
                values.add(text);
            }
        }
        return values;
    }

    static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }

    static List<String> array(Array array) throws SQLException {
        return array == null ? List.of() : List.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    public static void putInstant(ObjectNode o, String name, Instant value) {
        if (value == null) {
            o.putNull(name);
        } else {
            o.put(name, value.toString());
        }
    }

    static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
