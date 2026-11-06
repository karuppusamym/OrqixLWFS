package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.integration.AgentExecutor;
import com.orqix.integration.CallDeferredException;
import com.orqix.integration.ConnectionDefinition;
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EffectDispatcher;
import com.orqix.integration.EffectJournal;
import com.orqix.integration.HttpExecutor;
import com.orqix.integration.OAuthVault;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Durable agent loops (CG-M03, docs/build/S135). An agent step whose operation declares {@code agent.loop} runs one
 * model turn per execution of its engine job: the job runs {@link AgentExecutor#turn}, stores the conversation
 * encrypted in {@code agent_loop}, and defers itself (the S131 deferral: no attempt is used) to run the next turn at
 * once, or, when a tool waits for a person or a child request, until the loop's deadline. An answer or the child's end
 * makes the job due again ({@link #wake}). The effect journal holds one entry for the whole agent task; tool calls are
 * their own entries. Limits: turns, tool calls and the run budget (executor), the deadline (here), the kill switch
 * (executor, before every turn).
 *
 * <p>The job transaction is rolled back by the deferral, so every write here uses its own transaction; nothing in the
 * job transaction is relied on. Child requests are started by the scheduler outside the job (the engine must not start
 * a process inside the job's command).
 */
@Service
public class AgentLoops {

    private static final Logger log = LoggerFactory.getLogger(AgentLoops.class);

    static final int MAX_STATE_BYTES = 1_500_000;
    static final Duration STATE_GRACE = Duration.ofDays(1);
    static final Duration LEASE_MARGIN = Duration.ofHours(1);
    public static final String TURN = "AGENT_TURN";
    public static final String WAITING = "AGENT_WAITING";

    public record Loop(UUID id, UUID workspaceId, UUID runId, String nodeId, int occurrence, UUID effectId,
            String connectionKey, int connectionVersion, String operation, String status, int turns, int toolCalls,
            BigDecimal costUsd, BigDecimal budgetUsd, Instant startedAt, Instant deadlineAt, String waitKind,
            UUID waitRef, String waitLabel, boolean childPending, String stateKeyId, String stateCipher,
            String errorCode, int version) {

        boolean terminal() {
            return "succeeded".equals(status) || "failed".equals(status) || "stopped".equals(status);
        }
    }

    private final ConnectionService connections;
    private final EffectJournal journal;
    private final AgentExecutor agents;
    private final OAuthVault vault;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final EngineCommands engine;
    private final RunStore runs;
    private final ChildRuns children;
    private final ReleaseQueries releases;
    private final TransactionTemplate independent;

    public AgentLoops(ConnectionService connections, EffectJournal journal, AgentExecutor agents,
            OAuthVault vault, JdbcClient jdbc, Json json, Clock clock, EngineCommands engine, RunStore runs,
            ChildRuns children, ReleaseQueries releases, PlatformTransactionManager tx) {
        this.connections = connections;
        this.journal = journal;
        this.agents = agents;
        this.vault = vault;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.engine = engine;
        this.runs = runs;
        this.children = children;
        this.releases = releases;
        this.independent = new TransactionTemplate(tx);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** True when the operation runs as a durable loop. */
    public boolean durable(UUID workspaceId, String connectionKey, int version, String operation) {
        return connections.definition(workspaceId, connectionKey, version).map(r -> r.definition())
                .filter(ConnectionDefinition::isAgent).map(d -> d.operations().get(operation))
                .map(op -> op.agent() != null && op.agent().durable()).orElse(false);
    }

    /**
     * One job execution of a durable agent step. Returns the outcome when the loop ended; otherwise throws
     * {@link CallDeferredException} so the engine runs the job again (at once, or when the wait ends).
     */
    public EffectDispatcher.DispatchResult turn(EffectDispatcher.DispatchRequest request) {
        ConnectionService.DefinitionRecord record = connections
                .definition(request.workspaceId(), request.connectionKey(), request.connectionVersion()).orElseThrow();
        ConnectionDefinition definition = record.definition();
        ConnectionDefinition.Operation operation = definition.operation(request.operation());
        ConnectionDefinition.LoopLimits limits = operation.agent().loop();
        String effectClass = operation.effectClass().value();
        String effectKey = EffectDispatcher.effectKey(request.workspaceId(), request.runId(), request.nodeId(),
                request.occurrence());
        String requestHash = json.canonicalSha256(request.body());
        Instant now = clock.instant();

        Loop loop = find(request.workspaceId(), request.runId(), request.nodeId(), request.occurrence()).orElse(null);
        if (loop != null && "succeeded".equals(loop.status())) {
            EffectJournal.Effect effect = journal.find(request.workspaceId(), loop.effectId()).orElseThrow();
            return new EffectDispatcher.Succeeded(effect.id(), effect.attempts(), (ObjectNode) effect.result(), null,
                    null, effectClass, true, null, null);
        }
        EffectJournal.Effect effect = loop == null || loop.terminal() ? null
                : journal.find(request.workspaceId(), loop.effectId()).orElse(null);
        if (effect == null || !EffectJournal.IN_FLIGHT.equals(effect.state())) {
            EffectJournal.BeginResult begin = journal.begin(new EffectJournal.EffectRequest(request.workspaceId(),
                    request.runId(), request.nodeId(), request.occurrence(), effectKey, request.connectionKey(),
                    request.connectionVersion(), request.operation(), effectClass, requestHash), request.leaseOwner(),
                    limits.maxDuration().plus(LEASE_MARGIN), null);
            switch (begin.action()) {
                case ALREADY_SUCCEEDED -> {
                    return new EffectDispatcher.Succeeded(begin.effect().id(), begin.effect().attempts(),
                            (ObjectNode) begin.effect().result(), null, null, effectClass, true, null, null);
                }
                case BUSY -> throw new CallDeferredException("AGENT_BUSY", request.connectionKey(),
                        now.plusSeconds(30));
                case HASH_MISMATCH -> {
                    return new EffectDispatcher.Failed(begin.effect().id(), begin.effect().attempts(), false,
                            "INTERNAL_ERROR", "REQUEST_HASH_MISMATCH", "permanent", null, effectClass,
                            "The request for this step changed unexpectedly.");
                }
                case UNKNOWN, POLICY_DENIED -> {
                    return new EffectDispatcher.Failed(begin.effect().id(), begin.effect().attempts(), false,
                            "CONNECTION_UNAVAILABLE", "CONNECTION_UNAVAILABLE", "permanent", null, effectClass,
                            "The connection to " + definition.name() + " is not available.");
                }
                case PROCEED, RECONCILE_FIRST -> effect = begin.effect();
            }
            loop = start(request, loop, effect, limits, now);
        }

        if (now.isAfter(loop.deadlineAt())) {
            return finish(request, loop, effect, effectClass, failed(definition.name(), "AGENT_DEADLINE",
                    definition.name() + " did not finish within its time limit ("
                            + limits.maxDuration().toString().substring(2).toLowerCase() + "). An operator can retry, "
                            + "which starts the task again."));
        }
        AgentExecutor.Resolution resolution = null;
        if ("waiting".equals(loop.status())) {
            resolution = resolution(loop);
            if (resolution == null) {
                throw new CallDeferredException(WAITING, request.connectionKey(), loop.deadlineAt());
            }
        }
        ObjectNode state = state(loop);
        String endpoint = null;
        String bearer = null;
        if (operation.agent().usesOwnBinding()) {
            try {
                var own = agents.ownEndpoint(request.workspaceId(), request.connectionKey(), definition.name());
                endpoint = own.url();
                bearer = own.bearerToken();
            } catch (AgentExecutor.EndpointUnavailable e) {
                return finish(request, loop, effect, effectClass, failed(definition.name(), e.code(), e.getMessage()
                        + " An administrator must fix it, then an operator can retry."));
            }
        }
        AgentExecutor.Turn turn = agents.turn(new AgentExecutor.Request(request.workspaceId(), request.runId(),
                effect.id(), request.initiatorSubjectId(), request.nodeId(), effect.attempts(), definition, operation,
                request.body(), request.resultMapping(), endpoint, bearer, request.timeoutSeconds(),
                request.occurrence()), state, resolution);
        switch (turn.kind()) {
            case FINAL -> {
                return finish(request, loop, effect, effectClass, turn.result());
            }
            case CONTINUE -> {
                save(loop, turn, "running", null, null, null, false);
                throw new CallDeferredException(TURN, request.connectionKey(), now);
            }
            case WAIT -> {
                waitFor(request, loop, turn);
                throw new CallDeferredException(WAITING, request.connectionKey(), loop.deadlineAt());
            }
            default -> throw new IllegalStateException();
        }
    }

    private AgentExecutor.Result failed(String name, String code, String message) {
        return new AgentExecutor.Result(HttpExecutor.Outcome.PERMANENT, code, code, "CONNECTION_UNAVAILABLE", null, null,
                null, message, null);
    }

    // ------------------------------------------------------------------ loop rows

    public Optional<Loop> find(UUID workspaceId, UUID runId, String nodeId, int occurrence) {
        return jdbc.sql("SELECT * FROM agent_loop WHERE workspace_id = :ws AND run_id = :run AND node_id = :node "
                + "AND occurrence = :o").param("ws", workspaceId).param("run", runId).param("node", nodeId)
                .param("o", occurrence).query(this::map).optional();
    }

    Optional<Loop> byId(UUID workspaceId, UUID id) {
        return jdbc.sql("SELECT * FROM agent_loop WHERE workspace_id = :ws AND id = :id").param("ws", workspaceId)
                .param("id", id).query(this::map).optional();
    }

    private Loop map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Loop(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"),
                rs.getString("node_id"), rs.getInt("occurrence"), Sql.uuid(rs, "effect_id"),
                rs.getString("connection_key"), rs.getInt("connection_version"), rs.getString("operation"),
                rs.getString("status"), rs.getInt("turns"), rs.getInt("tool_calls"), rs.getBigDecimal("cost_usd"),
                rs.getBigDecimal("budget_usd"), Sql.instant(rs, "started_at"), Sql.instant(rs, "deadline_at"),
                rs.getString("wait_kind"), Sql.uuid(rs, "wait_ref"), rs.getString("wait_label"),
                rs.getBoolean("child_pending"), rs.getString("state_key_id"), rs.getString("state_cipher"),
                rs.getString("error_code"), rs.getInt("version"));
    }

    /** A new loop, or a fresh start of a failed one (an operator retry starts the task again). */
    private Loop start(EffectDispatcher.DispatchRequest request, Loop existing, EffectJournal.Effect effect,
            ConnectionDefinition.LoopLimits limits, Instant now) {
        independent.executeWithoutResult(status -> {
            if (existing == null) {
                jdbc.sql("""
                        INSERT INTO agent_loop (id, workspace_id, run_id, node_id, occurrence, effect_id, connection_key,
                            connection_version, operation, status, budget_usd, started_at, deadline_at, updated_at)
                        VALUES (:id, :ws, :run, :node, :o, :effect, :key, :version, :op, 'running', :budget, :now,
                            :deadline, :now)
                        ON CONFLICT (workspace_id, run_id, node_id, occurrence) DO NOTHING
                        """).param("id", Ids.newId()).param("ws", request.workspaceId()).param("run", request.runId())
                        .param("node", request.nodeId()).param("o", request.occurrence()).param("effect", effect.id())
                        .param("key", request.connectionKey()).param("version", request.connectionVersion())
                        .param("op", request.operation()).param("budget", limits.budgetUsd())
                        .param("now", Sql.ts(now)).param("deadline", Sql.ts(now.plus(limits.maxDuration()))).update();
            } else if (existing.terminal()) {
                cancelRequests(existing.workspaceId(), existing.id(), "cancelled");
                jdbc.sql("""
                        UPDATE agent_loop SET status = 'running', effect_id = :effect, turns = 0, tool_calls = 0,
                            cost_usd = 0, started_at = :now, deadline_at = :deadline, wait_kind = NULL, wait_ref = NULL,
                            wait_label = NULL, child_pending = false, state_key_id = NULL, state_cipher = NULL,
                            state_expires_at = NULL, error_code = NULL, version = version + 1, updated_at = :now
                        WHERE id = :id
                        """).param("effect", effect.id()).param("now", Sql.ts(now))
                        .param("deadline", Sql.ts(now.plus(limits.maxDuration()))).param("id", existing.id()).update();
            }
        });
        return find(request.workspaceId(), request.runId(), request.nodeId(), request.occurrence()).orElseThrow();
    }

    private void save(Loop loop, AgentExecutor.Turn turn, String status, String waitKind, UUID waitRef, String label,
            boolean childPending) {
        Instant now = clock.instant();
        String text = json.write(turn.state());
        if (text.length() > MAX_STATE_BYTES) {
            throw new IllegalStateException("Agent state too large");
        }
        OAuthVault.Sealed sealed = vault.seal(context(loop), text);
        independent.executeWithoutResult(s -> jdbc.sql("""
                UPDATE agent_loop SET status = :status, turns = :turns, tool_calls = :calls,
                    cost_usd = cost_usd + :cost, wait_kind = :kind, wait_ref = :ref, wait_label = :label,
                    child_pending = :pending, state_key_id = :keyId, state_cipher = :cipher,
                    state_expires_at = :expires, version = version + 1, updated_at = :now
                WHERE id = :id
                """).param("status", status).param("turns", turn.turns()).param("calls", turn.toolCalls())
                .param("cost", turn.turnCost()).param("kind", waitKind).param("ref", waitRef)
                .param("label", label == null ? null : label.length() > 80 ? label.substring(0, 80) : label)
                .param("pending", childPending).param("keyId", sealed.keyId()).param("cipher", sealed.ciphertext())
                .param("expires", Sql.ts(loop.deadlineAt().plus(STATE_GRACE))).param("now", Sql.ts(now))
                .param("id", loop.id()).update());
    }

    private ObjectNode state(Loop loop) {
        if (loop.stateCipher() == null) {
            return null;
        }
        return (ObjectNode) json.parse(vault.open(context(loop), loop.stateKeyId(), loop.stateCipher(),
                MAX_STATE_BYTES * 2));
    }

    private static String context(Loop loop) {
        return "agent-loop|" + loop.workspaceId() + "|" + loop.runId() + "|" + loop.id();
    }

    static String requestContext(UUID workspaceId, UUID requestId, String part) {
        return "agent-request|" + workspaceId + "|" + requestId + "|" + part;
    }

    /** Records the outcome in the journal, ends the loop and clears its state. */
    private EffectDispatcher.DispatchResult finish(EffectDispatcher.DispatchRequest request, Loop loop,
            EffectJournal.Effect effect, String effectClass, AgentExecutor.Result run) {
        int attempt = effect.attempts();
        String status = switch (run.outcome()) {
            case SUCCEEDED -> "succeeded";
            case RETRYABLE -> "running";
            default -> "failed";
        };
        independent.executeWithoutResult(s -> {
            if (!"running".equals(status)) {
                cancelRequests(loop.workspaceId(), loop.id(), "cancelled");
            }
            jdbc.sql("""
                    UPDATE agent_loop SET status = :status, error_code = :code, wait_kind = NULL, wait_ref = NULL,
                        wait_label = NULL, child_pending = false,
                        state_key_id = CASE WHEN :status = 'running' THEN state_key_id END,
                        state_cipher = CASE WHEN :status = 'running' THEN state_cipher END,
                        state_expires_at = CASE WHEN :status = 'running' THEN state_expires_at END,
                        version = version + 1, updated_at = :now
                    WHERE id = :id
                    """).param("status", status).param("code", run.errorCode()).param("now", Sql.ts(clock.instant()))
                    .param("id", loop.id()).update();
        });
        switch (run.outcome()) {
            case SUCCEEDED -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.SUCCEEDED, null, null, run.detail(),
                        run.result(), null);
                return new EffectDispatcher.Succeeded(effect.id(), attempt, run.result(), null, null, effectClass,
                        false, run.summary(), run.meta());
            }
            case RETRYABLE -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.RETRYABLE_FAILURE, null,
                        run.errorCode(), run.detail(), null, null);
                return new EffectDispatcher.Failed(effect.id(), attempt, true, "RETRIES_EXHAUSTED", run.errorCode(),
                        "retryable", null, effectClass, run.businessMessage());
            }
            default -> {
                journal.finish(request.workspaceId(), effect.id(), EffectJournal.PERMANENT_FAILURE, null,
                        run.errorCode(), run.detail(), null, null);
                return new EffectDispatcher.Failed(effect.id(), attempt, false, run.attentionReason(),
                        run.errorCode(), "permanent", null, effectClass, run.businessMessage());
            }
        }
    }

    // ------------------------------------------------------------------ waits

    private void waitFor(EffectDispatcher.DispatchRequest request, Loop loop, AgentExecutor.Turn turn) {
        AgentExecutor.Wait wait = turn.pause();
        if ("workflow".equals(wait.kind())) {
            ObjectNode state = turn.state().deepCopy();
            ObjectNode start = state.putObject("childStart");
            start.put("workflowKey", wait.workflowKey()).put("release", wait.release())
                    .put("occurrence", 100 + turn.toolCalls()).set("input", wait.childInput());
            save(loop, new AgentExecutor.Turn(turn.kind(), null, state, wait, turn.turnCost(), turn.turns(),
                    turn.toolCalls()), "waiting", "workflow", null, wait.toolLabel(), true);
            return;
        }
        UUID requestId = Ids.newId();
        OAuthVault.Sealed detail = vault.seal(requestContext(loop.workspaceId(), requestId, "detail"),
                json.write(wait.detail()));
        UUID requester = request.initiatorSubjectId();
        independent.executeWithoutResult(s -> jdbc.sql("""
                INSERT INTO agent_human_request (id, workspace_id, loop_id, run_id, node_id, kind, tool_name, tool_label,
                    group_key, requested_by, status, answer_schema, detail_key_id, detail_cipher, created_at)
                VALUES (:id, :ws, :loop, :run, :node, :kind, :tool, :label, :group, :by, 'open',
                    CAST(:schema AS jsonb), :keyId, :cipher, :now)
                """).param("id", requestId).param("ws", loop.workspaceId()).param("loop", loop.id())
                .param("run", loop.runId()).param("node", loop.nodeId()).param("kind", wait.kind())
                .param("tool", wait.toolName()).param("label", wait.toolLabel()).param("group", wait.group())
                .param("by", requester).param("schema", wait.answerSchema() == null ? null : json.write(wait.answerSchema()))
                .param("keyId", detail.keyId()).param("cipher", detail.ciphertext())
                .param("now", Sql.ts(clock.instant())).update());
        save(loop, turn, "waiting", wait.kind(), requestId, wait.toolLabel(), false);
    }

    /** How the loop's wait ended, or null while it lasts. */
    private AgentExecutor.Resolution resolution(Loop loop) {
        if ("workflow".equals(loop.waitKind())) {
            if (loop.childPending()) {
                return null;
            }
            if (loop.waitRef() == null) {
                ObjectNode answer = json.object().put("outcome", "notStarted")
                        .put("reason", loop.errorCode() == null ? "The child request could not start." : loop.errorCode());
                return new AgentExecutor.Resolution("ended", answer);
            }
            RunStore.RunRow child = runs.find(loop.workspaceId(), loop.waitRef()).orElse(null);
            if (child == null || child.isActive()) {
                return null;
            }
            ObjectNode answer = json.object();
            answer.put("reference", child.reference());
            answer.put("outcome", child.outcome() == null ? "Cancelled" : child.outcome());
            answer.put("outcomeKind", child.outcomeKind() == null ? "cancelled" : child.outcomeKind());
            if (child.output() != null && child.output().isObject()) {
                answer.set("output", child.output());
            }
            return new AgentExecutor.Resolution("ended", answer);
        }
        record Decided(String status, String keyId, String cipher) {
        }
        Decided decided = jdbc.sql("SELECT status, answer_key_id, answer_cipher FROM agent_human_request "
                + "WHERE workspace_id = :ws AND id = :id").param("ws", loop.workspaceId()).param("id", loop.waitRef())
                .query((rs, n) -> new Decided(rs.getString("status"), rs.getString("answer_key_id"),
                        rs.getString("answer_cipher"))).optional().orElse(null);
        if (decided == null || "open".equals(decided.status())) {
            return null;
        }
        ObjectNode answer = decided.cipher() == null ? null : (ObjectNode) json.parse(vault.open(
                requestContext(loop.workspaceId(), loop.waitRef(), "answer"), decided.keyId(), decided.cipher()));
        String outcome = switch (decided.status()) {
            case "answered", "approved", "rejected" -> decided.status();
            default -> "expired";
        };
        return new AgentExecutor.Resolution(outcome, answer);
    }

    /** Called when a child request ends (inside the child's ending transaction). True when it belonged to a loop. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean childEnded(RunStore.RunRow child) {
        Loop loop = jdbc.sql("SELECT * FROM agent_loop WHERE workspace_id = :ws AND wait_ref = :child "
                + "AND status = 'waiting' AND wait_kind = 'workflow'").param("ws", child.workspaceId())
                .param("child", child.id()).query(this::map).optional().orElse(null);
        if (loop == null) {
            return jdbc.sql("SELECT count(*) FROM agent_loop WHERE workspace_id = :ws AND run_id = :run AND node_id = :node")
                    .param("ws", child.workspaceId()).param("run", child.parentRunId())
                    .param("node", child.parentNodeId()).query(Integer.class).single() > 0;
        }
        wake(loop);
        return true;
    }

    /** Makes the loop's job due now; the scheduler repeats this for a wait that ended while its turn was running. */
    void wake(Loop loop) {
        RunStore.RunRow run = runs.find(loop.workspaceId(), loop.runId()).orElse(null);
        if (run == null || !run.isActive() || run.engineProcessInstanceId() == null) {
            return;
        }
        engine.wakeTimer(run.engineProcessInstanceId(), loop.nodeId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void wake(UUID workspaceId, UUID loopId) {
        byId(workspaceId, loopId).ifPresent(this::wake);
    }

    void cancelRequests(UUID workspaceId, UUID loopId, String status) {
        jdbc.sql("""
                UPDATE agent_human_request SET status = :status, detail_key_id = NULL, detail_cipher = NULL,
                    answer_key_id = NULL, answer_cipher = NULL, version = version + 1
                WHERE workspace_id = :ws AND loop_id = :loop AND status = 'open'
                """).param("status", status).param("ws", workspaceId).param("loop", loopId).update();
        // Payloads of decided requests are not needed once the loop moves on.
        jdbc.sql("""
                UPDATE agent_human_request SET detail_key_id = NULL, detail_cipher = NULL, answer_key_id = NULL,
                    answer_cipher = NULL
                WHERE workspace_id = :ws AND loop_id = :loop AND status <> 'open' AND detail_cipher IS NOT NULL
                """).param("ws", workspaceId).param("loop", loopId).update();
    }

    // ------------------------------------------------------------------ scheduler work

    /** Starts the child requests that waiting loops asked for. Returns how many were handled. */
    int startPendingChildren() {
        List<UUID> pending = jdbc.sql("SELECT id FROM agent_loop WHERE child_pending ORDER BY updated_at LIMIT 10")
                .query(UUID.class).list();
        int handled = 0;
        for (UUID id : pending) {
            try {
                Boolean done = independent.execute(s -> startChild(id));
                if (Boolean.TRUE.equals(done)) {
                    handled++;
                }
            } catch (RuntimeException e) {
                log.warn("Agent child request could not be started: {}", e.getClass().getSimpleName());
            }
        }
        return handled;
    }

    private boolean startChild(UUID id) {
        Loop loop = jdbc.sql("SELECT * FROM agent_loop WHERE id = :id AND child_pending FOR UPDATE SKIP LOCKED")
                .param("id", id).query(this::map).optional().orElse(null);
        if (loop == null) {
            return false;
        }
        ObjectNode state = state(loop);
        JsonNode start = state == null ? null : state.path("childStart");
        UUID child = null;
        String problem = null;
        try {
            RunStore.RunRow parent = runs.find(loop.workspaceId(), loop.runId()).orElseThrow();
            UUID workflowId = jdbc.sql("SELECT id FROM workflow WHERE workspace_id = :ws AND key = :key")
                    .param("ws", loop.workspaceId()).param("key", start.path("workflowKey").asString())
                    .query(UUID.class).optional().orElseThrow(() -> new IllegalStateException("The workflow is unknown"));
            UUID releaseId = jdbc.sql("SELECT id FROM workflow_release WHERE workspace_id = :ws AND workflow_id = :wf "
                    + "AND release_number = :n").param("ws", loop.workspaceId()).param("wf", workflowId)
                    .param("n", start.path("release").asInt()).query(UUID.class).optional()
                    .orElseThrow(() -> new IllegalStateException("The release is unknown"));
            if (!parent.isActive()) {
                throw new IllegalStateException("The request has ended");
            }
            child = children.startForAgent(loop.workspaceId(), loop.runId(), loop.nodeId(),
                    start.path("occurrence").asInt(101), releaseId, start.path("input"));
        } catch (IllegalStateException | java.util.NoSuchElementException e) {
            problem = e.getMessage() == null ? "The child request could not start." : e.getMessage();
        }
        jdbc.sql("UPDATE agent_loop SET child_pending = false, wait_ref = :child, error_code = :problem, "
                + "version = version + 1, updated_at = :now WHERE id = :id").param("child", child)
                .param("problem", problem == null ? null : problem.length() > 64 ? problem.substring(0, 64) : problem)
                .param("now", Sql.ts(clock.instant())).param("id", id).update();
        if (problem != null) {
            wake(loop); // the agent is told the child did not start
        }
        return true;
    }

    /** Wakes loops whose wait already ended (an answer that arrived while the turn was running). */
    int wakeResolved(Instant now) {
        List<Loop> waiting = jdbc.sql("""
                SELECT l.* FROM agent_loop l
                WHERE l.status = 'waiting' AND l.updated_at < :before AND (
                    (l.wait_kind IN ('ask', 'approval', 'elicitation') AND EXISTS (SELECT 1 FROM agent_human_request h
                        WHERE h.id = l.wait_ref AND h.status <> 'open'))
                    OR (l.wait_kind = 'workflow' AND NOT l.child_pending AND (l.wait_ref IS NULL OR EXISTS (
                        SELECT 1 FROM workflow_run c WHERE c.workspace_id = l.workspace_id AND c.id = l.wait_ref
                          AND c.lifecycle IN ('completed', 'cancelled'))))
                    OR l.deadline_at < :now)
                ORDER BY l.updated_at LIMIT 20
                """).param("before", Sql.ts(now.minusSeconds(5))).param("now", Sql.ts(now)).query(this::map).list();
        for (Loop loop : waiting) {
            try {
                independent.executeWithoutResult(s -> {
                    wake(loop);
                    jdbc.sql("UPDATE agent_loop SET updated_at = :now WHERE id = :id").param("now", Sql.ts(now))
                            .param("id", loop.id()).update();
                });
            } catch (RuntimeException e) {
                log.warn("Agent loop could not be woken: {}", e.getClass().getSimpleName());
            }
        }
        return waiting.size();
    }

    /** Stops loops of requests that ended, and clears expired state. */
    int sweep(Instant now) {
        int stopped = independent.execute(s -> {
            List<Loop> orphaned = jdbc.sql("""
                    SELECT l.* FROM agent_loop l JOIN workflow_run r ON r.workspace_id = l.workspace_id AND r.id = l.run_id
                    WHERE l.status IN ('running', 'waiting') AND r.lifecycle IN ('completed', 'cancelled')
                    LIMIT 50
                    """).query(this::map).list();
            for (Loop loop : orphaned) {
                cancelRequests(loop.workspaceId(), loop.id(), "cancelled");
                jdbc.sql("""
                        UPDATE agent_loop SET status = 'stopped', wait_kind = NULL, wait_ref = NULL, wait_label = NULL,
                            child_pending = false, state_key_id = NULL, state_cipher = NULL, state_expires_at = NULL,
                            version = version + 1, updated_at = :now WHERE id = :id
                        """).param("now", Sql.ts(now)).param("id", loop.id()).update();
            }
            return orphaned.size();
        });
        independent.executeWithoutResult(s -> {
            jdbc.sql("""
                    UPDATE agent_loop SET state_key_id = NULL, state_cipher = NULL, state_expires_at = NULL
                    WHERE state_cipher IS NOT NULL AND state_expires_at < :now
                    """).param("now", Sql.ts(now)).update();
            jdbc.sql("""
                    UPDATE agent_human_request SET status = 'expired', detail_key_id = NULL, detail_cipher = NULL,
                        version = version + 1
                    WHERE status = 'open' AND loop_id IN (SELECT id FROM agent_loop WHERE deadline_at < :now)
                    """).param("now", Sql.ts(now)).update();
        });
        return stopped;
    }

}
