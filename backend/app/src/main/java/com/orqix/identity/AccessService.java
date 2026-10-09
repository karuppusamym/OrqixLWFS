package com.orqix.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.shared.Ids;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/**
 * Workspace access administration (B02.4): role bindings, permission grants and task-assignment groups. Every change
 * evicts this instance's policy cache; other pods converge within {@code orqix.policy.cache-ttl} (T-SEC-09).
 */
@Service
public class AccessService {

    public record PrincipalRef(String type, String value) {
    }

    public record BindingRow(UUID id, String role, String principalType, String principalValue, String source,
            Instant createdAt) {
    }

    public record GrantRow(UUID id, String permission, UUID workflowId, String principalType, String principalValue,
            Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final GroupDirectory groups;
    private final AuditWriter audit;
    private final Clock clock;

    public AccessService(JdbcClient jdbc, PolicyService policy, GroupDirectory groups, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.groups = groups;
        this.audit = audit;
        this.clock = clock;
    }

    /** Workspace visible and caller holds workspace.manage-access (403 audited otherwise). */
    public WorkspaceAccess requireManager(Caller caller, UUID workspaceId, String operationId, boolean audited) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, operationId, audited);
        return access;
    }

    @Transactional(readOnly = true)
    public List<BindingRow> bindings(UUID workspaceId) {
        return jdbc.sql("""
                SELECT id, role, principal_type, principal_value, source, created_at FROM role_binding
                WHERE scope_type = 'workspace' AND workspace_id = :ws ORDER BY created_at, id
                """)
                .param("ws", workspaceId)
                .query((rs, n) -> new BindingRow(Sql.uuid(rs, "id"), rs.getString("role"), rs.getString("principal_type"),
                        rs.getString("principal_value"), rs.getString("source"), Sql.instant(rs, "created_at")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<GrantRow> grants(UUID workspaceId) {
        return jdbc.sql("""
                SELECT id, permission, workflow_id, principal_type, principal_value, created_at FROM permission_grant
                WHERE workspace_id = :ws ORDER BY created_at, id
                """)
                .param("ws", workspaceId)
                .query((rs, n) -> new GrantRow(Sql.uuid(rs, "id"), rs.getString("permission"), Sql.uuid(rs, "workflow_id"),
                        rs.getString("principal_type"), rs.getString("principal_value"), Sql.instant(rs, "created_at")))
                .list();
    }

    @Transactional
    public BindingRow createBinding(Caller caller, UUID workspaceId, String role, PrincipalRef principal) {
        requireManager(caller, workspaceId, "createRoleBinding", true);
        Role parsed = parseRole(role);
        validatePrincipal(principal);
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO role_binding (id, scope_type, workspace_id, role, principal_type, principal_value, source,
                                          created_by, created_at)
                VALUES (:id, 'workspace', :ws, :role, :type, :value, 'manual', :by, :at)
                ON CONFLICT DO NOTHING
                """)
                .param("id", id).param("ws", workspaceId).param("role", parsed.name()).param("type", principal.type())
                .param("value", principal.value()).param("by", caller.subjectId()).param("at", Sql.ts(clock.instant()))
                .update();
        if (inserted != 1) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "This role binding already exists.");
        }
        policy.evictAll();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "access.role-binding.created", "roleBinding", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("role", parsed.name(), "principalType", principal.type(), "principalValue", principal.value())));
        return bindings(workspaceId).stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteBinding(Caller caller, UUID workspaceId, UUID bindingId) {
        requireManager(caller, workspaceId, "deleteRoleBinding", true);
        BindingRow binding = bindings(workspaceId).stream().filter(b -> b.id().equals(bindingId)).findFirst()
                .orElseThrow(ApiException::notFound);
        jdbc.sql("DELETE FROM role_binding WHERE id = :id AND workspace_id = :ws AND scope_type = 'workspace'")
                .param("id", bindingId).param("ws", workspaceId).update();
        policy.evictAll();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "access.role-binding.deleted", "roleBinding", bindingId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("role", binding.role(), "principalType", binding.principalType(),
                        "principalValue", binding.principalValue())));
    }

    @Transactional
    public GrantRow createGrant(Caller caller, UUID workspaceId, String permission, UUID workflowId,
            PrincipalRef principal) {
        requireManager(caller, workspaceId, "createPermissionGrant", true);
        validatePrincipal(principal);
        if (!"workflow.start".equals(permission) && !"connection.manage".equals(permission)) {
            throw invalid("/permission", "enum", "Only workflow.start and connection.manage can be granted.");
        }
        if ("connection.manage".equals(permission) && workflowId != null) {
            throw invalid("/workflowId", "type", "connection.manage is workspace-wide.");
        }
        if (workflowId != null && jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", workflowId).query(Integer.class).single() == 0) {
            throw invalid("/workflowId", "enum", "Unknown workflow.");
        }
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO permission_grant (id, workspace_id, workflow_id, permission, principal_type, principal_value,
                                              created_by, created_at)
                VALUES (:id, :ws, :wf, :permission, :type, :value, :by, :at)
                ON CONFLICT DO NOTHING
                """)
                .param("id", id).param("ws", workspaceId).param("wf", workflowId).param("permission", permission)
                .param("type", principal.type()).param("value", principal.value()).param("by", caller.subjectId())
                .param("at", Sql.ts(clock.instant())).update();
        if (inserted != 1) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "This grant already exists.");
        }
        policy.evictAll();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("permission", permission);
        metadata.put("workflowId", workflowId == null ? null : workflowId.toString());
        metadata.put("principalType", principal.type());
        metadata.put("principalValue", principal.value());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "access.grant.created", "grant", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return grants(workspaceId).stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteGrant(Caller caller, UUID workspaceId, UUID grantId) {
        requireManager(caller, workspaceId, "deletePermissionGrant", true);
        GrantRow grant = grants(workspaceId).stream().filter(g -> g.id().equals(grantId)).findFirst()
                .orElseThrow(ApiException::notFound);
        jdbc.sql("DELETE FROM permission_grant WHERE id = :id AND workspace_id = :ws")
                .param("id", grantId).param("ws", workspaceId).update();
        policy.evictAll();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("permission", grant.permission());
        metadata.put("workflowId", grant.workflowId() == null ? null : grant.workflowId().toString());
        metadata.put("principalType", grant.principalType());
        metadata.put("principalValue", grant.principalValue());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "access.grant.deleted", "grant", grantId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
    }

    /** Creates or replaces a group and its members; {@code expectedRevision} is required when the group exists. */
    @Transactional
    public GroupDirectory.Group putGroup(Caller caller, UUID workspaceId, String key, String name,
            List<PrincipalRef> members, Integer expectedRevision) {
        requireManager(caller, workspaceId, "putGroup", true);
        members.forEach(this::validatePrincipal);
        Optional<UUID> existing = jdbc.sql("SELECT id FROM workspace_group WHERE workspace_id = :ws AND key = :key FOR UPDATE")
                .param("ws", workspaceId).param("key", key).query(UUID.class).optional();
        UUID groupId;
        int revision;
        if (existing.isPresent()) {
            groupId = existing.get();
            int current = jdbc.sql("SELECT revision FROM workspace_group WHERE id = :id").param("id", groupId)
                    .query(Integer.class).single();
            if (expectedRevision == null || expectedRevision != current) {
                throw new ApiException(ProblemCode.GROUP_REVISION_CONFLICT, "The group changed since you loaded it.")
                        .with("currentRevision", current);
            }
            revision = current + 1;
            jdbc.sql("UPDATE workspace_group SET name = :name, revision = :revision WHERE id = :id AND workspace_id = :ws")
                    .param("name", name).param("revision", revision).param("id", groupId).param("ws", workspaceId).update();
            jdbc.sql("DELETE FROM workspace_group_member WHERE workspace_id = :ws AND group_id = :id")
                    .param("ws", workspaceId).param("id", groupId).update();
        } else {
            groupId = Ids.newId();
            revision = 1;
            jdbc.sql("""
                    INSERT INTO workspace_group (id, workspace_id, key, name, revision, created_at)
                    VALUES (:id, :ws, :key, :name, 1, :at)
                    """)
                    .param("id", groupId).param("ws", workspaceId).param("key", key).param("name", name)
                    .param("at", Sql.ts(clock.instant())).update();
        }
        List<PrincipalRef> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(members));
        for (PrincipalRef member : distinct) {
            jdbc.sql("""
                    INSERT INTO workspace_group_member (workspace_id, group_id, principal_type, principal_value, created_by,
                                                        created_at)
                    VALUES (:ws, :group, :type, :value, :by, :at)
                    """)
                    .param("ws", workspaceId).param("group", groupId).param("type", member.type())
                    .param("value", member.value()).param("by", caller.subjectId()).param("at", Sql.ts(clock.instant()))
                    .update();
        }
        policy.evictAll();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "access.group.updated", "group", key, AuditWriter.Outcome.SUCCESS,
                Map.of("groupKey", key, "memberCount", distinct.size(), "revision", revision)));
        return groups.group(workspaceId, key).orElseThrow();
    }

    private void validatePrincipal(PrincipalRef principal) {
        if (principal == null || principal.type() == null || principal.value() == null || principal.value().isBlank()
                || principal.value().length() > 256) {
            throw invalid("/principal", "required", "A principal needs a type and a value.");
        }
        if ("subject".equals(principal.type())) {
            UUID id = Ids.parseOrNull(principal.value());
            if (id == null || jdbc.sql("SELECT count(*) FROM subject WHERE id = :id").param("id", id)
                    .query(Integer.class).single() == 0) {
                throw invalid("/principal/value", "enum", "Unknown subject.");
            }
        } else if (!"idpGroup".equals(principal.type())) {
            throw invalid("/principal/type", "enum", "Use subject or idpGroup.");
        }
    }

    private static Role parseRole(String role) {
        try {
            Role parsed = Role.valueOf(role);
            if (Role.WORKSPACE_ROLES.contains(parsed)) {
                return parsed;
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // fall through
        }
        throw invalid("/role", "enum", "Unknown workspace role.");
    }

    private static ApiException invalid(String pointer, String code, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, code, message)));
    }
}
