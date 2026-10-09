package com.orqix.identity;

import java.util.EnumSet;
import java.util.Set;

/** Roles (V1 {@code role_binding.role}). Workspace roles are the ones assignable through the access API. */
public enum Role {
    PLATFORM_ADMIN, ORGANIZATION_ADMIN, WORKSPACE_ADMIN, WORKFLOW_DESIGNER, PUBLISHER, OPERATOR, VIEWER, APPROVER;

    public static final Set<Role> WORKSPACE_ROLES =
            EnumSet.of(WORKSPACE_ADMIN, WORKFLOW_DESIGNER, PUBLISHER, OPERATOR, VIEWER, APPROVER);
}
