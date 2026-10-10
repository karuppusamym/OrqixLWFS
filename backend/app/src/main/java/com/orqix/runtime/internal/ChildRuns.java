package com.orqix.runtime.internal;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.FormValidator;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Child starts, completions and cancellation share the engine transaction and the run projection. */
@Service
public class ChildRuns {
    private final RunStore runs;
    private final TaskStore tasks;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final FormValidator forms;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public ChildRuns(RunStore runs, TaskStore tasks, RunEvents events, ReleaseQueries releases,
            EngineCommands engine, FormValidator forms, JdbcClient jdbc, Json json, Clock clock) {
        this.runs = runs;
        this.tasks = tasks;
        this.events = events;
        this.releases = releases;
        this.engine = engine;
        this.forms = forms;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void start(UUID workspaceId, UUID parentId, String nodeId, JsonNode input) {
        RunStore.RunRow parent = runs.lock(workspaceId, parentId).orElseThrow();
        start(parent, nodeId, input, 1);
    }

    private void start(RunStore.RunRow parent, String nodeId, JsonNode input, int occurrence) {
        UUID workspaceId = parent.workspaceId();
        UUID parentId = parent.id();
        if (!parent.isActive()) throw new IllegalStateException("Parent run has ended");
        if (parent.callDepth() >= 3) throw new IllegalStateException("Child workflow depth limit exceeded");
        if (runs.findChild(workspaceId, parentId, nodeId, occurrence).isPresent()) return;
        ReleaseQueries.ReleaseView parentRelease = releases.require(workspaceId, parent.releaseId());
        JsonNode pin = parentRelease.childPin(nodeId);
        JsonNode each = parentRelease.node(nodeId).path("config").path("forEach");
        if (!each.isMissingNode()) {
            JsonNode items = parent.input().at(each.path("itemsRef").asString().substring("/input".length()));
            if (!items.isArray() || items.isEmpty() || items.size() > each.path("maxItems").asInt())
                throw new IllegalStateException("Collection is outside its declared iteration bound");
            ObjectNode mapped = (ObjectNode) input.deepCopy();
            mapped.set(each.path("itemInput").asString(), items.get(occurrence - 1));
            input = mapped;
        }
        if (pin == null) throw new IllegalStateException("Child release was not pinned at publication");
        ReleaseQueries.ReleaseView child = releases.require(workspaceId, UUID.fromString(pin.path("releaseId").asString()));
        if (!forms.validate(json.write(child.inputSchema()), json.write(input)).isEmpty()) {
            throw new IllegalStateException("Mapped child input does not satisfy the published request form");
        }
        if (runs.countActive(workspaceId, child.workflowId()) >= child.maxConcurrentRuns()) {
            throw new IllegalStateException("Child workflow has reached its concurrent run limit");
        }
        UUID id = Ids.newId();
        runs.insertChild(id, workspaceId, child.workflowId(), child.id(), null, parent.initiator(), input,
                parent, nodeId, occurrence);
        runs.mutate(workspaceId, parentId, m -> {
            ObjectNode step = json.object();
            step.put("nodeId", nodeId).put("label", parentRelease.node(nodeId).path("label").asString())
                    .put("kind", "child").put("since", clock.instant().toString());
            step.putNull("candidateGroup").putNull("assigneeId").putNull("dueAt");
            m.addStep(step);
            m.settle();
        });
        events.append(workspaceId, parentId, "CHILD_STARTED", nodeId, null,
                Map.of("childRunId", id.toString(), "iteration", occurrence,
                        "childReference", runs.find(workspaceId, id).orElseThrow().reference(),
                        "workflowName", child.workflowName(), "releaseNumber", child.releaseNumber()));
        events.append(workspaceId, id, "RUN_STARTED", null, parent.initiator(),
                Map.of("releaseNumber", child.releaseNumber(), "parentRunId", parentId.toString()));
        String tenant = jdbc.sql("SELECT engine_tenant_id FROM workspace WHERE id = :ws")
                .param("ws", workspaceId).query(String.class).single();
        String pid = engine.startProcess(child.processDefinitionId(), tenant, id.toString(), Map.of(
                ProcessData.RUN_ID, id.toString(), ProcessData.WORKSPACE_ID, workspaceId.toString(),
                ProcessData.INITIATOR, parent.initiator().toString(), ProcessData.INPUT, json.write(input)), parent.initiator());
        runs.setEngineProcessInstance(workspaceId, id, pid);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onRunEnded(RunStore.RunRow child) {
        if (!child.isChild()) return;
        RunStore.RunRow parent = runs.lock(child.workspaceId(), child.parentRunId()).orElseThrow();
        if (!parent.isActive()) return;
        JsonNode node = releases.require(parent.workspaceId(), parent.releaseId()).node(child.parentNodeId());
        JsonNode repeat = node.path("config").path("repeat");
        int iteration = child.parentOccurrence();
        boolean matched = !repeat.isMissingNode() && repeat.path("untilOutcome").asString().equals(child.outcome());
        boolean exhausted = !repeat.isMissingNode() && !matched && iteration >= repeat.path("maxIterations").asInt();
        JsonNode each = node.path("config").path("forEach");
        boolean moreItems = !each.isMissingNode() && iteration < parent.input().at(each.path("itemsRef").asString().substring("/input".length())).size();
        events.append(parent.workspaceId(), parent.id(), "CHILD_ENDED", child.parentNodeId(), null,
                Map.of("childRunId", child.id().toString(), "childReference", child.reference(),
                        "workflowName", releases.require(child.workspaceId(), child.releaseId()).workflowName(),
                        "outcome", child.outcome() == null ? "Cancelled" : child.outcome(),
                        "outcomeKind", child.outcomeKind() == null ? "cancelled" : child.outcomeKind(),
                        "iteration", iteration));
        if (((!repeat.isMissingNode() && !matched && !exhausted) || moreItems) && !RunStore.CANCELLED.equals(child.lifecycle())) {
            start(parent, child.parentNodeId(), child.input(), iteration + 1);
            return;
        }
        ObjectNode result = json.object();
        result.put("iterations", iteration).put("limitReached", exhausted);
        boolean allSucceeded = true;
        for (int i = 1; i <= iteration; i++) {
            var previous = runs.findChild(parent.workspaceId(), parent.id(), child.parentNodeId(), i).orElseThrow();
            if (!"success".equals(previous.outcomeKind())) allSucceeded = false;
        }
        result.put("allSucceeded", allSucceeded);
        result.put("outcome", child.outcome() == null ? "Cancelled" : child.outcome());
        result.put("outcomeKind", child.outcomeKind() == null ? "cancelled" : child.outcomeKind());
        result.put("reference", child.reference());
        node.path("config").path("resultMapping").properties().forEach(e -> {
            JsonNode value = child.output() == null ? null : child.output().get(e.getValue().asString());
            if (value != null) result.set(e.getKey(), value);
        });
        engine.resumeChild(parent.engineProcessInstanceId(), child.parentNodeId(),
                Map.of(ProcessData.stepVariable(child.parentNodeId()), json.write(result)));
        runs.mutate(parent.workspaceId(), parent.id(), m -> {
            m.removeStep(child.parentNodeId());
            m.settle();
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelActiveChildren(RunStore.RunRow parent, String reason) {
        for (RunStore.RunRow descendant : runs.descendantsDeepestFirst(parent.workspaceId(), parent.id())) {
            RunStore.RunRow child = runs.lock(parent.workspaceId(), descendant.id()).orElseThrow();
            if (!child.isActive()) continue;
            engine.deleteProcessInstance(child.engineProcessInstanceId(), reason, null);
            tasks.cancelOpenForRun(child.workspaceId(), child.id(), clock.instant());
            runs.mutate(child.workspaceId(), child.id(), m -> {
                m.lifecycle = RunStore.CANCELLED;
                m.attention = null;
                m.endedAt = clock.instant();
                m.steps.removeAll();
            });
            events.append(child.workspaceId(), child.id(), "RUN_CANCELLED", null, null, Map.of("reason", reason));
        }
    }
}
