package com.orqix.runtime.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.integration.EffectJournal;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;

import tools.jackson.databind.JsonNode;

/**
 * What a request holds right now, as the migration and move rules need it (docs/build/S143): where it waits, which
 * steps produced data, which loop paths it took, which calls are in flight. Read inside the caller's transaction,
 * normally under the run lock.
 */
@Component
public class RunFacts {

    private static final Set<String> IN_DOUBT = Set.of(EffectJournal.PENDING, EffectJournal.IN_FLIGHT,
            EffectJournal.UNKNOWN);

    private final EngineCommands engine;
    private final EffectJournal journal;
    private final JdbcClient jdbc;

    public RunFacts(EngineCommands engine, EffectJournal journal, JdbcClient jdbc) {
        this.engine = engine;
        this.journal = journal;
        this.jdbc = jdbc;
    }

    /** Canonical ids of the steps the request is at, plus the step needing attention. */
    public List<String> activeSteps(RunStore.RunRow run) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode step : run.currentSteps()) {
            String id = step.path("nodeId").asString("");
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        String attention = attentionStep(run);
        if (attention != null) {
            ids.add(attention);
        }
        return new ArrayList<>(ids);
    }

    public String attentionStep(RunStore.RunRow run) {
        if (!RunStore.NEEDS_ATTENTION.equals(run.lifecycle()) || run.attention() == null) {
            return null;
        }
        String id = run.attention().path("nodeId").asString(null);
        return id == null ? null : stripOccurrence(id);
    }

    /** Steps whose output data the request holds. */
    public Set<String> producedSteps(RunStore.RunRow run) {
        Set<String> result = new TreeSet<>();
        for (String name : engine.variables(run.engineProcessInstanceId(), ProcessData.STEP_PREFIX).keySet()) {
            result.add(name.substring(ProcessData.STEP_PREFIX.length()));
        }
        return result;
    }

    /** The {@code orqix_step_<id>} value of a step, or null. */
    public Object stepData(RunStore.RunRow run, String nodeId) {
        return engine.variables(run.engineProcessInstanceId(), ProcessData.stepVariable(nodeId))
                .get(ProcessData.stepVariable(nodeId));
    }

    /** Bounded loop paths (transition ids) already taken at least once. */
    public Set<String> loopTraversals(RunStore.RunRow run) {
        Set<String> result = new TreeSet<>();
        engine.variables(run.engineProcessInstanceId(), ProcessData.TRAVERSAL_PREFIX).forEach((name, value) -> {
            if (value instanceof Number n && n.intValue() > 0) {
                result.add(name.substring(ProcessData.TRAVERSAL_PREFIX.length()));
            }
        });
        return result;
    }

    /** Steps with an external call started and not finished, or with an outcome nobody confirmed. */
    public Set<String> effectsInDoubt(RunStore.RunRow run) {
        Set<String> result = new TreeSet<>();
        for (EffectJournal.Effect effect : journal.forRun(run.workspaceId(), run.id())) {
            if (IN_DOUBT.contains(effect.state())) {
                result.add(stripOccurrence(effect.nodeId()));
            }
        }
        return result;
    }

    /** Worker jobs a worker holds and checks that are running: work an operator cannot see the result of yet. */
    public Set<String> busySteps(RunStore.RunRow run) {
        Set<String> result = new TreeSet<>();
        for (String id : jdbc.sql("""
                SELECT node_id FROM external_job WHERE workspace_id = :ws AND run_id = :run AND status = 'locked'
                UNION SELECT node_id FROM poll_wait WHERE workspace_id = :ws AND run_id = :run AND state = 'checking'
                """).param("ws", run.workspaceId()).param("run", run.id()).query(String.class).list()) {
            result.add(stripOccurrence(id));
        }
        return result;
    }

    /** Steps the request has been at, from its timeline. */
    public Set<String> visitedSteps(RunStore.RunRow run) {
        Set<String> result = new TreeSet<>();
        for (String id : jdbc.sql("""
                SELECT DISTINCT node_id FROM run_event WHERE workspace_id = :ws AND run_id = :run AND node_id IS NOT NULL
                """).param("ws", run.workspaceId()).param("run", run.id()).query(String.class).list()) {
            String step = stripOccurrence(id);
            result.add(step.contains("__") ? step.substring(0, step.indexOf("__")) : step);
        }
        return result;
    }

    /** Active steps whose running child requests hold the request (a child step, S12). */
    public Set<String> childSteps(RunStore.RunRow run, RunStore runs) {
        Set<String> result = new TreeSet<>();
        for (RunStore.RunRow child : runs.children(run.workspaceId(), run.id())) {
            if (child.isActive() && child.parentNodeId() != null) {
                result.add(child.parentNodeId());
            }
        }
        return result;
    }

    public MigrationRules.Facts migrationFacts(RunStore.RunRow run) {
        return new MigrationRules.Facts(activeSteps(run), producedSteps(run), loopTraversals(run), effectsInDoubt(run),
                busySteps(run));
    }

    public MoveRules.Facts moveFacts(RunStore.RunRow run) {
        return new MoveRules.Facts(activeSteps(run), producedSteps(run), visitedSteps(run), effectsInDoubt(run),
                attentionStep(run));
    }

    static String stripOccurrence(String effectNode) {
        int hash = effectNode.indexOf('#');
        return hash < 0 ? effectNode : effectNode.substring(0, hash);
    }

    /** The workspace requires a second operator for moves: a production stage (explicit or by default) or regulated. */
    public String fourEyesReason(UUID workspaceId) {
        Boolean isTest = jdbc.sql("SELECT is_test FROM workspace WHERE id = :ws").param("ws", workspaceId)
                .query(Boolean.class).optional().orElse(false);
        String stage = jdbc.sql("SELECT stage FROM workspace_environment WHERE workspace_id = :ws")
                .param("ws", workspaceId).query(String.class).optional()
                .orElse(Boolean.TRUE.equals(isTest) ? "test" : "production");
        if ("production".equals(stage)) {
            return "This is a production workspace: a second operator must approve the move.";
        }
        boolean regulated = jdbc.sql("SELECT regulated FROM ai_workspace_policy WHERE workspace_id = :ws")
                .param("ws", workspaceId).query(Boolean.class).optional().orElse(false);
        return regulated ? "This workspace is regulated: a second operator must approve the move." : null;
    }
}
