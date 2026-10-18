package com.orqix.runtime.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * EX09: read-only comparison of the Orqix projection with what the engine holds, and the repair that is provably
 * safe: rebuilding the open steps from what the engine itself holds. Approvals come from live engine tasks, date and
 * message waits from the engine's timer and subscription, external-worker steps from the engine's waiting service
 * task. Nothing here triggers, skips or completes an engine activity, so it cannot run a step twice or invent a
 * business outcome. A token that nothing can move (strandedToken) is only reported; the operator may cancel the
 * request. When the engine state does not fully determine the steps the check refuses and says why.
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

    private static final Set<String> REBUILDABLE_KINDS = Set.of("approval", "wait", "external");

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

    /**
     * One step to put back. {@code kind} is approval (with the facts of its open Orqix task row), wait (a date wait,
     * or a message wait when {@code message} is set) or external (a worker step, with its topic).
     */
    public record Rebuild(String kind, String nodeId, String taskId, String label, String group, String assignee,
            String dueAt, String since, String message, String topic) {
    }

    public record Verdict(RuntimeDtos.RunDiagnosis diagnosis, List<Rebuild> rebuild) {
    }

    /** Either the complete set of steps the engine determines, or the reason it does not. */
    private record Plan(List<Rebuild> steps, String undetermined) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Verdict diagnose(RunStore.RunRow run) {
        List<String> projected = new ArrayList<>();
        boolean supportedKinds = true;
        boolean waitsOutsideEngine = false;
        for (JsonNode step : run.currentSteps()) {
            projected.add(step.path("nodeId").asString(""));
            String kind = step.path("kind").asString("");
            supportedKinds &= REBUILDABLE_KINDS.contains(kind);
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
        boolean childRuns = jdbc.sql("""
                SELECT count(*) FROM workflow_run WHERE workspace_id=:ws AND parent_run_id=:id
                   AND lifecycle IN ('running','waiting','needsAttention')
                """).param("ws", run.workspaceId()).param("id", run.id()).query(Long.class).single() > 0;
        boolean openJobs = jdbc.sql("""
                SELECT count(*) FROM external_job WHERE workspace_id=:ws AND run_id=:id
                   AND status IN ('available','locked')
                """).param("ws", run.workspaceId()).param("id", run.id()).query(Long.class).single() > 0;
        waitsOutsideEngine |= childRuns || openJobs;
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
        String undetermined = null;
        if (supportedKinds && !childRuns) {
            Plan plan = plan(run, snap, engineActivities);
            if (plan.undetermined() == null) {
                Map<String, String> expected = new TreeMap<>();
                for (Rebuild r : plan.steps()) {
                    expected.put(r.nodeId(), r.kind() + ":" + (r.taskId() == null ? "" : r.taskId()));
                }
                Map<String, String> shown = new TreeMap<>();
                for (JsonNode step : run.currentSteps()) {
                    shown.put(step.path("nodeId").asString(""),
                            step.path("kind").asString("") + ":" + step.path("taskId").asString(""));
                }
                List<String> engineNodes = List.copyOf(expected.keySet());
                if (shown.equals(expected)) {
                    return verdict(IN_SYNC, "The projection matches the steps the engine holds.", sortedProjection,
                            engineNodes, run);
                }
                if (!RunStore.RUNNING.equals(run.lifecycle()) && !RunStore.WAITING.equals(run.lifecycle())) {
                    return verdict(UNSUPPORTED, "The request needs attention for another reason; resolve that first.",
                            sortedProjection, engineNodes, run);
                }
                return new Verdict(new RuntimeDtos.RunDiagnosis(PROJECTION_DRIFT, true,
                        "The projection does not show the steps the engine holds. They can be rebuilt without touching the engine.",
                        sortedProjection, engineNodes, run.version()), plan.steps());
            }
            undetermined = plan.undetermined();
        }
        if (undetermined == null && sortedProjection.containsAll(engineActivities)) {
            return verdict(IN_SYNC, "Every active engine step is shown in the projection.", sortedProjection,
                    engineActivities, run);
        }
        return verdict(UNSUPPORTED,
                undetermined == null
                        ? "The projection and engine differ in steps this check cannot rebuild; it is not repaired automatically."
                        : "The engine state does not fully determine the steps, so nothing is rebuilt: " + undetermined,
                sortedProjection, engineActivities, run);
    }

    /**
     * The steps the engine fully determines, or why it does not. Approvals need their open Orqix task row, a message
     * wait its open subscription for the same engine execution, an external step its open job with the same topic.
     * Anything the engine holds that is not accounted for (other timers, signals, jobs in flight, failed or suspended
     * jobs) means the state is not determined.
     */
    private Plan plan(RunStore.RunRow run, EngineCommands.Snapshot snap, List<String> engineActivities) {
        var facts = engine.waitFacts(run.engineProcessInstanceId(), snap.taskIdsByActivity().keySet());
        if (!facts.unexplained().isEmpty()) {
            return undetermined("the engine holds " + String.join(", ", facts.unexplained()) + ".");
        }
        if (facts.executableJobs() > 0 || snap.deadLetterJobs() > 0 || snap.suspendedJobs() > 0) {
            return undetermined(
                    "the engine has jobs running, failed or suspended; check again once they settle or are resolved.");
        }
        List<String> others = new ArrayList<>(engineActivities);
        others.removeAll(snap.taskIdsByActivity().keySet());
        others.removeAll(facts.explainedActivities());
        if (!others.isEmpty()) {
            return undetermined("the engine is at " + String.join(", ", others) + ", which this check cannot interpret.");
        }
        List<Rebuild> steps = new ArrayList<>();
        for (var entry : snap.taskIdsByActivity().entrySet()) {
            var row = jdbc.sql("""
                    SELECT label, candidate_group_key, assignee_subject_id, due_at, created_at FROM task_context
                    WHERE workspace_id=:ws AND run_id=:run AND engine_task_id=:task AND node_id=:node AND status='open'
                    """).param("ws", run.workspaceId()).param("run", run.id()).param("task", entry.getValue())
                    .param("node", entry.getKey())
                    .query((rs, n) -> new Rebuild("approval", entry.getKey(), entry.getValue(), rs.getString("label"),
                            rs.getString("candidate_group_key"),
                            rs.getObject("assignee_subject_id") == null ? null
                                    : Sql.uuid(rs, "assignee_subject_id").toString(),
                            rs.getTimestamp("due_at") == null ? null : Sql.instant(rs, "due_at").toString(),
                            Sql.instant(rs, "created_at").toString(), null, null)).optional();
            if (row.isEmpty()) {
                return undetermined(
                        "an engine approval has no open Orqix task row, so the step cannot be reconstructed safely.");
            }
            steps.add(row.get());
        }
        List<String> workerNodes = new ArrayList<>();
        for (var wait : facts.steps()) {
            String due = wait.dueAt() == null ? null : wait.dueAt().toString();
            if ("external".equals(wait.kind())) {
                var job = jdbc.sql("""
                        SELECT created_at FROM external_job WHERE workspace_id=:ws AND run_id=:run AND node_id=:node
                           AND topic=:topic AND status IN ('available','locked')
                        """).param("ws", run.workspaceId()).param("run", run.id()).param("node", wait.nodeId())
                        .param("topic", wait.topic()).query((rs, n) -> Sql.instant(rs, "created_at").toString())
                        .optional();
                if (job.isEmpty()) {
                    return undetermined("the worker step " + wait.nodeId() + " has no open job for topic "
                            + wait.topic() + ".");
                }
                workerNodes.add(wait.nodeId());
                steps.add(new Rebuild("external", wait.nodeId(), null, wait.label(), null, null, null, job.get(), null,
                        wait.topic()));
            } else if (wait.message() == null) {
                if (wait.since() == null) {
                    return undetermined("the engine timer at " + wait.nodeId() + " has no creation time.");
                }
                steps.add(new Rebuild("wait", wait.nodeId(), null, wait.label(), null, null, due,
                        wait.since().toString(), null, null));
            } else {
                var sub = jdbc.sql("""
                        SELECT created_at FROM message_subscription WHERE workspace_id=:ws AND run_id=:run
                           AND node_id=:node AND status='waiting' AND engine_execution_id=:exec AND message_name=:name
                        """).param("ws", run.workspaceId()).param("run", run.id()).param("node", wait.nodeId())
                        .param("exec", wait.executionId()).param("name", wait.message())
                        .query((rs, n) -> Sql.instant(rs, "created_at").toString()).optional();
                if (sub.isEmpty()) {
                    return undetermined("the message wait at " + wait.nodeId()
                            + " has no open subscription for the engine's waiting execution.");
                }
                steps.add(new Rebuild("wait", wait.nodeId(), null, wait.label(), null, null, due, sub.get(),
                        wait.message(), null));
            }
        }
        long strayJobs = jdbc.sql("""
                SELECT count(*) FROM external_job WHERE workspace_id=:ws AND run_id=:run
                   AND status IN ('available','locked') AND NOT (node_id = ANY (CAST(:nodes AS text[])))
                """).param("ws", run.workspaceId()).param("run", run.id())
                .param("nodes", workerNodes.toArray(String[]::new)).query(Long.class).single();
        if (strayJobs > 0) {
            return undetermined("an external job is open for a step where the engine is not waiting.");
        }
        if (steps.isEmpty()) {
            return undetermined("the engine holds no step this check can interpret.");
        }
        steps.sort(Comparator.comparing(Rebuild::nodeId));
        return new Plan(steps, null);
    }

    private static Plan undetermined(String reason) {
        return new Plan(List.of(), reason);
    }

    /** Replaces the projected steps with the rebuilt ones; the engine is not touched. */
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
        step.put("kind", r.kind());
        step.put("since", r.since());
        if (r.group() == null) {
            step.putNull("candidateGroup");
        } else {
            step.put("candidateGroup", r.group());
        }
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
        if (r.message() != null) {
            step.put("message", r.message());
        }
        if (r.topic() != null) {
            step.put("topic", r.topic());
        }
        if (r.taskId() != null) {
            step.put("taskId", r.taskId());
        }
        return step;
    }

    private Verdict verdict(String state, String message, List<String> projected, List<String> engineNodes,
            RunStore.RunRow run) {
        return new Verdict(new RuntimeDtos.RunDiagnosis(state, false, message, projected, engineNodes, run.version()),
                List.of());
    }
}
