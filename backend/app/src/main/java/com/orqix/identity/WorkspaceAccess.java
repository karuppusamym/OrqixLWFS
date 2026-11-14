package com.orqix.identity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Effective access of one caller in one workspace (S6 section 2), computed with current policy and cached for at most
 * {@code orqix.policy.cache-ttl}.
 *
 * @param member             any workspace-scoped role binding or grant for the caller's principals
 * @param permissions        role permissions plus workspace-wide grants
 * @param workflowStartGrants workflows with a workflow-specific {@code workflow.start} grant (also from CG-I01
 *                           workflow and folder grants)
 * @param groupKeys          workspace task-assignment groups the caller belongs to
 * @param resources          CG-I01 (S151) resource grants; they only add a permission on their own resource
 */
public record WorkspaceAccess(Workspace workspace, UUID subjectId, boolean member, Set<Permission> permissions,
        Set<UUID> workflowStartGrants, Set<String> groupKeys, boolean platformAdmin, ResourceGrants resources) {

    /** Workspace facts. */
    public record Workspace(UUID id, UUID organizationId, String organizationName, String key, String name,
            String engineTenantId, boolean isTest, boolean allowSelfApprovalWorkflows) {
    }

    /**
     * CG-I01 (S151): permissions held on single resources. Folder grants are already expanded to the workflows filed
     * in the folder. Each map holds only the permissions the resource type allows (V450).
     */
    public record ResourceGrants(Map<Permission, Set<UUID>> workflows, Map<Permission, Set<String>> decisions,
            Map<Permission, Set<String>> connections) {

        public static final ResourceGrants NONE = new ResourceGrants(Map.of(), Map.of(), Map.of());

        public ResourceGrants {
            workflows = Map.copyOf(workflows);
            decisions = Map.copyOf(decisions);
            connections = Map.copyOf(connections);
        }

        public boolean isEmpty() {
            return workflows.isEmpty() && decisions.isEmpty() && connections.isEmpty();
        }

        public Set<UUID> workflows(Permission permission) {
            return workflows.getOrDefault(permission, Set.of());
        }

        public Set<String> decisions(Permission permission) {
            return decisions.getOrDefault(permission, Set.of());
        }

        public Set<String> connections(Permission permission) {
            return connections.getOrDefault(permission, Set.of());
        }
    }

    public WorkspaceAccess {
        permissions = permissions.isEmpty() ? Collections.unmodifiableSet(EnumSet.noneOf(Permission.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
        workflowStartGrants = Set.copyOf(workflowStartGrants);
        groupKeys = Set.copyOf(groupKeys);
        resources = resources == null ? ResourceGrants.NONE : resources;
    }

    public WorkspaceAccess(Workspace workspace, UUID subjectId, boolean member, Set<Permission> permissions,
            Set<UUID> workflowStartGrants, Set<String> groupKeys, boolean platformAdmin) {
        this(workspace, subjectId, member, permissions, workflowStartGrants, groupKeys, platformAdmin,
                ResourceGrants.NONE);
    }

    public UUID workspaceId() {
        return workspace.id();
    }

    /** Workspace-wide permission (role or workspace-wide grant). Resource grants never count here. */
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

    /** Workspace-wide, or granted on this workflow (directly or through its folder). */
    public boolean hasOnWorkflow(Permission permission, UUID workflowId) {
        return has(permission) || (workflowId != null && resources.workflows(permission).contains(workflowId));
    }

    /**
     * Workflows on which the caller holds the permission only through resource grants; null when it is held
     * workspace-wide (every workflow). Used to filter lists and searches.
     */
    public Set<UUID> workflowsWith(Permission permission) {
        return has(permission) ? null : resources.workflows(permission);
    }

    /** Workspace-wide or on this decision table (by key). */
    public boolean hasOnDecision(Permission permission, String decisionKey) {
        return has(permission) || (decisionKey != null && resources.decisions(permission).contains(decisionKey));
    }

    /** Workspace-wide or on this connection (by key). */
    public boolean hasOnConnection(Permission permission, String connectionKey) {
        return has(permission) || (connectionKey != null && resources.connections(permission).contains(connectionKey));
    }

    /** True when the caller holds the permission workspace-wide or on at least one resource. */
    public boolean hasAnywhere(Permission permission) {
        return has(permission) || !resources.workflows(permission).isEmpty()
                || !resources.decisions(permission).isEmpty() || !resources.connections(permission).isEmpty();
    }

    /**
     * The access for operations on one workflow and its requests: workspace-wide permissions plus the resource grants
     * on that workflow. Use the result for that workflow only; it never carries grants on another resource.
     */
    public WorkspaceAccess onWorkflow(UUID workflowId) {
        return narrowed(scoped(resources.workflows(), workflowId));
    }

    /** As {@link #onWorkflow(UUID)}, for one shared decision table. */
    public WorkspaceAccess onDecision(String decisionKey) {
        return narrowed(scoped(resources.decisions(), decisionKey));
    }

    /** As {@link #onWorkflow(UUID)}, for one connection. */
    public WorkspaceAccess onConnection(String connectionKey) {
        return narrowed(scoped(resources.connections(), connectionKey));
    }

    private static <K> Set<Permission> scoped(Map<Permission, Set<K>> grants, K resource) {
        Set<Permission> found = EnumSet.noneOf(Permission.class);
        if (resource != null) {
            grants.forEach((permission, ids) -> {
                if (ids.contains(resource)) {
                    found.add(permission);
                }
            });
        }
        return found;
    }

    private WorkspaceAccess narrowed(Set<Permission> extra) {
        // workflow.start stays in workflowStartGrants (already merged at compute time), so canStartAnything keeps
        // its workspace meaning.
        extra.remove(Permission.WORKFLOW_START);
        if (extra.isEmpty() || permissions.containsAll(extra)) {
            return this;
        }
        Set<Permission> combined = new HashSet<>(permissions);
        combined.addAll(extra);
        return new WorkspaceAccess(workspace, subjectId, member, combined, workflowStartGrants, groupKeys,
                platformAdmin, ResourceGrants.NONE);
    }
}
