package com.orqix.ai.internal;

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

/**
 * Current workspace facts for one generation: the groups and connection definitions shown to the model and the
 * {@link CompileContext} the draft is validated with (built exactly like the catalog's context, from the same
 * sources, so an AI draft validates the same way in the studio).
 */
@Component
public class WorkspaceFacts {

    private final GroupDirectory groups;
    private final ConnectionService connections;
    private final Json json;

    public WorkspaceFacts(GroupDirectory groups, ConnectionService connections, Json json) {
        this.groups = groups;
        this.connections = connections;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public DraftRules.Workspace load(WorkspaceAccess.Workspace workspace) {
        Map<String, CompileContext.GroupInfo> groupInfos = new LinkedHashMap<>();
        List<GroupDirectory.Group> all = groups.groups(workspace.id());
        for (GroupDirectory.Group group : all) {
            groupInfos.put(group.key(), new CompileContext.GroupInfo(group.key(), group.name(), group.members().size()));
        }
        List<ConnectionService.DefinitionRecord> definitions = connections.definitions(workspace.id());
        List<CompileContext.ConnectionVersion> versions = definitions.stream()
                .map(d -> new CompileContext.ConnectionVersion(d.key(), d.version(),
                        json.write(d.definition().document()), d.definitionHash()))
                .toList();
        CompileContext context = new CompileContext(workspace.allowSelfApprovalWorkflows(), groupInfos, versions,
                connections.enabledBindings(workspace.id()));
        return new DraftRules.Workspace(
                all.stream().map(g -> new DraftRules.GroupFact(g.key(), g.name())).toList(),
                definitions.stream().map(d -> new DraftRules.ConnectionFact(d.key(), d.version(), d.definition().name(),
                        d.definition().document())).toList(),
                context);
    }
}
