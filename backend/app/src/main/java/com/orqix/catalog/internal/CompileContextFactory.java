package com.orqix.catalog.internal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.compiler.CompileContext;
import com.orqix.identity.GroupDirectory;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.AdapterSteps;
import com.orqix.integration.AgentWriteTools;
import com.orqix.integration.ConnectionService;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/** Builds a fresh {@link CompileContext} from current workspace facts (S4 sections 1 and 10). */
@Component
public class CompileContextFactory {

    private final GroupDirectory groups;
    private final ConnectionService connections;
    private final CatalogStore store;
    private final AdapterSteps adapters;
    private final AgentWriteTools writeTools;
    private final Json json;

    public CompileContextFactory(GroupDirectory groups, ConnectionService connections, CatalogStore store,
            AdapterSteps adapters, AgentWriteTools writeTools, Json json) {
        this.groups = groups;
        this.connections = connections;
        this.store = store;
        this.adapters = adapters;
        this.writeTools = writeTools;
        this.json = json;
    }

    /** A context for a workspace without child-workflow facts. */
    @Transactional(readOnly = true)
    public CompileContext create(WorkspaceAccess.Workspace workspace) {
        return create(workspace, Set.of());
    }

    /**
     * A context that also describes the workflows the definition starts ("Start another workflow", S12): the active
     * release of each, which a publish pins. Only the referenced workflows are loaded.
     *
     * @param definitionJson a draft; unreadable text just means no child facts
     */
    @Transactional(readOnly = true)
    public CompileContext create(WorkspaceAccess.Workspace workspace, String definitionJson) {
        return create(workspace, referencedWorkflows(definitionJson));
    }

    private Set<String> referencedWorkflows(String definitionJson) {
        Set<String> keys = new LinkedHashSet<>();
        try {
            for (JsonNode node : json.parse(definitionJson).path("nodes")) {
                if ("startWorkflow".equals(node.path("type").asString(null))
                        && node.path("config").path("workflowRef").isString()) {
                    keys.add(node.path("config").path("workflowRef").asString());
                }
            }
        } catch (RuntimeException e) {
            return Set.of();
        }
        return keys;
    }

    private CompileContext create(WorkspaceAccess.Workspace workspace, Set<String> workflowKeys) {
        Map<String, CompileContext.GroupInfo> groupInfos = new LinkedHashMap<>();
        for (GroupDirectory.Group group : groups.groups(workspace.id())) {
            groupInfos.put(group.key(), new CompileContext.GroupInfo(group.key(), group.name(), group.members().size()));
        }
        List<CompileContext.ConnectionVersion> versions = connections.definitions(workspace.id()).stream()
                .map(d -> new CompileContext.ConnectionVersion(d.key(), d.version(),
                        json.write(d.definition().document()), d.definitionHash()))
                .toList();
        Map<String, CompileContext.WorkflowInfo> children = new LinkedHashMap<>();
        for (CatalogStore.CallableRow row : store.callable(workspace.id(), workflowKeys)) {
            if (row.activeReleaseId() == null) {
                children.put(row.key(), CompileContext.WorkflowInfo.unpublished(row.key(), row.name()));
                continue;
            }
            JsonNode manifest = json.parse(row.manifestJson());
            Set<String> closure = new LinkedHashSet<>();
            manifest.path("callClosure").forEach(k -> closure.add(k.asString()));
            children.put(row.key(), CompileContext.WorkflowInfo.of(row.key(), row.name(),
                    row.activeReleaseId().toString(), row.releaseNumber(), row.semanticHash(), row.definitionJson(),
                    closure, manifest.path("callHeight").asInt(0)));
        }
        // Adapter steps (S68) are checked against the workspace's current adapters; publishing pins nothing, the runtime
        // checks the adapter again before every send.
        Map<String, CompileContext.AdapterInfo> adapterInfos = new LinkedHashMap<>();
        for (AdapterSteps.Fact fact : adapters.facts(workspace.id())) {
            adapterInfos.put(fact.key(), new CompileContext.AdapterInfo(fact.key(), fact.kind(), fact.name(), fact.enabled(),
                    fact.configJson()));
        }
        // Agent write tools (IN04, S75): only currently active approvals; the runtime checks again before every write.
        Set<CompileContext.WriteToolApproval> approvals = new LinkedHashSet<>();
        for (AgentWriteTools.ApprovalFact fact : writeTools.activeApprovals(workspace.id())) {
            approvals.add(new CompileContext.WriteToolApproval(fact.workflowKey(), fact.connectionKey(),
                    fact.connectionVersion(), fact.operation(), fact.toolName()));
        }
        return new CompileContext(workspace.allowSelfApprovalWorkflows(), groupInfos, versions,
                connections.enabledBindings(workspace.id()), children, adapterInfos, approvals);
    }
}
