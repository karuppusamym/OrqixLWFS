package com.orqix.runtime.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * EX09: read-only comparison of the Orqix projection with what the engine holds, and the one repair that is provably
 * safe: rebuilding the open approval steps from live engine tasks. Nothing here triggers, skips or completes an
 * engine activity, so it cannot run a step twice or invent a business outcome. A token that nothing can move
 * (strandedToken) is only reported; the operator may cancel the request.
 */
@Component
public class RunRepair {
    public static final String IN_SYNC = "inSync";
    public static final String PROJECTION_DRIFT = "projectionDrift";
    public static final String STRANDED_TOKEN = "strandedToken";
    public static final String EMPTY_REQUEST = "emptyRequest";
    public static final String ENGINE_MISSING = "engineMissing";
    public static final String UNSUPPORTED = "unsupported";
    public static final String PROTECTED = "protected";
    public static final String ENDED = "ended";

    private final JdbcClient jdbc;
    private final EngineCommands engine;
    private final RunSuspensions suspensions;
    private final Json json;

    public RunRepair(JdbcClient jdbc, EngineCommands engine, RunSuspensions suspensions, Json json) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.suspensions = suspensions;
        this.json = json;
    }

    /** One approval the engine holds, with the facts of its open Orqix task row. */
    public record Rebuild(String nodeId, String taskId, String label, String group, String assignee, String dueAt,
            String since) {
    }

    public record Verdict(RuntimeDtos.RunDiagnosis diagnosis, List<Rebuild> rebuild) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Verdict diagnose(RunStore.RunRow run) {
        List<String> projected = new ArrayList<>();
        boolean approvalsOnly = true;
        boolean waitsOutsideEngine = false;
        for (JsonNode step : run.currentSteps()) {
            projected.add(step.path("nodeId").asString(""));
            String kind = step.path("kind").asString("");
            approvalsOnly &= "approval".equals(kind);
            waitsOutsideEngine |= "child".equals(kind) || "external".equals(kind);
        }
        List<String> sortedProjection = List.copyOf(new TreeSet<>(projected));
        if (!run.isActive()) {
            return verdict(ENDED, "The request has ended; there is nothing to repair.", sortedProjection, List.of(), run);
        }
        if (suspensions.isSuspended(run.workspaceId(), run.id())) {
            return verdict(PROTECTED, "The request is suspended. Resume it before checking or repairing it.",
                    sortedProjection, List.of(), run);
        }
        var snap = engine.snapshot(run.engineProcessInstanceId());
        if (!snap.exists()) {
            return verdict(ENGINE_MISSING,
                    "The workflow engine has no record of this request. Its state cannot be reconstructed; cancel it.",
                    sortedProjection, List.of(), run);
        }
        if (snap.suspended()) {
            return verdict(PROTECTED, "The engine instance is suspended.", sortedProjection, List.of(), run);
        }
        waitsOutsideEngine |= jdbc.sql("""
                SELECT (SELECT count(*) FROM workflow_run WHERE workspace_id=:ws AND parent_run_id=:id
                           AND lifecycle IN ('running','waiting','needsAttention'))
                     + (SELECT count(*) FROM external_job WHERE workspace_id=:ws AND run_id=:id
                           AND status IN ('available','locked'))
                """).param("ws", run.workspaceId()).param("id", run.id()).query(Long.class).single() > 0;
        List<String> engineActivities = snap.activeActivityIds();
        if (!snap.hasDrivingWork() && !waitsOutsideEngine) {
            if (engineActivities.isEmpty()) {
                return verdict(EMPTY_REQUEST,
                        "The engine holds no work for this request. The run watchdog reports it; cancel it after inspection.",
                        sortedProjection, engineActivities, run);
            }
            return verdict(STRANDED_TOKEN,
                    "The engine has a token at " + String.join(", ", engineActivities)
                            + " but no task, job, timer, message wait or child request can move it. Restarting or skipping the step could repeat or invent an outcome, so it is not repaired automatically; cancel the request.",
                    sortedProjection, engineActivities, run);
        }
        List<String> others = new ArrayList<>(engineActivities);
        others.removeAll(snap.taskIdsByActivity().keySet());
        if (others.isEmpty() && !snap.taskIdsByActivity().isEmpty() && approvalsOnly && !waitsOutsideEngine) {
            List<String> engineNodes = List.copyOf(snap.taskIdsByActivity().keySet());
            Map<String, String> projectedTasks = new TreeMap<>();
            for (JsonNode step : run.currentSteps()) {
                projectedTasks.put(step.path("nodeId").asString(""), step.path("taskId").asString(""));
            }
            if (projectedTasks.equals(snap.taskIdsByActivity())) {
                return verdict(IN_SYNC, "The projection matches the engine's open approvals.", sortedProjection,
                        engineNodes, run);
            }
            if (!RunStore.RUNNING.equals(run.lifecycle()) && !RunStore.WAITING.equals(run.lifecycle())) {
                return verdict(UNSUPPORTED, "The request needs attention for another reason; resolve that first.",
                        sortedProjection, engineNodes, run);
            }
            List<Rebuild> rebuild = new ArrayList<>();
            for (var entry : snap.taskIdsByActivity().entrySet()) {
                var row = jdbc.sql("""
                        SELECT label, candidate_group_key, assignee_subject_id, due_at, created_at FROM task_context
                        WHERE workspace_id=:ws AND run_id=:run AND engine_task_id=:task AND node_id=:node AND status='open'
                        """).param("ws", run.workspaceId()).param("run", run.id()).param("task", entry.getValue())
                        .param("node", entry.getKey())
                        .query((rs, n) -> new Rebuild(entry.getKey(), entry.getValue(), rs.getString("label"),
                                rs.getString("candidate_group_key"),
                                rs.getObject("assignee_subject_id") == null ? null
                                        : Sql.uuid(rs, "assignee_subject_id").toString(),
                                rs.getTimestamp("due_at") == null ? null : Sql.instant(rs, "due_at").toString(),
                                Sql.instant(rs, "created_at").toString())).optional();
                if (row.isEmpty()) {
                    return verdict(UNSUPPORTED,
                            "An engine approval has no open Orqix task row, so the step cannot be reconstructed safely.",
                            sortedProjection, engineNodes, run);
                }
                rebuild.add(row.get());
            }
            return new Verdict(new RuntimeDtos.RunDiagnosis(PROJECTION_DRIFT, true,
                    "The projection does not show the open approvals the engine holds. They can be rebuilt without touching the engine.",
                    sortedProjection, engineNodes, run.version()), rebuild);
        }
        if (sortedProjection.containsAll(engineActivities)) {
            return verdict(IN_SYNC, "Every active engine step is shown in the projection.", sortedProjection,
                    engineActivities, run);
        }
        return verdict(UNSUPPORTED,
                "The projection and engine differ in steps other than approvals; this is not repaired automatically.",
                sortedProjection, engineActivities, run);
    }

    /** Replaces the projected steps with the rebuilt approvals; the engine is not touched. */
    public void rebuild(RunStore.Mutation m, List<Rebuild> rebuild) {
        m.steps.removeAll();
        for (Rebuild r : rebuild) {
            m.addStep(step(r));
        }
        m.settle();
    }

    private ObjectNode step(Rebuild r) {
        ObjectNode step = json.object();
        step.put("nodeId", r.nodeId());
        step.put("label", r.label());
        step.put("kind", "approval");
        step.put("since", r.since());
        step.put("candidateGroup", r.group());
        if (r.assignee() == null) {
            step.putNull("assigneeId");
        } else {
            step.put("assigneeId", r.assignee());
        }
        if (r.dueAt() == null) {
            step.putNull("dueAt");
        } else {
            step.put("dueAt", r.dueAt());
        }
        step.put("taskId", r.taskId());
        return step;
    }

    private Verdict verdict(String state, String message, List<String> projected, List<String> engineNodes,
            RunStore.RunRow run) {
        return new Verdict(new RuntimeDtos.RunDiagnosis(state, false, message, projected, engineNodes, run.version()),
                List.of());
    }
}
