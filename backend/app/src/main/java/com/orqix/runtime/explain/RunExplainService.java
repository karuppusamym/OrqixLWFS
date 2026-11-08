package com.orqix.runtime.explain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceFeatures;
import com.orqix.integration.ConnectionStatus;
import com.orqix.integration.OperatorExplainPort;
import com.orqix.runtime.api.ExplainDtos;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.api.WaitDtos;
import com.orqix.runtime.internal.RunAccess;
import com.orqix.runtime.internal.RunEvents;
import com.orqix.runtime.internal.RunService;
import com.orqix.runtime.internal.WaitsService;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * "Explain this request" (CG-A07, doc 29 section 7.12): why a request is stuck and which of the caller's permitted
 * actions would unblock it. <b>It never acts.</b> The only writes are the {@code ai_generation} metering row (when a
 * model was called) and one audit entry, each in its own transaction; the request, its tasks, waits and engine state are
 * only read.
 *
 * <p>Access: whoever may see the request (404 otherwise) and holds {@code run.operate} (403, audited). Facts come from
 * {@link RunFacts}; the answer from the model through {@link OperatorExplainPort} (the AI module's provider SPI,
 * residency rule, budget and metering) when AI is available, else from
 * {@link RuleExplainer}. A model's answer is reused for the same caller and request state for a short time.
 */
@Service
public class RunExplainService {

    static final String OPERATION = "explainRun";
    private static final int CACHE_LIMIT = 256;

    private record Cached(Instant until, AiAnswer.Parsed answer, String profile, String model) {
    }

    private final RunAccess runAccess;
    private final PolicyService policy;
    private final RunService runs;
    private final RunEvents events;
    private final WaitsService waits;
    private final ReleaseQueries releases;
    private final ConnectionStatus connections;
    private final WorkspaceFeatures features;
    private final OperatorExplainPort model;
    private final ExplainProperties properties;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RunExplainService(RunAccess runAccess, PolicyService policy, RunService runs, RunEvents events,
            WaitsService waits, ReleaseQueries releases, ConnectionStatus connections, WorkspaceFeatures features,
            OperatorExplainPort model, ExplainProperties properties, AuditWriter audit, Json json, Clock clock,
            PlatformTransactionManager transactions) {
        this.runAccess = runAccess;
        this.policy = policy;
        this.runs = runs;
        this.events = events;
        this.waits = waits;
        this.releases = releases;
        this.connections = connections;
        this.features = features;
        this.model = model;
        this.properties = properties;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactions);
    }

    private record Gathered(RunFacts.Input input, RunFacts.Result facts, UUID workflowId) {
    }

    public ExplainDtos.RunExplanation explain(Caller caller, UUID workspaceId, UUID runId,
            ExplainDtos.ExplainRunRequest request) {
        Gathered g = readTx.execute(status -> gather(caller, workspaceId, runId));
        RuntimeDtos.RunDetail run = g.input().run();
        RunFacts.Result facts = g.facts();
        String digest = digest(facts);
        boolean preferRules = request != null && Boolean.TRUE.equals(request.preferRules());

        String fallback = null;
        AiAnswer.Parsed answer = null;
        ExplainDtos.AiInfo info = null;
        // AI switched off (deployment or workspace) wins over a cached answer.
        String off = !properties.enabled() ? "aiOff"
                : !features.enabled(workspaceId, WorkspaceFeatures.Feature.AI) ? "featureOff" : null;
        if (!preferRules && off != null) {
            fallback = off;
        } else if (!preferRules) {
            String key = caller.subjectId() + "|" + runId + "|" + digest;
            Cached hit = cache.get(key);
            if (hit != null && hit.until().isAfter(clock.instant())) {
                answer = hit.answer();
                info = new ExplainDtos.AiInfo(hit.profile(), hit.model(), AiAnswer.PROMPT_VERSION, true);
            } else {
                Attempt attempt = callModel(caller, workspaceId, g, run.lifecycle());
                fallback = attempt.fallback();
                if (attempt.answer() != null) {
                    answer = attempt.answer();
                    info = new ExplainDtos.AiInfo(attempt.profile(), attempt.model(), AiAnswer.PROMPT_VERSION, false);
                    if (!properties.cacheTtl().isZero()) {
                        if (cache.size() >= CACHE_LIMIT) {
                            cache.clear();
                        }
                        cache.put(key, new Cached(clock.instant().plus(properties.cacheTtl()), answer,
                                attempt.profile(), attempt.model()));
                    }
                }
            }
        }
        ExplainDtos.RunExplanation result;
        if (answer != null) {
            result = new ExplainDtos.RunExplanation(run.id(), run.version(), run.lifecycle(), "ai", answer.summary(),
                    answer.cause(), answer.evidence(), answer.suggestedActions(), facts.facts(), facts.actions(),
                    answer.droppedActions(), null, info, true, digest, clock.instant());
        } else {
            RuleExplainer.Explanation rules = RuleExplainer.explain(g.input(), facts);
            result = new ExplainDtos.RunExplanation(run.id(), run.version(), run.lifecycle(), "rules",
                    rules.summary(), rules.cause(), rules.evidence(), rules.suggestedActions(), facts.facts(),
                    facts.actions(), List.of(), fallback, null, true, digest, clock.instant());
        }
        audit(caller, workspaceId, runId, result);
        return result;
    }

    // ------------------------------------------------------------------ facts

    private Gathered gather(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, OPERATION, true);
        RuntimeDtos.RunDetail run = runs.detail(visible, List.of());
        WaitDtos.RunWaitList waitList = waits.forRun(caller, workspaceId, runId);
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, visible.run().releaseId());
        long from = Math.max(0, visible.run().eventSeq() - properties.eventWindow());
        List<RunFacts.Event> timeline = events.list(workspaceId, runId, from, properties.eventWindow() + 1).stream()
                .map(e -> new RunFacts.Event(e.seq(), e.type(), e.nodeId(), e.occurredAt(), e.data())).toList();
        Set<String> keys = new LinkedHashSet<>();
        if (run.effects() != null) {
            run.effects().forEach(e -> keys.add(e.connectionKey()));
        }
        waitList.items().forEach(w -> {
            if (w.connectionKey() != null) {
                keys.add(w.connectionKey());
            }
        });
        List<String> nodes = new ArrayList<>();
        if (run.attention() != null && run.attention().nodeId() != null) {
            nodes.add(run.attention().nodeId());
        }
        run.currentSteps().forEach(s -> nodes.add(s.nodeId()));
        for (String nodeId : nodes) {
            JsonNode node = release.node(nodeId);
            if (node != null && node.path("config").hasNonNull("connectionRef")) {
                keys.add(node.path("config").path("connectionRef").asString());
            }
        }
        Map<String, RunFacts.ConnectionState> states = new LinkedHashMap<>();
        keys.stream().filter(k -> k != null && !k.isBlank()).limit(5)
                .forEach(k -> states.put(k, connection(workspaceId, k)));
        RunFacts.Input input = new RunFacts.Input(run, timeline, waitList, release.definition(), states,
                visible.access().permissions(), visible.dataVisible(), clock.instant());
        return new Gathered(input, RunFacts.build(input), visible.run().workflowId());
    }

    private RunFacts.ConnectionState connection(UUID workspaceId, String key) {
        ConnectionStatus.Snapshot c = connections.snapshot(workspaceId, key);
        return new RunFacts.ConnectionState(key, c.name(), c.bound(), c.enabled(), c.authType(), c.circuitState(),
                c.circuitOpenUntil(), c.circuitReason(), c.consecutiveFailures(), c.tokenRefreshFailuresLastHour(),
                c.lastErrorHttpStatus(), c.lastErrorCode(), c.lastErrorAt());
    }

    // ------------------------------------------------------------------ model

    private record Attempt(AiAnswer.Parsed answer, String fallback, String profile, String model) {
        static Attempt fallback(String reason) {
            return new Attempt(null, reason, null, null);
        }
    }

    private Attempt callModel(Caller caller, UUID workspaceId, Gathered g, String lifecycle) {
        RunFacts.Result facts = g.facts();
        AiAnswer.Parsed[] parsed = new AiAnswer.Parsed[1];
        OperatorExplainPort.Result result = model.complete(new OperatorExplainPort.Request(workspaceId,
                caller.subjectId(), g.workflowId(), properties.profile(), AiAnswer.PROMPT_VERSION, AiAnswer.SYSTEM,
                AiAnswer.user(json.mapper(), lifecycle, facts), "run_explanation", AiAnswer.schema(json.mapper()),
                properties.maxOutputTokens(), properties.timeout(), content -> {
                    parsed[0] = AiAnswer.parse(json.mapper(), content, facts);
                    return parsed[0] != null;
                }));
        if (result.content() == null) {
            return Attempt.fallback(result.unavailableReason());
        }
        if (parsed[0] == null) {
            return Attempt.fallback("invalidAnswer");
        }
        return new Attempt(parsed[0], null, result.profile(), result.model());
    }

    // ------------------------------------------------------------------ audit and helpers

    private void audit(Caller caller, UUID workspaceId, UUID runId, ExplainDtos.RunExplanation result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", result.source());
        metadata.put("profile", result.ai() == null ? null : result.ai().profile());
        metadata.put("model", result.ai() == null ? null : result.ai().model());
        metadata.put("promptVersion", result.ai() == null ? null : AiAnswer.PROMPT_VERSION);
        metadata.put("cached", result.ai() != null && result.ai().cached());
        metadata.put("factCount", result.facts().size());
        metadata.put("suggestedCount", result.suggestedActions().size());
        metadata.put("droppedCount", result.droppedActions().size());
        metadata.put("fallbackReason", result.fallbackReason());
        writeTx.executeWithoutResult(status -> audit.write(new AuditWriter.Entry(workspaceId,
                AuditWriter.ActorType.USER, caller.subjectId(), "ai.run.explained", "run", runId.toString(),
                AuditWriter.Outcome.SUCCESS, metadata)));
    }

    private String digest(RunFacts.Result facts) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("facts", facts.facts().stream().map(f -> f.id() + "|" + f.text()).toList());
        state.put("actions", facts.actions().stream().map(ExplainDtos.PermittedAction::id).toList());
        return Json.sha256Hex(json.write(state));
    }

}
