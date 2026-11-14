package com.orqix.ai.tasks;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.ai.skills.AiSkills;
import com.orqix.audit.AuditWriter;
import com.orqix.compiler.AiSkillContracts;
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
 * Shadow mode, promotion, drift and feedback (CG-A02, doc 29 section 7.7). The agreement dashboard compares AI decisions
 * with the human outcomes recorded on them; a promotion ({@code suggest -> autoWithReview -> auto}) needs a minimum
 * number of compared cases (default 200) with agreement above the skill's target, and is an approved skill change
 * (a second person holding {@code ai.approve-skill}). The weekly drift check falls back to {@code suggest} when the
 * agreement of the last 7 days drops beyond the skill's threshold. Every override becomes a candidate evaluation case,
 * reviewed (and written synthetically when no values were retained) before it joins a suite.
 */
@Service
public class AiShadow {

    private static final Logger log = LoggerFactory.getLogger(AiShadow.class);
    static final int DEFAULT_MIN_SHADOW = 200;
    static final double DEFAULT_TARGET = 0.9;
    static final double DEFAULT_MAX_DROP = 0.1;
    static final int MIN_DRIFT_CASES = 20;
    private static final List<String> LADDER = List.of("suggest", "autoWithReview", "auto");

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final AiSkills skills;

    public AiShadow(JdbcClient jdbc, Json json, Clock clock, PolicyService policy, AuditWriter audit, AiSkills skills) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.audit = audit;
        this.skills = skills;
    }

    // ================================================================== dashboard

    @Transactional(readOnly = true)
    public ObjectNode dashboard(Caller caller, UUID workspaceId, String key, int version) {
        readable(caller, workspaceId, "getAiShadowDashboard");
        AiSkills.Version v = skills.version(workspaceId, key, version).orElseThrow(ApiException::notFound);
        return evidence(workspaceId, v, null);
    }

    /** Agreement, confusion matrix, per-label precision/recall, cost per case and p95 latency (since {@code since}). */
    ObjectNode evidence(UUID workspaceId, AiSkills.Version v, Instant since) {
        record Row(String status, String autonomy, String predicted, String human, String outcome, BigDecimal cost,
                long latency) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT status, autonomy, output, human_value, human_outcome, cost_usd, latency_ms FROM ai_decision_record
                WHERE workspace_id = :ws AND skill_key = :key AND skill_version = :v
                  AND (CAST(:since AS timestamptz) IS NULL OR human_at >= :since)
                """).param("ws", workspaceId).param("key", v.skillKey()).param("v", v.version())
                .param("since", since == null ? null : Sql.ts(since)).query((rs, n) -> {
                    String output = rs.getString("output");
                    JsonNode out = output == null ? null : json.parse(output);
                    String predicted = out == null ? null : out.path("label").asString(out.path("recommendation")
                            .asString(null));
                    return new Row(rs.getString("status"), rs.getString("autonomy"), predicted,
                            rs.getString("human_value"), rs.getString("human_outcome"), rs.getBigDecimal("cost_usd"),
                            rs.getLong("latency_ms"));
                }).list();
        ObjectNode view = json.object();
        view.put("skillKey", v.skillKey()).put("version", v.version());
        int compared = 0;
        int agreed = 0;
        Map<String, Map<String, Integer>> confusion = new LinkedHashMap<>();
        BigDecimal cost = BigDecimal.ZERO;
        List<Long> latencies = new ArrayList<>();
        int shadow = 0;
        Map<String, Integer> statuses = new LinkedHashMap<>();
        for (Row r : rows) {
            statuses.merge(r.status(), 1, Integer::sum);
            if ("shadow".equals(r.autonomy())) {
                shadow++;
            }
            cost = cost.add(r.cost() == null ? BigDecimal.ZERO : r.cost());
            latencies.add(r.latency());
            if (r.outcome() == null || r.predicted() == null) {
                continue;
            }
            compared++;
            String human = "accepted".equals(r.outcome()) || r.human() == null ? r.predicted() : r.human();
            if (human.equals(r.predicted())) {
                agreed++;
            }
            confusion.computeIfAbsent(r.predicted(), k -> new LinkedHashMap<>()).merge(human, 1, Integer::sum);
        }
        double agreement = compared == 0 ? 0 : agreed / (double) compared;
        view.put("decisions", rows.size()).put("shadowDecisions", shadow).put("compared", compared)
                .put("agreed", agreed).put("agreement", AiTaskEngine.round(agreement));
        ObjectNode counts = view.putObject("statuses");
        statuses.forEach(counts::put);
        ObjectNode matrix = view.putObject("confusion");
        confusion.forEach((ai, row) -> {
            ObjectNode r = matrix.putObject(ai);
            row.forEach(r::put);
        });
        ObjectNode perLabel = view.putObject("perLabel");
        java.util.Set<String> labels = new java.util.TreeSet<>(confusion.keySet());
        confusion.values().forEach(row -> labels.addAll(row.keySet()));
        for (String label : labels) {
            int tp = confusion.getOrDefault(label, Map.of()).getOrDefault(label, 0);
            int predicted = confusion.getOrDefault(label, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            int actual = confusion.values().stream().mapToInt(row -> row.getOrDefault(label, 0)).sum();
            perLabel.putObject(label).put("precision", AiTaskEngine.round(predicted == 0 ? 0 : tp / (double) predicted))
                    .put("recall", AiTaskEngine.round(actual == 0 ? 0 : tp / (double) actual)).put("support", actual);
        }
        view.put("costPerCaseUsd", rows.isEmpty() ? BigDecimal.ZERO
                : cost.divide(BigDecimal.valueOf(rows.size()), 10, java.math.RoundingMode.HALF_UP));
        latencies.sort(Long::compare);
        view.put("p95LatencyMs", latencies.isEmpty() ? 0
                : latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1)));
        JsonNode promotion = v.document().path("promotion");
        view.put("minShadowCases", promotion.path("minShadowCases").asInt(DEFAULT_MIN_SHADOW))
                .put("targetAgreement", promotion.path("targetAgreement").asDouble(DEFAULT_TARGET))
                .put("autonomyCeiling", v.autonomyCeiling()).put("driftFallback", v.driftFallback());
        return view;
    }

    // ================================================================== promotion

    @Transactional
    public ObjectNode requestPromotion(Caller caller, UUID workspaceId, String key, int version, JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, "requestAiSkillPromotion", true);
        AiSkills.Version v = skills.version(workspaceId, key, version).orElseThrow(ApiException::notFound);
        String to = body == null ? "" : body.path("toAutonomy").asString("");
        String reason = body == null ? "" : body.path("reason").asString("").strip();
        if (reason.isEmpty()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Say why this skill may decide with more autonomy.");
        }
        if (!"approved".equals(v.status())) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT, "Only an approved version can be promoted.");
        }
        int from = LADDER.indexOf(v.autonomyCeiling());
        if (LADDER.indexOf(to) != from + 1) {
            throw invalid("/toAutonomy", "Promote one step at a time: " + v.autonomyCeiling() + " can become "
                    + (from + 1 < LADDER.size() ? LADDER.get(from + 1) : "nothing higher") + ".");
        }
        ObjectNode evidence = evidence(workspaceId, v, null);
        int minCases = evidence.path("minShadowCases").asInt();
        double target = evidence.path("targetAgreement").asDouble();
        if (evidence.path("compared").asInt() < minCases || evidence.path("agreement").asDouble() < target) {
            throw invalid("/toAutonomy", "Promotion needs " + minCases + " compared shadow cases with agreement of at"
                    + " least " + target + "; this version has " + evidence.path("compared").asInt() + " at "
                    + evidence.path("agreement").asDouble() + ".");
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO ai_skill_promotion (id, workspace_id, skill_version_id, from_autonomy, to_autonomy,
                        evidence, status, requested_by, requested_at, reason)
                    VALUES (:id, :ws, :v, :from, :to, CAST(:evidence AS jsonb), 'pending', :by, :now, :reason)
                    """).param("id", id).param("ws", workspaceId).param("v", v.id()).param("from", v.autonomyCeiling())
                    .param("to", to).param("evidence", json.write(evidence)).param("by", caller.subjectId())
                    .param("now", Sql.ts(now())).param("reason", AiSkillsText.cut(reason, 1000)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT, "A promotion of this version is pending.");
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.promotion.requested", "aiSkillPromotion", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("skillKey", key, "version", version, "toAutonomy", to)));
        return promotion(workspaceId, id);
    }

    @Transactional
    public ObjectNode decidePromotion(Caller caller, UUID workspaceId, UUID promotionId, JsonNode body) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.AI_APPROVE_SKILL, "decideAiSkillPromotion", true);
        var row = jdbc.sql("""
                SELECT p.skill_version_id, p.to_autonomy, p.status, p.requested_by, s.key, v.version
                FROM ai_skill_promotion p JOIN ai_skill_version v ON v.id = p.skill_version_id
                     JOIN ai_skill s ON s.id = v.skill_id
                WHERE p.workspace_id = :ws AND p.id = :id FOR UPDATE OF p
                """).param("ws", workspaceId).param("id", promotionId).query((rs, n) -> new Object[] {
                    Sql.uuid(rs, "skill_version_id"), rs.getString("to_autonomy"), rs.getString("status"),
                    Sql.uuid(rs, "requested_by"), rs.getString("key"), rs.getInt("version")})
                .optional().orElseThrow(ApiException::notFound);
        String decision = body == null ? "" : body.path("decision").asString("");
        String reason = body == null ? "" : body.path("reason").asString("").strip();
        if (!List.of("approve", "reject").contains(decision)) {
            throw invalid("/decision", "Use approve or reject.");
        }
        if (reason.isEmpty()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Give a reason for the decision.");
        }
        if (!"pending".equals(row[2])) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT, "This promotion was already decided.");
        }
        if (caller.subjectId().equals(row[3])) {
            throw new ApiException(ProblemCode.SELF_APPROVAL_DENIED,
                    "A promotion must be decided by someone other than the person who asked for it.");
        }
        Instant now = now();
        jdbc.sql("""
                UPDATE ai_skill_promotion SET status = :status, decided_by = :by, decided_at = :now, decision_reason = :reason
                WHERE id = :id
                """).param("status", "approve".equals(decision) ? "approved" : "rejected").param("by", caller.subjectId())
                .param("now", Sql.ts(now)).param("reason", AiSkillsText.cut(reason, 1000)).param("id", promotionId)
                .update();
        if ("approve".equals(decision)) {
            jdbc.sql("UPDATE ai_skill_version SET autonomy_ceiling = :to, updated_at = :now WHERE id = :id")
                    .param("to", row[1]).param("now", Sql.ts(now)).param("id", row[0]).update();
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.promotion.decided", "aiSkillPromotion", promotionId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("skillKey", row[4], "version", row[5], "toAutonomy", row[1], "decision", decision)));
        return promotion(workspaceId, promotionId);
    }

    @Transactional(readOnly = true)
    public ObjectNode promotions(Caller caller, UUID workspaceId, String key) {
        readable(caller, workspaceId, "listAiSkillPromotions");
        ObjectNode result = json.object();
        ArrayNode items = result.putArray("items");
        jdbc.sql("""
                SELECT p.id FROM ai_skill_promotion p JOIN ai_skill_version v ON v.id = p.skill_version_id
                     JOIN ai_skill s ON s.id = v.skill_id
                WHERE p.workspace_id = :ws AND s.key = :key ORDER BY p.requested_at DESC LIMIT 100
                """).param("ws", workspaceId).param("key", key).query(UUID.class).list()
                .forEach(id -> items.add(promotion(workspaceId, id)));
        return result;
    }

    private ObjectNode promotion(UUID workspaceId, UUID id) {
        return jdbc.sql("""
                SELECT p.id, s.key, v.version, p.from_autonomy, p.to_autonomy, p.evidence, p.status, p.requested_by,
                       p.requested_at, p.reason, p.decided_by, p.decided_at, p.decision_reason
                FROM ai_skill_promotion p JOIN ai_skill_version v ON v.id = p.skill_version_id
                     JOIN ai_skill s ON s.id = v.skill_id
                WHERE p.workspace_id = :ws AND p.id = :id
                """).param("ws", workspaceId).param("id", id).query((rs, n) -> {
            ObjectNode o = json.object();
            o.put("id", Sql.uuid(rs, "id").toString()).put("skillKey", rs.getString("key"))
                    .put("version", rs.getInt("version")).put("fromAutonomy", rs.getString("from_autonomy"))
                    .put("toAutonomy", rs.getString("to_autonomy")).put("status", rs.getString("status"))
                    .put("requestedBy", Sql.uuid(rs, "requested_by").toString())
                    .put("requestedAt", Sql.instant(rs, "requested_at").toString()).put("reason", rs.getString("reason"));
            o.set("evidence", json.parse(rs.getString("evidence")));
            UUID by = Sql.uuid(rs, "decided_by");
            if (by == null) {
                o.putNull("decidedBy");
            } else {
                o.put("decidedBy", by.toString());
            }
            AiSkills.putInstant(o, "decidedAt", Sql.instant(rs, "decided_at"));
            if (rs.getString("decision_reason") == null) {
                o.putNull("decisionReason");
            } else {
                o.put("decisionReason", rs.getString("decision_reason"));
            }
            return o;
        }).single();
    }

    // ================================================================== drift

    /** Weekly drift check of every promoted version (Mondays 03:00 UTC). */
    @Scheduled(cron = "${orqix.ai.tasks.drift-cron:0 0 3 * * MON}", zone = "UTC")
    public void weeklyDrift() {
        try {
            List<Object[]> versions = jdbc.sql("""
                    SELECT s.workspace_id, s.key, v.version FROM ai_skill_version v JOIN ai_skill s ON s.id = v.skill_id
                    WHERE v.status = 'approved' AND v.autonomy_ceiling <> 'suggest' AND NOT v.drift_fallback
                    """).query((rs, n) -> new Object[] {Sql.uuid(rs, "workspace_id"), rs.getString("key"),
                    rs.getInt("version")}).list();
            for (Object[] v : versions) {
                checkDrift((UUID) v[0], (String) v[1], (Integer) v[2], null);
            }
        } catch (RuntimeException e) {
            log.warn("AI drift check failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Runs the drift check now ({@code ai.evaluate}). */
    @Transactional
    public ObjectNode driftCheck(Caller caller, UUID workspaceId, String key, int version) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.AI_EVALUATE, "checkAiSkillDrift", true);
        skills.version(workspaceId, key, version).orElseThrow(ApiException::notFound);
        return checkDrift(workspaceId, key, version, caller.subjectId());
    }

    @Transactional
    ObjectNode checkDrift(UUID workspaceId, String key, int version, UUID actor) {
        AiSkills.Version v = skills.version(workspaceId, key, version).orElseThrow();
        ObjectNode recent = evidence(workspaceId, v, clock.instant().minus(Duration.ofDays(7)));
        double baseline = jdbc.sql("""
                SELECT (evidence ->> 'agreement')::float8 FROM ai_skill_promotion
                WHERE skill_version_id = :v AND status = 'approved' ORDER BY decided_at DESC LIMIT 1
                """).param("v", v.id()).query(Double.class).optional()
                .orElse(v.document().path("promotion").path("targetAgreement").asDouble(DEFAULT_TARGET));
        double maxDrop = v.document().path("drift").path("maxAgreementDrop").asDouble(DEFAULT_MAX_DROP);
        boolean fallback = v.document().path("drift").path("fallbackToSuggest").asBoolean(true);
        int cases = recent.path("compared").asInt();
        double agreement = recent.path("agreement").asDouble();
        boolean drifted = cases >= MIN_DRIFT_CASES && baseline - agreement > maxDrop;
        ObjectNode result = json.object();
        result.put("skillKey", key).put("version", version).put("cases", cases)
                .put("agreement", AiTaskEngine.round(agreement)).put("baseline", AiTaskEngine.round(baseline))
                .put("maxAgreementDrop", maxDrop).put("drifted", drifted);
        boolean fellBack = drifted && fallback && !v.driftFallback();
        if (fellBack) {
            String reason = "Agreement over the last 7 days fell to " + AiTaskEngine.round(agreement) + " from "
                    + AiTaskEngine.round(baseline) + " (" + cases + " cases).";
            jdbc.sql("""
                    UPDATE ai_skill_version SET drift_fallback = true, drift_reason = :reason, updated_at = :now WHERE id = :id
                    """).param("reason", reason).param("now", Sql.ts(now())).param("id", v.id()).update();
            audit.write(new AuditWriter.Entry(workspaceId, actor == null ? AuditWriter.ActorType.SYSTEM
                    : AuditWriter.ActorType.USER, actor, "ai.skill.drift-fallback", "aiSkill", key,
                    AuditWriter.Outcome.SUCCESS, Map.of("skillKey", key, "version", version, "agreement",
                    AiTaskEngine.round(agreement))));
        }
        result.put("fellBackToSuggest", fellBack || v.driftFallback());
        return result;
    }

    @Transactional
    public ObjectNode clearDrift(Caller caller, UUID workspaceId, String key, int version, JsonNode body) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.AI_APPROVE_SKILL, "clearAiSkillDrift", true);
        AiSkills.Version v = skills.version(workspaceId, key, version).orElseThrow(ApiException::notFound);
        if (body == null || body.path("reason").asString("").isBlank()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Say why the skill may decide automatically again.");
        }
        if (!v.driftFallback()) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT, "This version has not fallen back.");
        }
        jdbc.sql("UPDATE ai_skill_version SET drift_fallback = false, drift_reason = NULL, updated_at = :now WHERE id = :id")
                .param("now", Sql.ts(now())).param("id", v.id()).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.skill.drift-cleared", "aiSkill", key, AuditWriter.Outcome.SUCCESS,
                Map.of("skillKey", key, "version", version)));
        return dashboard(caller, workspaceId, key, version);
    }

    // ================================================================== review queue and candidates

    /** Decisions sampled for after-the-fact review that nobody has reviewed yet (no request values). */
    @Transactional(readOnly = true)
    public ObjectNode reviewQueue(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.AI_EVALUATE) && !access.has(Permission.RUN_OPERATE)) {
            policy.require(access, Permission.AI_EVALUATE, "listAiReviewQueue", false);
        }
        ObjectNode result = json.object();
        ArrayNode items = result.putArray("items");
        jdbc.sql("""
                SELECT id, run_id, node_id, skill_key, skill_version, status, output, confidence, created_at
                FROM ai_decision_record WHERE workspace_id = :ws AND review_sampled AND human_outcome IS NULL
                ORDER BY created_at DESC LIMIT 200
                """).param("ws", workspaceId).query((rs, n) -> {
            ObjectNode o = items.addObject();
            o.put("decisionId", Sql.uuid(rs, "id").toString()).put("runId", Sql.uuid(rs, "run_id").toString())
                    .put("nodeId", rs.getString("node_id")).put("skillKey", rs.getString("skill_key"))
                    .put("skillVersion", rs.getInt("skill_version")).put("status", rs.getString("status"));
            JsonNode output = rs.getString("output") == null ? null : json.parse(rs.getString("output"));
            String value = output == null ? null : output.path("label").asString(output.path("recommendation")
                    .asString(null));
            if (value == null) {
                o.putNull("aiValue");
            } else {
                o.put("aiValue", value);
            }
            BigDecimal c = rs.getBigDecimal("confidence");
            if (c == null) {
                o.putNull("confidence");
            } else {
                o.put("confidence", c);
            }
            o.put("createdAt", Sql.instant(rs, "created_at").toString());
            return o;
        }).list();
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode candidates(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.AI_EVALUATE, "listAiEvalCandidates", false);
        ObjectNode result = json.object();
        ArrayNode items = result.putArray("items");
        jdbc.sql("""
                SELECT id, skill_key, decision_id, input, expected, created_at FROM ai_eval_candidate
                WHERE workspace_id = :ws AND status = 'pending' ORDER BY created_at DESC LIMIT 200
                """).param("ws", workspaceId).query((rs, n) -> {
            ObjectNode o = items.addObject();
            o.put("id", Sql.uuid(rs, "id").toString()).put("skillKey", rs.getString("skill_key"))
                    .put("decisionId", Sql.uuid(rs, "decision_id").toString());
            String input = rs.getString("input");
            if (input == null) {
                o.putNull("input");
            } else {
                o.set("input", json.parse(input));
            }
            o.set("expected", json.parse(rs.getString("expected")));
            o.put("createdAt", Sql.instant(rs, "created_at").toString());
            return o;
        }).list();
        return result;
    }

    /**
     * Accepts a candidate into a suite: the reviewer sends the case input (redacted, or written synthetically) and the
     * expected answer; the case is appended to the suite document. {@code decision: discard} drops it.
     */
    @Transactional
    public ObjectNode reviewCandidate(Caller caller, UUID workspaceId, UUID candidateId, JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.AI_EVALUATE, "reviewAiEvalCandidate", true);
        var candidate = jdbc.sql("""
                SELECT skill_key, status FROM ai_eval_candidate WHERE workspace_id = :ws AND id = :id FOR UPDATE
                """).param("ws", workspaceId).param("id", candidateId)
                .query((rs, n) -> new String[] {rs.getString("skill_key"), rs.getString("status")}).optional()
                .orElseThrow(ApiException::notFound);
        if (!"pending".equals(candidate[1])) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT, "This candidate was already reviewed.");
        }
        String decision = body == null ? "" : body.path("decision").asString("");
        if (!List.of("accept", "discard").contains(decision)) {
            throw invalid("/decision", "Use accept or discard.");
        }
        if ("accept".equals(decision)) {
            String suiteKey = body.path("suiteKey").asString("");
            JsonNode suite = jdbc.sql("""
                    SELECT document FROM ai_eval_suite WHERE workspace_id = :ws AND key = :key FOR UPDATE
                    """).param("ws", workspaceId).param("key", suiteKey).query(String.class).optional()
                    .map(json::parse).orElseThrow(() -> invalid("/suiteKey", "There is no evaluation suite " + suiteKey
                            + "."));
            if (!candidate[0].equals(suite.path("skillKey").asString())) {
                throw invalid("/suiteKey", "This suite evaluates another skill.");
            }
            ObjectNode updated = ((ObjectNode) suite).deepCopy();
            ObjectNode added = ((ArrayNode) updated.path("cases")).addObject();
            added.put("id", body.path("caseId").asString("override-" + candidateId.toString().substring(0, 8)));
            added.set("input", body.path("input"));
            added.set("expected", body.path("expected"));
            added.put("source", "override");
            List<AiSkillContracts.Problem> problems = AiSkillContracts.checkSuite(updated);
            if (!problems.isEmpty()) {
                throw invalid("/input", "The case does not fit the suite: " + problems.get(0).pointer() + " "
                        + problems.get(0).message());
            }
            jdbc.sql("""
                    UPDATE ai_eval_suite SET document = CAST(:doc AS jsonb), content_hash = :hash, case_count = :cases,
                           revision = revision + 1, updated_by = :by, updated_at = :now
                    WHERE workspace_id = :ws AND key = :key
                    """).param("doc", json.write(updated)).param("hash", AiSkillContracts.contentHash(updated))
                    .param("cases", updated.path("cases").size()).param("by", caller.subjectId())
                    .param("now", Sql.ts(now())).param("ws", workspaceId).param("key", suiteKey).update();
        }
        jdbc.sql("""
                UPDATE ai_eval_candidate SET status = :status, reviewed_by = :by, reviewed_at = :now WHERE id = :id
                """).param("status", "accept".equals(decision) ? "accepted" : "discarded").param("by", caller.subjectId())
                .param("now", Sql.ts(now())).param("id", candidateId).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.eval-candidate.reviewed", "aiEvalCandidate", candidateId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("skillKey", candidate[0], "decision", decision)));
        ObjectNode result = json.object();
        result.put("id", candidateId.toString()).put("status", "accept".equals(decision) ? "accepted" : "discarded");
        return result;
    }

    private void readable(Caller caller, UUID workspaceId, String operationId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.WORKFLOW_READ) && !access.has(Permission.AI_EVALUATE)
                && !access.has(Permission.AI_APPROVE_SKILL)) {
            policy.require(access, Permission.WORKFLOW_READ, operationId, false);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }
}
