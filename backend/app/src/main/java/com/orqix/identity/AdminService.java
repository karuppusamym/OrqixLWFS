package com.orqix.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.shared.Ids;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/** Platform administration (B02.4): organizations and workspaces with their first administrators. */
@Service
public class AdminService {

    public record OrganizationRow(UUID id, String key, String name, Instant createdAt) {
    }

    public record WorkspaceRow(UUID id, UUID organizationId, String key, String name, boolean isTest,
            boolean allowSelfApprovalWorkflows, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Clock clock;

    public AdminService(JdbcClient jdbc, PolicyService policy, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    public void requirePlatformAdmin(Caller caller, String operationId, boolean audited) {
        if (!policy.isPlatformAdmin(caller)) {
            throw policy.deny(null, caller.subjectId(), "platform.admin", operationId, audited,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
    }

    @Transactional(readOnly = true)
    public List<OrganizationRow> organizations(CursorCodec.Position after, int limitPlusOne) {
        return jdbc.sql("""
                SELECT id, key, name, created_at FROM organization
                WHERE (CAST(:afterAt AS timestamptz) IS NULL
                       OR (created_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY created_at DESC, id DESC LIMIT :limit
                """)
                .param("afterAt", after == null ? null : Sql.ts(after.at()))
                .param("afterId", after == null ? null : UUID.fromString(after.id())).param("limit", limitPlusOne)
                .query((rs, n) -> new OrganizationRow(Sql.uuid(rs, "id"), rs.getString("key"), rs.getString("name"),
                        Sql.instant(rs, "created_at")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<WorkspaceRow> workspaces(CursorCodec.Position after, int limitPlusOne) {
        return jdbc.sql("""
                SELECT id, organization_id, key, name, is_test, allow_self_approval_workflows, created_at FROM workspace
                WHERE (CAST(:afterAt AS timestamptz) IS NULL
                       OR (created_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY created_at DESC, id DESC LIMIT :limit
                """)
                .param("afterAt", after == null ? null : Sql.ts(after.at()))
                .param("afterId", after == null ? null : UUID.fromString(after.id())).param("limit", limitPlusOne)
                .query((rs, n) -> new WorkspaceRow(Sql.uuid(rs, "id"), Sql.uuid(rs, "organization_id"),
                        rs.getString("key"), rs.getString("name"), rs.getBoolean("is_test"),
                        rs.getBoolean("allow_self_approval_workflows"), Sql.instant(rs, "created_at")))
                .list();
    }

    @Transactional
    public OrganizationRow createOrganization(Caller caller, String key, String name) {
        requirePlatformAdmin(caller, "createOrganization", true);
        UUID id = Ids.newId();
        Instant now = clock.instant();
        int inserted = jdbc.sql("""
                INSERT INTO organization (id, key, name, created_at) VALUES (:id, :key, :name, :at)
                ON CONFLICT (key) DO NOTHING
                """)
                .param("id", id).param("key", key).param("name", name).param("at", Sql.ts(now)).update();
        if (inserted != 1) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "An organization with this key already exists.");
        }
        audit.write(new AuditWriter.Entry(null, AuditWriter.ActorType.USER, caller.subjectId(),
                "admin.organization.created", "organization", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("key", key)));
        return new OrganizationRow(id, key, name, now);
    }

    @Transactional
    public WorkspaceRow createWorkspace(Caller caller, UUID organizationId, String key, String name, boolean isTest,
            boolean allowSelfApproval, List<AccessService.PrincipalRef> initialAdmins) {
        requirePlatformAdmin(caller, "createWorkspace", true);
        if (jdbc.sql("SELECT count(*) FROM organization WHERE id = :id").param("id", organizationId)
                .query(Integer.class).single() == 0) {
            throw invalid("/organizationId", "Unknown organization.");
        }
        if (allowSelfApproval && !isTest) {
            throw invalid("/allowSelfApprovalWorkflows", "Self-approval workflows are only allowed in test workspaces.");
        }
        for (AccessService.PrincipalRef admin : initialAdmins) {
            if (!"subject".equals(admin.type()) && !"idpGroup".equals(admin.type())) {
                throw invalid("/initialAdmins", "Use subject or idpGroup principals.");
            }
            if ("subject".equals(admin.type()) && Ids.parseOrNull(admin.value()) == null) {
                throw invalid("/initialAdmins", "Subject principals are subject ids.");
            }
        }
        UUID id = Ids.newId();
        Instant now = clock.instant();
        int inserted = jdbc.sql("""
                INSERT INTO workspace (id, organization_id, key, name, engine_tenant_id, is_test,
                                       allow_self_approval_workflows, created_at)
                VALUES (:id, :org, :key, :name, :tenant, :test, :self, :at)
                ON CONFLICT DO NOTHING
                """)
                .param("id", id).param("org", organizationId).param("key", key).param("name", name)
                .param("tenant", id.toString()).param("test", isTest).param("self", allowSelfApproval)
                .param("at", Sql.ts(now)).update();
        if (inserted != 1) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A workspace with this key already exists.");
        }
        for (AccessService.PrincipalRef admin : new java.util.LinkedHashSet<>(initialAdmins)) {
            jdbc.sql("""
                    INSERT INTO role_binding (id, scope_type, workspace_id, role, principal_type, principal_value, source,
                                              created_by, created_at)
                    VALUES (:id, 'workspace', :ws, 'WORKSPACE_ADMIN', :type, :value, 'manual', :by, :at)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", Ids.newId()).param("ws", id).param("type", admin.type()).param("value", admin.value())
                    .param("by", caller.subjectId()).param("at", Sql.ts(now)).update();
        }
        policy.evictAll();
        audit.write(new AuditWriter.Entry(id, AuditWriter.ActorType.USER, caller.subjectId(), "admin.workspace.created",
                "workspace", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("key", key, "isTest", isTest)));
        return new WorkspaceRow(id, organizationId, key, name, isTest, allowSelfApproval, now);
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "enum", message)));
    }
}
