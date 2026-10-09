package com.orqix.identity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Effective access of one caller in one workspace (S6 section 2), computed with current policy and cached for at most
 * {@code orqix.policy.cache-ttl}.
 *
 * @param member             any workspace-scoped role binding or grant for the caller's principals
 * @param permissions        role permissions plus workspace-wide grants
 * @param workflowStartGrants workflows with a workflow-specific {@code workflow.start} grant
 * @param groupKeys          workspace task-assignment groups the caller belongs to
 */
public record WorkspaceAccess(Workspace workspace, UUID subjectId, boolean member, Set<Permission> permissions,
        Set<UUID> workflowStartGrants, Set<String> groupKeys, boolean platformAdmin) {

    /** Workspace facts. */
    public record Workspace(UUID id, UUID organizationId, String organizationName, String key, String name,
            String engineTenantId, boolean isTest, boolean allowSelfApprovalWorkflows) {
    }

    public WorkspaceAccess {
        permissions = permissions.isEmpty() ? Collections.unmodifiableSet(EnumSet.noneOf(Permission.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
        workflowStartGrants = Set.copyOf(workflowStartGrants);
        groupKeys = Set.copyOf(groupKeys);
    }

    public UUID workspaceId() {
        return workspace.id();
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    /** Non-members without workspace.manage-access see nothing of the workspace (404 rule, S6 section 4). */
    public boolean visible() {
        return member || has(Permission.WORKSPACE_MANAGE_ACCESS);
    }

    /** Workspace-wide or workflow-specific {@code workflow.start}. */
    public boolean canStart(UUID workflowId) {
        return has(Permission.WORKFLOW_START) || workflowStartGrants.contains(workflowId);
    }

    public boolean canStartAnything() {
        return has(Permission.WORKFLOW_START) || !workflowStartGrants.isEmpty();
    }
}
