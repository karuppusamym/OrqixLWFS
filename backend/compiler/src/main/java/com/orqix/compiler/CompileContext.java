package com.orqix.compiler;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workspace facts the compiler needs for binding checks (S4 section 5.3). Supplied by the caller; the compiler never
 * reads a database.
 *
 * @param selfApprovalAllowed       true only when the workspace permits self-approval workflows (test workspaces)
 * @param groups                    workspace task-assignment groups by key
 * @param connectionVersions        every registered connection definition version in the workspace
 * @param enabledConnectionBindings connection keys with an enabled binding in this workspace
 */
public record CompileContext(
        boolean selfApprovalAllowed,
        Map<String, GroupInfo> groups,
        List<ConnectionVersion> connectionVersions,
        Set<String> enabledConnectionBindings) {

    public CompileContext {
        groups = Map.copyOf(groups);
        connectionVersions = List.copyOf(connectionVersions);
        enabledConnectionBindings = Set.copyOf(enabledConnectionBindings);
    }

    /** @param configuredMemberCount configured member principals (IdP group membership cannot be counted) */
    public record GroupInfo(String key, String name, int configuredMemberCount) {
    }

    /** @param definitionJson the connection definition document (connection-definition-v0.schema.json) */
    public record ConnectionVersion(String key, int version, String definitionJson, String definitionHash) {
    }
}
