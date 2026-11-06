package com.orqix.ai.tasks;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.ai.internal.AiLedger;
import com.orqix.ai.provider.ProviderRegistry;
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
 * Evaluation runs (CG-A02, doc 29 sections 7.5 and 7.7): a skill version is run over an evaluation suite on its model
 * profile through the same inference path as production (minimization, samples, repair). The run reports accuracy,
 * per-label precision and recall, the confusion matrix, cost per case, p95 latency, calibration buckets and the
 * threshold trade-off; it passes when it has enough cases and meets the skill's accuracy and recall targets. A passing
 * run on the pinned profile within 30 days is the publish gate. Runs execute in the background; the API returns at once.
 */
@Service
public class AiEvaluations {

    private static final Logger log = LoggerFactory.getLogger(AiEvaluations.class);
    static final int DEFAULT_MIN_CASES = 50;
    static final double DEFAULT_MIN_ACCURACY = 0.9;
    static final double[] THRESHOLDS = {0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95};

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final AiSkills skills;
    private final AiTaskEngine engine;
    private final ProviderRegistry providers;
    private final AiLedger ledger;
    private final AiTaskProperties properties;
    private final TransactionTemplate tx;

    public AiEvaluations(JdbcClient jdbc, Json json, Clock clock, PolicyService policy, AuditWriter audit,
            AiSkills skills, AiTaskEngine engine, ProviderRegistry providers, AiLedger ledger,
            AiTaskProperties properties, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.audit = audit;
        this.skills = skills;
        this.engine = engine;
        this.providers = providers;
        this.ledger = ledger;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Starts a run of {@code suiteKey} (default: the skill's own suite) on the version's profile. */
    @Transactional
    public ObjectNode start(Caller caller, UUID workspaceId, String key, int version, JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.AI_EVALUATE, "startAiEvaluation", true);
        AiSkills.Version v = skills.version(workspaceId, key, version).orElseThrow(ApiException::notFound);
        ProviderRegistry.Resolved profile;
        try {
            profile = providers.resolve(v.modelProfile(), skills.policy(workspaceId).residency());
        } catch (ProviderRegistry.ProfileRefusedException e) {
            throw new ApiException(ProblemCode.AI_MODEL_NOT_ALLOWED, e.getMessage());
        }
        String suiteKey = body != null && body.path("suiteKey").isString() ? body.path("suiteKey").asString()
                : v.document().path("evaluation").path("suite").asString(null);
        if (suiteKey == null) {
            throw invalid("/suiteKey", "Name the evaluation suite to run.");
        }
        var suite = jdbc.sql("""
                SELECT id, document, content_hash FROM ai_eval_suite WHERE workspace_id = :ws AND key = :key
                """).param("ws", workspaceId).param("key", suiteKey)
                .query((rs, n) -> new Object[] {Sql.uuid(rs, "id"), rs.getString("document"), rs.getString("content_hash")})
                .optional().orElseThrow(() -> invalid("/suiteKey", "There is no evaluation suite " + suiteKey + "."));
        JsonNode suiteDocument = json.parse((String) suite[1]);
        if (!key.equals(suiteDocument.path("skillKey").asString())) {
            throw invalid("/suiteKey", "This suite evaluates another skill.");
        }
        List<AiSkillContracts.Problem> problems = AiSkillContracts.checkSuiteFor(suiteDocument, v.document());
        if (!problems.isEmpty()) {
            throw invalid("/suiteKey", "The suite does not fit this skill version: " + problems.get(0).pointer() + " "
                    + problems.get(0).message());
        }
        if (jdbc.sql("SELECT count(*) FROM ai_eval_run WHERE skill_version_id = :v AND status = 'running'")
                .param("v", v.id()).query(Integer.class).single() > 0) {
            throw new ApiException(ProblemCode.AI_SKILL_STATE_CONFLICT, "An evaluation of this version is running.");
        }
        UUID id = Ids.newId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                INSERT INTO ai_eval_run (id, workspace_id, skill_version_id, suite_id, suite_hash, model_profile, status,
                                         case_count, started_by, started_at)
                VALUES (:id, :ws, :v, :suite, :hash, :profile, 'running', 0, :by, :now)
                """).param("id", id).param("ws", workspaceId).param("v", v.id()).param("suite", suite[0])
                .param("hash", suite[2]).param("profile", profile.profileId()).param("by", caller.subjectId())
                .param("now", Sql.ts(now)).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.evaluation.started", "aiEvalRun", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("skillKey", key,
                "version", version, "suiteKey", suiteKey, "profile", profile.profileId())));
        UUID subject = caller.subjectId();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        Thread.ofVirtual().name("orqix-ai-eval-" + id).start(() -> execute(workspaceId, id, v, profile,
                                suiteDocument, subject));
                    }
                });
        return view(workspaceId, id).orElseThrow();
    }

    /** Runs every case and stores the metrics (no transaction while the provider works). */
    void execute(UUID workspaceId, UUID runId, AiSkills.Version v, ProviderRegistry.Resolved profile, JsonNode suite,
            UUID subject) {
        try {
            ObjectNode metrics = json.object();
            ArrayNode calibration = json.mapper().createArrayNode();
            boolean passed = evaluate(workspaceId, v, profile, suite, subject, metrics, calibration);
            int cases = suite.path("cases").size();
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                        UPDATE ai_eval_run SET status = :status, case_count = :cases, metrics = CAST(:metrics AS jsonb),
                               calibration = CAST(:calibration AS jsonb), finished_at = :now
                        WHERE id = :id
                        """).param("status", passed ? "passed" : "failed").param("cases", cases)
                        .param("metrics", json.write(metrics)).param("calibration", json.write(calibration))
                        .param("now", Sql.ts(clock.instant())).param("id", runId).update();
                audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.SYSTEM, null,
                        "ai.evaluation.finished", "aiEvalRun", runId.toString(), AuditWriter.Outcome.SUCCESS,
                        Map.of("skillKey", v.skillKey(), "version", v.version(), "status", passed ? "passed" : "failed",
                                "caseCount", cases)));
            });
        } catch (RuntimeException e) {
            log.warn("AI evaluation {} failed: {}", runId, e.getClass().getSimpleName(), e);
            tx.executeWithoutResult(s -> jdbc.sql("""
                    UPDATE ai_eval_run SET status = 'error', failure = :failure, finished_at = :now WHERE id = :id
                    """).param("failure", "The evaluation stopped: " + e.getClass().getSimpleName())
                    .param("now", Sql.ts(clock.instant())).param("id", runId).update());
        }
    }

    private boolean evaluate(UUID workspaceId, AiSkills.Version v, ProviderRegistry.Resolved profile, JsonNode suite,
            UUID subject, ObjectNode metrics, ArrayNode calibration) {
        JsonNode skill = v.document();
        String mode = v.mode();
        AiSkills.Policy policy = skills.policy(workspaceId);
        JsonNode confidence = skill.path("confidence");
        int samples = "selfConsistency".equals(confidence.path("method").asString("single"))
                ? Math.max(1, confidence.path("samples").asInt(3)) : 1;
        int timeout = skill.path("limits").path("timeoutSeconds").asInt((int) properties.defaultTimeout().toSeconds());
        BigDecimal maxCost = skill.path("limits").has("maxCostUsd") ? skill.path("limits").path("maxCostUsd")
                .decimalValue() : properties.defaultMaxCostUsd();
        List<double[]> scored = new ArrayList<>(); // raw confidence, correct (1/0)
        List<Long> latencies = new ArrayList<>();
        Map<String, Map<String, Integer>> confusion = new LinkedHashMap<>();
        BigDecimal cost = BigDecimal.ZERO;
        long in = 0;
        long out = 0;
        int correct = 0;
        int invalid = 0;
        int calls = 0;
        for (JsonNode c : suite.path("cases")) {
            long started = System.nanoTime();
            ObjectNode input = c.path("input").isObject() ? (ObjectNode) c.path("input") : json.object();
            Minimizer.Result minimized = Minimizer.apply(json, skill.path("input"), input, policy, Map.of());
            AiTaskEngine.Inference inf = engine.infer(workspaceId, v, profile, minimized.sent(), samples,
                    clock.instant().plusSeconds(timeout), maxCost, null, null);
            latencies.add((System.nanoTime() - started) / 1_000_000);
            cost = cost.add(inf.costUsd());
            in += inf.inputTokens();
            out += inf.outputTokens();
            calls += inf.calls();
            JsonNode expected = c.path("expected");
            String expectedKey = switch (mode) {
                case "classify" -> expected.path("label").asString("");
                case "recommend" -> expected.path("outcome").asString("");
                default -> "expected";
            };
            if (!inf.usable()) {
                invalid++;
                confusion.computeIfAbsent(expectedKey, k -> new LinkedHashMap<>()).merge("(none)", 1, Integer::sum);
                scored.add(new double[] {0, 0});
                continue;
            }
            AiTaskEngine.Consensus consensus = AiTaskEngine.consensus(inf.answers());
            SkillAnswers.Answer a = consensus.answer();
            boolean ok = switch (mode) {
                case "classify" -> expected.path("label").asString("").equals(a.label());
                case "recommend" -> expected.path("outcome").asString("").equals(a.recommendation());
                case "score" -> a.score().subtract(expected.path("score").decimalValue()).abs()
                        .compareTo(BigDecimal.valueOf(expected.path("tolerance").asDouble(0))) <= 0;
                case "extract" -> fieldsMatch(expected.path("fields"), a.fields());
                default -> mentions(expected.path("mustMention"), a.text());
            };
            String predicted = switch (mode) {
                case "classify" -> a.label();
                case "recommend" -> a.recommendation();
                default -> ok ? "expected" : "other";
            };
            confusion.computeIfAbsent(expectedKey, k -> new LinkedHashMap<>()).merge(predicted, 1, Integer::sum);
            if (ok) {
                correct++;
            }
            scored.add(new double[] {AiTaskEngine.rawConfidence(inf, consensus), ok ? 1 : 0});
        }
        if (calls > 0) {
            BigDecimal total = cost;
            long tin = in;
            long tout = out;
            int tcalls = calls;
            try {
                tx.executeWithoutResult(s -> ledger.record(new AiLedger.Entry(workspaceId, subject, "aiEval",
                        AiSkillsText.cut(profile.profileId(), 32), profile.model(), profile.model(),
                        AiTaskEngine.PROMPT_VERSION, Math.min(20, tcalls), false, tin, tout, total, 0,
                        AiLedger.Outcome.SUCCESS, null, null, null)));
            } catch (RuntimeException e) {
                log.warn("AI evaluation metering failed: {}", e.getClass().getSimpleName());
            }
        }
        int cases = suite.path("cases").size();
        double accuracy = cases == 0 ? 0 : correct / (double) cases;
        metrics.put("caseCount", cases).put("correct", correct).put("invalid", invalid)
                .put("accuracy", AiTaskEngine.round(accuracy));
        ObjectNode matrix = metrics.putObject("confusion");
        confusion.forEach((exp, row) -> {
            ObjectNode r = matrix.putObject(exp);
            row.forEach(r::put);
        });
        ObjectNode perLabel = metrics.putObject("perLabel");
        Set<String> labels = new LinkedHashSet<>();
        skill.path("classify".equals(mode) ? "labels" : "outcomes").forEach(l -> labels.add(l.path("key").asString()));
        Map<String, Double> recall = new LinkedHashMap<>();
        for (String label : labels) {
            int tp = confusion.getOrDefault(label, Map.of()).getOrDefault(label, 0);
            int support = confusion.getOrDefault(label, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            int predicted = confusion.values().stream().mapToInt(row -> row.getOrDefault(label, 0)).sum();
            double r = support == 0 ? 0 : tp / (double) support;
            double p = predicted == 0 ? 0 : tp / (double) predicted;
            recall.put(label, r);
            perLabel.putObject(label).put("precision", AiTaskEngine.round(p)).put("recall", AiTaskEngine.round(r))
                    .put("support", support);
        }
        metrics.put("costUsd", cost);
        metrics.put("costPerCaseUsd", cases == 0 ? BigDecimal.ZERO
                : cost.divide(BigDecimal.valueOf(cases), 10, java.math.RoundingMode.HALF_UP));
        latencies.sort(Long::compare);
        metrics.put("p95LatencyMs", latencies.isEmpty() ? 0
                : latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1)));
        // Calibration (section 7.5): ten buckets of raw confidence and the observed accuracy of each.
        double[] bucketAccuracy = new double[10];
        int[] bucketCases = new int[10];
        int[] bucketCorrect = new int[10];
        for (double[] s : scored) {
            int b = Math.min(9, (int) Math.floor(s[0] * 10));
            bucketCases[b]++;
            bucketCorrect[b] += (int) s[1];
        }
        for (int b = 0; b < 10; b++) {
            bucketAccuracy[b] = bucketCases[b] == 0 ? 0 : bucketCorrect[b] / (double) bucketCases[b];
            calibration.addObject().put("from", b / 10.0).put("to", (b + 1) / 10.0).put("cases", bucketCases[b])
                    .put("correct", bucketCorrect[b]).put("accuracy", AiTaskEngine.round(bucketAccuracy[b]));
        }
        // Threshold trade-off on the calibrated confidence: share decided automatically and its observed accuracy.
        ArrayNode tradeoff = metrics.putArray("thresholds");
        for (double t : THRESHOLDS) {
            int auto = 0;
            int autoCorrect = 0;
            for (double[] s : scored) {
                int b = Math.min(9, (int) Math.floor(s[0] * 10));
                double calibrated = bucketCases[b] >= 5 ? bucketAccuracy[b] : s[0];
                if (calibrated >= t) {
                    auto++;
                    autoCorrect += (int) s[1];
                }
            }
            tradeoff.addObject().put("threshold", t).put("autoShare", AiTaskEngine.round(cases == 0 ? 0
                    : auto / (double) cases)).put("autoAccuracy", AiTaskEngine.round(auto == 0 ? 0
                    : autoCorrect / (double) auto));
        }
        // Gate.
        JsonNode evaluation = skill.path("evaluation");
        int minCases = evaluation.path("minCases").asInt(DEFAULT_MIN_CASES);
        double minAccuracy = evaluation.path("minAccuracy").asDouble(DEFAULT_MIN_ACCURACY);
        ArrayNode failures = metrics.putArray("failures");
        if (cases < minCases) {
            failures.add("The suite has " + cases + " cases; the skill needs at least " + minCases + ".");
        }
        if (accuracy < minAccuracy) {
            failures.add("Accuracy " + AiTaskEngine.round(accuracy) + " is below " + minAccuracy + ".");
        }
        for (var e : evaluation.path("minRecall").properties()) {
            double r = recall.getOrDefault(e.getKey(), 0.0);
            if (r < e.getValue().asDouble()) {
                failures.add("Recall of " + e.getKey() + " is " + AiTaskEngine.round(r) + ", below "
                        + e.getValue().asDouble() + ".");
            }
        }
        metrics.put("minCases", minCases).put("minAccuracy", minAccuracy);
        return failures.isEmpty();
    }

    private static boolean fieldsMatch(JsonNode expected, ObjectNode actual) {
        for (var e : expected.properties()) {
            JsonNode a = actual == null ? null : actual.get(e.getKey());
            if (a == null || !normalize(a).equals(normalize(e.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(JsonNode v) {
        return v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asString(v.toString()).strip();
    }

    private static boolean mentions(JsonNode phrases, String text) {
        String lower = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        for (JsonNode p : phrases) {
            if (!lower.contains(p.asString("").toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public ObjectNode list(Caller caller, UUID workspaceId, String key, int version) {
        readable(caller, workspaceId, "listAiEvaluations");
        AiSkills.Version v = skills.version(workspaceId, key, version).orElseThrow(ApiException::notFound);
        ObjectNode result = json.object();
        ArrayNode items = result.putArray("items");
        jdbc.sql("SELECT id FROM ai_eval_run WHERE skill_version_id = :v ORDER BY started_at DESC LIMIT 50")
                .param("v", v.id()).query(UUID.class).list()
                .forEach(id -> items.add(view(workspaceId, id).orElseThrow()));
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode get(Caller caller, UUID workspaceId, UUID runId) {
        readable(caller, workspaceId, "getAiEvaluation");
        return view(workspaceId, runId).orElseThrow(ApiException::notFound);
    }

    Optional<ObjectNode> view(UUID workspaceId, UUID runId) {
        return jdbc.sql("""
                SELECT r.id, s.key, v.version, e.key AS suite_key, r.suite_hash, r.model_profile, r.status, r.case_count,
                       r.metrics, r.calibration, r.failure, r.started_by, r.started_at, r.finished_at
                FROM ai_eval_run r JOIN ai_skill_version v ON v.id = r.skill_version_id JOIN ai_skill s ON s.id = v.skill_id
                     JOIN ai_eval_suite e ON e.id = r.suite_id
                WHERE r.workspace_id = :ws AND r.id = :id
                """).param("ws", workspaceId).param("id", runId).query((rs, n) -> {
            ObjectNode o = json.object();
            o.put("id", Sql.uuid(rs, "id").toString()).put("skillKey", rs.getString("key"))
                    .put("version", rs.getInt("version")).put("suiteKey", rs.getString("suite_key"))
                    .put("suiteHash", rs.getString("suite_hash")).put("modelProfile", rs.getString("model_profile"))
                    .put("status", rs.getString("status")).put("caseCount", rs.getInt("case_count"));
            String metrics = rs.getString("metrics");
            String calibration = rs.getString("calibration");
            if (metrics == null) {
                o.putNull("metrics");
            } else {
                o.set("metrics", json.parse(metrics));
            }
            if (calibration == null) {
                o.putNull("calibration");
            } else {
                o.set("calibration", json.parse(calibration));
            }
            if (rs.getString("failure") == null) {
                o.putNull("failure");
            } else {
                o.put("failure", rs.getString("failure"));
            }
            o.put("startedBy", Sql.uuid(rs, "started_by").toString())
                    .put("startedAt", Sql.instant(rs, "started_at").toString());
            Instant finished = Sql.instant(rs, "finished_at");
            if (finished == null) {
                o.putNull("finishedAt");
            } else {
                o.put("finishedAt", finished.toString());
            }
            return o;
        }).optional();
    }

    private void readable(Caller caller, UUID workspaceId, String operationId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.WORKFLOW_READ) && !access.has(Permission.AI_EVALUATE)
                && !access.has(Permission.AI_APPROVE_SKILL)) {
            policy.require(access, Permission.WORKFLOW_READ, operationId, false);
        }
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }
}
