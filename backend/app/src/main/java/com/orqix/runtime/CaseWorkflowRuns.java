package com.orqix.runtime;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.FormValidator;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.runtime.internal.ChildRuns;
import com.orqix.runtime.internal.RunEvents;
import com.orqix.runtime.internal.RunStore;
import com.orqix.runtime.internal.TaskStore;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Runs that a case task starts (WM04, S110). The release is the one pinned when the case definition was published; the
 * run belongs to the case starter, uses the workflow's own request form validation and open-request limit, and is
 * cancelled when the case no longer needs it. Callers hold the case lock, so a task never starts two runs.
 */
@Service
public class CaseWorkflowRuns {
    /** A run a case task started. */
    public record Launched(UUID runId, String reference) {}

    /** State of a case task's run: active, or ended with its business outcome and output. */
    public record State(UUID runId, String reference, String lifecycle, boolean active, String outcome, String outcomeKind,
            JsonNode output) {}

    /** The workflow could not start: the mapped input or the open-request limit refused it. */
    public static final class NotStarted extends RuntimeException {
        public NotStarted(String message) { super(message); }
    }

    private final RunStore runs;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final FormValidator forms;
    private final TaskStore tasks;
    private final ChildRuns children;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public CaseWorkflowRuns(RunStore runs, RunEvents events, ReleaseQueries releases, EngineCommands engine,
            FormValidator forms, TaskStore tasks, ChildRuns children, AuditWriter audit, JdbcClient jdbc, Json json,
            Clock clock) {
        this.runs = runs;
        this.events = events;
        this.releases = releases;
        this.engine = engine;
        this.forms = forms;
        this.tasks = tasks;
        this.children = children;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** Input field names, required input fields and output field names of a release, for mapping checks. */
    public record Fields(List<String> inputs, List<String> required, List<String> outputs, Map<String, String> inputTypes,
            Map<String, String> outputTypes) {}

    @Transactional(readOnly = true)
    public Fields fields(UUID workspaceId, UUID releaseId) {
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, releaseId);
        JsonNode input = release.inputSchema();
        JsonNode output = release.definition().path("outputSchema");
        var inputs = new java.util.ArrayList<String>();
        var types = new java.util.LinkedHashMap<String, String>();
        input.path("properties").properties().forEach(e -> {
            inputs.add(e.getKey());
            types.put(e.getKey(), e.getValue().path("type").asString("string"));
        });
        var required = new java.util.ArrayList<String>();
        input.path("required").forEach(n -> required.add(n.asString()));
        var outputs = new java.util.ArrayList<String>();
        var outputTypes = new java.util.LinkedHashMap<String, String>();
        output.path("properties").properties().forEach(e -> {
            outputs.add(e.getKey());
            outputTypes.put(e.getKey(), e.getValue().path("type").asString("string"));
        });
        return new Fields(List.copyOf(inputs), List.copyOf(required), List.copyOf(outputs), Map.copyOf(types),
                Map.copyOf(outputTypes));
    }

    /** A refused start changes nothing, so it does not mark the caller's transaction for rollback. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = NotStarted.class)
    public Launched start(UUID workspaceId, UUID releaseId, UUID initiator, JsonNode input, UUID caseId, String planItemId) {
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, releaseId);
        var errors = forms.validate(json.write(release.inputSchema()), json.write(input), json.write(release.inputUi()));
        if (!errors.isEmpty()) {
            throw new NotStarted("The case data mapped to \"" + release.workflowName() + "\" does not satisfy its request form: "
                    + errors.stream().map(e -> e.pointer() + " " + e.message()).limit(3).toList());
        }
        if (runs.countActive(workspaceId, release.workflowId()) >= release.maxConcurrentRuns()) {
            throw new NotStarted("\"" + release.workflowName() + "\" has reached its open-request limit.");
        }
        UUID id = Ids.newId();
        runs.insert(id, workspaceId, release.workflowId(), release.id(), "case:" + caseId, initiator, input);
        events.append(workspaceId, id, "RUN_STARTED", null, initiator, Map.of("releaseNumber", release.releaseNumber(),
                "caseId", caseId.toString(), "casePlanItemId", planItemId));
        String tenant = jdbc.sql("SELECT engine_tenant_id FROM workspace WHERE id = :ws")
                .param("ws", workspaceId).query(String.class).single();
        String pid = engine.startProcess(release.processDefinitionId(), tenant, id.toString(), Map.of(
                ProcessData.RUN_ID, id.toString(), ProcessData.WORKSPACE_ID, workspaceId.toString(),
                ProcessData.INITIATOR, initiator.toString(), ProcessData.INPUT, json.write(input)), initiator);
        runs.setEngineProcessInstance(workspaceId, id, pid);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.SYSTEM, null, "run.started", "run",
                id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("workflowKey", release.workflowKey(),
                        "releaseNumber", release.releaseNumber(), "caseId", caseId.toString())));
        return new Launched(id, runs.find(workspaceId, id).orElseThrow().reference());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<State> state(UUID workspaceId, UUID runId) {
        return runs.find(workspaceId, runId).map(r -> new State(r.id(), r.reference(), r.lifecycle(), r.isActive(),
                r.outcome(), r.outcomeKind(), r.output()));
    }

    /** Cancels the run and anything it started; a run that already ended is left as it is. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(UUID workspaceId, UUID runId, String reason) {
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElse(null);
        if (run == null || !run.isActive()) return;
        if (run.engineProcessInstanceId() != null && engine.processExists(run.engineProcessInstanceId())) {
            engine.deleteProcessInstance(run.engineProcessInstanceId(), reason, null);
        }
        var now = clock.instant();
        tasks.cancelOpenForRun(workspaceId, runId, now);
        runs.mutate(workspaceId, runId, m -> {
            m.lifecycle = RunStore.CANCELLED;
            m.attention = null;
            m.endedAt = now;
            m.steps.removeAll();
        });
        events.append(workspaceId, runId, "RUN_CANCELLED", null, null, Map.of("reason", reason));
        children.cancelActiveChildren(run, "The request that started this one has ended.");
    }
}
