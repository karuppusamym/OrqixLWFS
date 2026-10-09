package com.orqix.catalog.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.compiler.CompileContext;
import com.orqix.identity.GroupDirectory;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.ConnectionService;
import com.orqix.shared.json.Json;

/** Builds a fresh {@link CompileContext} from current workspace facts (S4 sections 1 and 10). */
@Component
public class CompileContextFactory {

    private final GroupDirectory groups;
    private final ConnectionService connections;
    private final Json json;

    public CompileContextFactory(GroupDirectory groups, ConnectionService connections, Json json) {
        this.groups = groups;
        this.connections = connections;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public CompileContext create(WorkspaceAccess.Workspace workspace) {
        Map<String, CompileContext.GroupInfo> groupInfos = new LinkedHashMap<>();
        for (GroupDirectory.Group group : groups.groups(workspace.id())) {
            groupInfos.put(group.key(), new CompileContext.GroupInfo(group.key(), group.name(), group.members().size()));
        }
        List<CompileContext.ConnectionVersion> versions = connections.definitions(workspace.id()).stream()
                .map(d -> new CompileContext.ConnectionVersion(d.key(), d.version(),
                        json.write(d.definition().document()), d.definitionHash()))
                .toList();
        return new CompileContext(workspace.allowSelfApprovalWorkflows(), groupInfos, versions,
                connections.enabledBindings(workspace.id()));
    }
}
