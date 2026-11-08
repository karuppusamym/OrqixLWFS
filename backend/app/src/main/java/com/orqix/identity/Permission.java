package com.orqix.identity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Workspace permissions (S6 section 3; OpenAPI {@code Permission}). {@code platform.admin} is a separate flag. */
public enum Permission {
    WORKFLOW_READ("workflow.read"),
    WORKFLOW_EDIT("workflow.edit"),
    WORKFLOW_PUBLISH("workflow.publish"),
    WORKFLOW_EXPORT("workflow.export"),
    WORKFLOW_START("workflow.start"),
    RUN_READ("run.read"),
    RUN_READ_DATA("run.read-data"),
    RUN_OPERATE("run.operate"),
    TASK_ACT("task.act"),
    CONNECTION_READ("connection.read"),
    CONNECTION_MANAGE("connection.manage"),
    WORKSPACE_MANAGE_ACCESS("workspace.manage-access"),
    AUDIT_READ("audit.read"),
    MESSAGE_SEND("message.send"),
    DOCUMENT_BROWSE("document.browse"),
    JOB_WORK("job.work"),
    WORKFLOW_APPROVE_PROMOTION("workflow.approve-promotion"),
    /** IN04 (S75): grant-only, workspace-wide; approves or revokes agent write tools. No role carries it. */
    AGENT_APPROVE_WRITE_TOOL("agent.approve-write-tool"),
    /** CG-C04 (S133): grant-only, workspace-wide; imports, reviews, installs, binds and retires connector packages. */
    CONNECTOR_MANAGE("connector.manage"),
    /** CG-C04 (S133): grant-only, workspace-wide; approves the exact hash of a package another person imported. */
    CONNECTOR_APPROVE("connector.approve"),
    /** Grant-only (S134): approve AI skill versions and promotions written by someone else. */
    AI_APPROVE_SKILL("ai.approve-skill"),
    /** Grant-only (S134): run AI evaluation suites, read shadow dashboards and the AI review queue. */
    AI_EVALUATE("ai.evaluate"),
    /** Grant-only, workspace-wide (CG-O06, S144): manage alert rules and destinations, read incidents. */
    ALERT_MANAGE("alert.manage");

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Permission of(String value) {
        for (Permission p : values()) {
            if (p.value.equals(value)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown permission " + value);
    }

    /** API permission list: enum order; {@code workflow.start} also when only workflow-specific grants exist. */
    public static java.util.List<String> apiValues(java.util.Collection<Permission> permissions, boolean canStartAnything) {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (Permission p : values()) {
            if (permissions.contains(p) || (p == WORKFLOW_START && canStartAnything)) {
                values.add(p.value());
            }
        }
        return values;
    }

    /** Default role mapping (S6 section 3). Administrators do not inherit business permissions. */
    public static final Map<Role, Set<Permission>> ROLE_PERMISSIONS = Map.of(
            Role.PLATFORM_ADMIN, EnumSet.of(WORKSPACE_MANAGE_ACCESS),
            Role.ORGANIZATION_ADMIN, EnumSet.of(WORKSPACE_MANAGE_ACCESS),
            Role.WORKSPACE_ADMIN, EnumSet.of(WORKSPACE_MANAGE_ACCESS, CONNECTION_READ),
            Role.WORKFLOW_DESIGNER, EnumSet.of(WORKFLOW_READ, WORKFLOW_EDIT, WORKFLOW_EXPORT, CONNECTION_READ),
            Role.PUBLISHER, EnumSet.of(WORKFLOW_READ, WORKFLOW_PUBLISH, WORKFLOW_EXPORT, CONNECTION_READ),
            Role.OPERATOR, EnumSet.of(WORKFLOW_READ, RUN_READ, RUN_READ_DATA, RUN_OPERATE, CONNECTION_READ, MESSAGE_SEND),
            Role.VIEWER, EnumSet.of(WORKFLOW_READ, RUN_READ),
            Role.APPROVER, EnumSet.of(TASK_ACT));
}
