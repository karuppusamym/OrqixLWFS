package com.orqix.runtime.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;

/**
 * "Where is my request": the release graph, the steps passed and the current steps (S5 section 8). Access is the rule
 * of the run events (run.read, initiator or task participant); the graph carries labels, kinds, routes and layout only.
 */
@Service
public class RunPaths {

    /** Events read for a path; longer histories are explicitly flagged and shown as current steps only. */
    static final int MAX_EVENTS = 5000;

    private final RunAccess access;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final RuntimeViews views;

    public RunPaths(RunAccess access, RunEvents events, ReleaseQueries releases, RuntimeViews views) {
        this.access = access;
        this.events = events;
        this.releases = releases;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public RunPathBuilder.Result path(Caller caller, UUID workspaceId, UUID runId) {
        RunStore.RunRow run = access.require(caller, workspaceId, runId).run();
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, run.releaseId());
        Map<UUID, String> names = views.names(List.of(run), List.of(), List.of());
        String attentionNode = run.attention() == null ? null : run.attention().path("nodeId").asString(null);
        List<RunEvents.EventRow> history = events.list(workspaceId, runId, 0, MAX_EVENTS + 1);
        boolean truncated = history.size() > MAX_EVENTS;
        RunPathBuilder.Result result = RunPathBuilder.build(run.id(), run.lifecycle(), release.definition(), release.sourceMap(),
                truncated ? history.subList(0, MAX_EVENTS) : history, views.steps(run, names), attentionNode);
        return new RunPathBuilder.Result(result.runId(), result.lifecycle(), result.diagram(),
                truncated ? List.of() : result.visited(), result.current(),
                truncated ? List.of() : result.taken(), truncated);
    }
}
