package com.orqix.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.orqix.catalog.internal.WorkflowService;
import com.orqix.catalog.internal.ReleaseService;
import com.orqix.identity.Caller;
import tools.jackson.databind.JsonNode;

/** Authorized catalog views for guided execution clients. */
@Service
public class StartableWorkflows {
    public record Choice(UUID id, String key, String name) {}
    public record Form(String workflowName, UUID releaseId, JsonNode inputSchema, JsonNode inputUi) {}
    private final WorkflowService workflows;
    private final ReleaseService releases;
    public StartableWorkflows(WorkflowService workflows, ReleaseService releases) {
        this.workflows = workflows; this.releases = releases;
    }
    public List<Choice> find(Caller caller, UUID workspace, String query) {
        return workflows.listWorkflows(caller, workspace, query, true, null, 100).items().stream()
                .map(w -> new Choice(w.id(), w.key(), w.name())).toList();
    }
    public Form form(Caller caller, UUID workspace, UUID workflow) {
        var f = releases.startForm(caller, workspace, workflow);
        return new Form(f.workflowName(), f.releaseId(), f.inputSchema(), f.inputUi());
    }
}
