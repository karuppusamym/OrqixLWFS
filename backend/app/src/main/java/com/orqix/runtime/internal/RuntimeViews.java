package com.orqix.runtime.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.GroupDirectory;
import com.orqix.identity.SubjectService;
import com.orqix.identity.SubjectService.SubjectRef;
import com.orqix.integration.EffectJournal;
import com.orqix.runtime.api.RuntimeDtos;

import tools.jackson.databind.JsonNode;

/** Maps projections to API representations (S2 section 7, contracts/openapi.yaml). */
@Component
public class RuntimeViews {

    private final SubjectService subjects;
    private final ReleaseQueries releases;
    private final GroupDirectory groups;
    private final TaskQueries taskQueries;
    private final RunStore runs;
    private final RunStages stages;

    public RuntimeViews(SubjectService subjects, ReleaseQueries releases, GroupDirectory groups, TaskQueries taskQueries,
            RunStore runs, RunStages stages) {
        this.runs = runs;
        this.stages = stages;
        this.subjects = subjects;
        this.releases = releases;
        this.groups = groups;
        this.taskQueries = taskQueries;
    }

    /** Subject names for a batch of runs/tasks (one query). */
    public Map<UUID, String> names(List<RunStore.RunRow> runs, List<TaskStore.TaskRow> tasks,
            List<TaskQueries.DecisionRow> decisions) {
        Set<UUID> ids = new HashSet<>();
        runs.forEach(r -> {
            ids.add(r.initiator());
            r.currentSteps().forEach(s -> {
                String assignee = s.path("assigneeId").asString(null);
                if (assignee != null) {
                    ids.add(UUID.fromString(assignee));
                }
            });
        });
        tasks.forEach(t -> {
            ids.add(t.initiator());
            if (t.assignee() != null) {
                ids.add(t.assignee());
            }
        });
        decisions.forEach(d -> ids.add(d.actor()));
        return subjects.displayNames(ids);
    }

    public Map<UUID, String> namesOf(java.util.Collection<UUID> ids) {
        return subjects.displayNames(ids);
    }

    /**
     * {@code titleVisible} is false for a caller who may not read the request data: the title is a field of that data,
     * so it is withheld together with {@code input}.
     */
    public RuntimeDtos.RunSummary run(RunStore.RunRow run, Map<UUID, String> names, boolean isTest,
            boolean titleVisible) {
        ReleaseQueries.ReleaseView release = releases.require(run.workspaceId(), run.releaseId());
        return new RuntimeDtos.RunSummary(run.id(), run.reference(),
                new RuntimeDtos.WorkflowRef(run.workflowId(), release.workflowKey(), release.workflowName()),
                new RuntimeDtos.ReleaseRef(release.id(), release.releaseNumber()), run.businessKey(),
                titleVisible ? requestTitle(run.input(), release) : null,
                subjects.ref(run.initiator(), names), run.lifecycle(), run.outcome(), run.outcomeKind(),
                steps(run, names), attention(run.attention()), run.createdAt(), run.updatedAt(), run.endedAt(), isTest,
                run.version(), parentRef(run), stages.current(run, release));
    }

    /** Link from a child run to the run that started it; null for ordinary runs. */
    public RuntimeDtos.ParentRef parentRef(RunStore.RunRow run) {
        if (!run.isChild()) {
            return null;
        }
        RunStore.RunRow parent = runs.find(run.workspaceId(), run.parentRunId()).orElse(null);
        if (parent == null) {
            return null;
        }
        ReleaseQueries.ReleaseView release = releases.require(parent.workspaceId(), parent.releaseId());
        return new RuntimeDtos.ParentRef(parent.id(), parent.reference(), run.parentNodeId(),
                release.nodeLabel(run.parentNodeId()));
    }

    /** Runs started by steps of this run, oldest first. */
    public java.util.List<RuntimeDtos.ChildRun> children(RunStore.RunRow run) {
        java.util.List<RuntimeDtos.ChildRun> result = new ArrayList<>();
        ReleaseQueries.ReleaseView parentRelease = releases.require(run.workspaceId(), run.releaseId());
        for (RunStore.RunRow child : runs.children(run.workspaceId(), run.id())) {
            ReleaseQueries.ReleaseView release = releases.require(child.workspaceId(), child.releaseId());
            result.add(new RuntimeDtos.ChildRun(child.id(), child.reference(),
                    new RuntimeDtos.WorkflowRef(child.workflowId(), release.workflowKey(), release.workflowName()),
                    new RuntimeDtos.ReleaseRef(release.id(), release.releaseNumber()), child.parentNodeId(),
                    parentRelease.nodeLabel(child.parentNodeId()), child.lifecycle(), child.outcome(),
                    child.outcomeKind(), attention(child.attention()), child.createdAt(), child.endedAt(),
                    child.parentOccurrence() == null ? 1 : child.parentOccurrence()));
        }
        return result;
    }

    public List<RuntimeDtos.CurrentStep> steps(RunStore.RunRow run, Map<UUID, String> names) {
        List<RuntimeDtos.CurrentStep> steps = new ArrayList<>();
        for (JsonNode step : run.currentSteps()) {
            String assignee = step.path("assigneeId").asString(null);
            steps.add(new RuntimeDtos.CurrentStep(step.path("nodeId").asString(), step.path("label").asString(),
                    step.path("kind").asString(), instant(step.path("since")), step.path("candidateGroup").asString(null),
                    assignee == null ? null : subjects.ref(UUID.fromString(assignee), names), instant(step.path("dueAt")),
                    step.path("message").asString(null)));
        }
        return steps;
    }

    public RuntimeDtos.Attention attention(JsonNode attention) {
        if (attention == null || attention.isNull() || attention.isMissingNode()) {
            return null;
        }
        String effectId = attention.path("effectId").asString(null);
        return new RuntimeDtos.Attention(attention.path("reason").asString(), attention.path("nodeId").asString(),
                effectId == null ? null : UUID.fromString(effectId), attention.path("message").asString(),
                instant(attention.path("since")));
    }

    public RuntimeDtos.TaskSummary task(TaskStore.TaskRow task, RunStore.RunRow run, Map<UUID, String> names,
            TaskQueries.DecisionRow decision) {
        return task(task, run, names, decision, true);
    }

    /**
     * {@code titleVisible} is false for a caller who may not read the request data: the title is the first text field
     * of that data (for example a customer name), so it is withheld together with {@code input}.
     */
    public RuntimeDtos.TaskSummary task(TaskStore.TaskRow task, RunStore.RunRow run, Map<UUID, String> names,
            TaskQueries.DecisionRow decision, boolean titleVisible) {
        ReleaseQueries.ReleaseView release = releases.require(task.workspaceId(), run.releaseId());
        return new RuntimeDtos.TaskSummary(task.taskId(), task.runId(), run.reference(), release.workflowName(),
                run.businessKey(),
                task.nodeId(), task.label(), titleVisible ? requestTitle(run.input(), release) : null,
                subjects.ref(task.initiator(), names),
                new RuntimeDtos.GroupRef(task.candidateGroupKey(), groupName(task.workspaceId(), task.candidateGroupKey())),
                task.assignee() == null ? null : subjects.ref(task.assignee(), names), task.status(), task.dueAt(),
                task.createdAt(), task.version(), task.priority(), decision == null ? null : decision(decision, task.label(), names),
                stages.ofStep(run, release, task.nodeId()));
    }

    public RuntimeDtos.TaskDecision decision(TaskQueries.DecisionRow decision, String label, Map<UUID, String> names) {
        List<String> flags = new ArrayList<>();
        decision.policyFlags().forEach(f -> flags.add(f.asString()));
        return new RuntimeDtos.TaskDecision(decision.id(), decision.taskId(), decision.runId(), decision.nodeId(), label,
                subjects.ref(decision.actor(), names), decision.action(), decision.reason(), decision.comment(),
                decision.decidedAt(), flags);
    }

    public RuntimeDtos.Effect effect(EffectJournal.Effect effect, String label) {
        return new RuntimeDtos.Effect(effect.id(), effect.nodeId(), label, effect.connectionKey(), effect.operation(),
                effect.effectClass(), effect.state(), effect.attempts(), effect.lastHttpStatus(), effect.lastErrorCode(),
                effect.lastErrorDetail(), effect.remoteReference(), effect.resolution(), effect.updatedAt());
    }

    /** Property names of the request form in display order: {@code inputUi.order} first, then the remaining schema properties. */
    static List<String> fieldOrder(ReleaseQueries.ReleaseView release) {
        List<String> order = new ArrayList<>();
        JsonNode properties = release.inputSchema().path("properties");
        release.inputUi().path("order").forEach(n -> {
            String name = n.asString();
            if (properties.has(name) && !order.contains(name)) {
                order.add(name);
            }
        });
        properties.propertyNames().forEach(name -> {
            if (!order.contains(name)) {
                order.add(name);
            }
        });
        return order;
    }

    /** The request title is cut to this many characters. */
    public static final int REQUEST_TITLE_MAX = 120;

    /** First non-blank free-text field (not a choice list or a date) of the request, in form order, trimmed and cut to 120 characters, for list display. */
    public static String requestTitle(JsonNode input, ReleaseQueries.ReleaseView release) {
        for (String field : fieldOrder(release)) {
            JsonNode schema = release.inputSchema().path("properties").path(field);
            boolean plainText = "string".equals(schema.path("type").asString(null)) && schema.path("enum").isMissingNode()
                    && schema.path("format").isMissingNode();
            if (plainText && input.path(field).isString()) {
                String value = input.path(field).asString().strip();
                if (!value.isEmpty()) {
                    return value.length() > REQUEST_TITLE_MAX ? value.substring(0, REQUEST_TITLE_MAX).stripTrailing()
                            : value;
                }
            }
        }
        return null;
    }

    /**
     * Field titles of the pinned form for display of request data, in form order. The stored request is a
     * {@code jsonb} document whose keys PostgreSQL reorders, so clients take the display order from this map.
     */
    public static Map<String, String> inputLabels(ReleaseQueries.ReleaseView release) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String field : fieldOrder(release)) {
            labels.put(field, release.inputSchema().path("properties").path(field).path("title").asString(field));
        }
        return labels;
    }

    public String groupName(UUID workspaceId, String key) {
        return groupNames(workspaceId).getOrDefault(key, key);
    }

    private Map<String, String> groupNames(UUID workspaceId) {
        Map<String, String> names = new HashMap<>();
        groups.groups(workspaceId).forEach(g -> names.put(g.key(), g.name()));
        return names;
    }

    public List<TaskQueries.DecisionRow> decisions(UUID workspaceId, UUID runId) {
        return taskQueries.decisionsForRun(workspaceId, runId);
    }

    public SubjectRef ref(UUID id, Map<UUID, String> names) {
        return subjects.ref(id, names);
    }

    private static Instant instant(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : Instant.parse(node.asString());
    }
}
