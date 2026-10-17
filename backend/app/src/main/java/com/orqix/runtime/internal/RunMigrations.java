package com.orqix.runtime.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.FormValidator;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Moving an in-flight request to another release of its workflow (docs/build/S38): which releases it could move to
 * and why not. The graph rules live in {@link ReleaseCompatibility}; this adds what needs the request itself.
 */
@Service
public class RunMigrations {

    /** Newest releases offered; a workflow with more history can still be moved by choosing a recent target. */
    static final int MAX_OPTIONS = 20;

    private final ReleaseQueries releases;
    private final FormValidator forms;
    private final Json json;

    public RunMigrations(ReleaseQueries releases, FormValidator forms, Json json) {
        this.releases = releases;
        this.forms = forms;
        this.json = json;
    }

    /** Every reason {@code run} cannot continue on {@code target}; empty means compatible. */
    public List<ReleaseCompatibility.Finding> evaluate(RunStore.RunRow run, ReleaseQueries.ReleaseView source,
            ReleaseQueries.ReleaseView target) {
        List<String> active = new ArrayList<>();
        for (JsonNode step : run.currentSteps()) {
            active.add(step.path("nodeId").asString(""));
        }
        List<ReleaseCompatibility.Finding> findings = new ArrayList<>(
                ReleaseCompatibility.check(source.definition(), target.definition(), active));
        var errors = forms.validate(json.write(target.inputSchema()), json.write(run.input()),
                json.write(target.inputUi()));
        if (!errors.isEmpty()) {
            findings.add(new ReleaseCompatibility.Finding("INPUT_INVALID",
                    "The request's answers do not satisfy the target release's form: "
                            + errors.stream().limit(3).map(e -> e.message()).toList(), null));
        }
        return findings;
    }

    public RuntimeDtos.MigrationOptions options(RunStore.RunRow run, boolean suspended) {
        ReleaseQueries.ReleaseView source = releases.require(run.workspaceId(), run.releaseId());
        UUID activeId = releases.workflow(run.workspaceId(), run.workflowId())
                .map(ReleaseQueries.WorkflowRef::activeReleaseId).orElse(null);
        List<RuntimeDtos.MigrationOption> options = new ArrayList<>();
        for (UUID id : releases.releaseIds(run.workspaceId(), run.workflowId(), MAX_OPTIONS + 1)) {
            if (id.equals(run.releaseId()) || options.size() >= MAX_OPTIONS) {
                continue;
            }
            ReleaseQueries.ReleaseView target = releases.require(run.workspaceId(), id);
            List<RuntimeDtos.MigrationFinding> findings = evaluate(run, source, target).stream()
                    .map(f -> new RuntimeDtos.MigrationFinding(f.code(), f.message(), f.nodeId())).toList();
            options.add(new RuntimeDtos.MigrationOption(new RuntimeDtos.ReleaseRef(target.id(), target.releaseNumber()),
                    id.equals(activeId), target.publishedAt(), findings.isEmpty(), findings));
        }
        String notReady = !run.isActive() ? "The request has ended."
                : !suspended ? "Suspend the request first. A request is moved only while nothing can change under it."
                : null;
        return new RuntimeDtos.MigrationOptions(new RuntimeDtos.ReleaseRef(source.id(), source.releaseNumber()),
                notReady == null, notReady, options);
    }
}
