package com.orqix.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Authorization policy (S6 sections 2-5). Effective permissions are computed from role bindings and grants of the
 * caller's principals (subject + session IdP groups), cached per (subject, groups, workspace) for at most
 * {@code orqix.policy.cache-ttl} and evicted locally on any access change. Background work never uses this cache.
 */
@Service
public class PolicyService {

    private record CacheKey(UUID subjectId, String groups, UUID workspaceId) {
    }

    private record CacheEntry(Optional<WorkspaceAccess> access, Instant expiresAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final OrqixProperties properties;
    private final AuditWriter audit;
    private final MeterRegistry meters;
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public PolicyService(JdbcClient jdbc, Clock clock, OrqixProperties properties, AuditWriter audit,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.audit = audit;
        this.meters = meters;
    }

    /** Effective access, or empty when the workspace does not exist. */
    @Transactional(readOnly = true)
    public Optional<WorkspaceAccess> access(Caller caller, UUID workspaceId) {
        CacheKey key = new CacheKey(caller.subjectId(), caller.groupsKey(), workspaceId);
        Instant now = clock.instant();
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return entry.access();
        }
        Optional<WorkspaceAccess> computed = compute(caller, workspaceId);
        cache.put(key, new CacheEntry(computed, now.plus(properties.policy().cacheTtl())));
        return computed;
    }

    /** Current effective access for background work and another subject; bypasses the session cache. */
    @Transactional(readOnly = true)
    public Optional<WorkspaceAccess> freshAccess(Caller caller, UUID workspaceId) {
        return compute(caller, workspaceId);
    }

    /** Workspace visible to the caller (member or manage-access), else 404 (S6 section 4 step 3). */
    public WorkspaceAccess requireVisible(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = access(caller, workspaceId).orElseThrow(ApiException::notFound);
        if (!access.visible()) {
            throw ApiException.notFound();
        }
        return access;
    }

    /** Workspace membership required (member only, not manage-access), else 404. */
    public WorkspaceAccess requireMember(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = access(caller, workspaceId).orElseThrow(ApiException::notFound);
        if (!access.member()) {
            throw ApiException.notFound();
        }
        return access;
    }

    /** Action check: 403 FORBIDDEN (audited as {@code auth.denied} when {@code audited}). */
    public void require(WorkspaceAccess access, Permission permission, String operationId, boolean audited) {
        if (!access.has(permission)) {
            throw deny(access.workspaceId(), access.subjectId(), permission.value(), operationId, audited,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
    }

    /** Records a denial (metric, optional audit) and returns the exception to throw. */
    public ApiException deny(UUID workspaceId, UUID subjectId, String permission, String operationId, boolean audited,
            ApiException exception) {
        Counter.builder("orqix_authz_denied").tag("permission", permission).register(meters).increment();
        if (audited) {
            audit.writeDenied(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, subjectId, "auth.denied",
                    "operation", operationId, AuditWriter.Outcome.DENIED,
                    Map.of("permission", permission, "operationId", operationId)));
        }
        return exception;
    }

    public boolean isPlatformAdmin(Caller caller) {
        return jdbc.sql("""
                SELECT count(*) FROM role_binding
                WHERE scope_type = 'platform' AND role = 'PLATFORM_ADMIN' AND
                      ((principal_type = 'subject' AND principal_value = :subject)
                       OR (principal_type = 'idpGroup' AND principal_value IN (:groups)))
                """)
                .param("subject", caller.subjectId().toString()).param("groups", groupsParam(caller))
                .query(Integer.class).single() > 0;
    }

    /** Workspaces where the caller is a member or administers the organization. */
    @Transactional(readOnly = true)
    public List<WorkspaceAccess> memberships(Caller caller) {
        List<UUID> ids = jdbc.sql("""
                SELECT w.id FROM workspace w
                WHERE w.archived_at IS NULL AND (
                  EXISTS (SELECT 1 FROM role_binding rb WHERE rb.scope_type = 'workspace' AND rb.workspace_id = w.id
                          AND ((rb.principal_type = 'subject' AND rb.principal_value = :subject)
                               OR (rb.principal_type = 'idpGroup' AND rb.principal_value IN (:groups))))
                  OR EXISTS (SELECT 1 FROM permission_grant pg WHERE pg.workspace_id = w.id
                          AND ((pg.principal_type = 'subject' AND pg.principal_value = :subject)
                               OR (pg.principal_type = 'idpGroup' AND pg.principal_value IN (:groups))))
                  OR EXISTS (SELECT 1 FROM role_binding rb WHERE rb.scope_type = 'organization'
                          AND rb.organization_id = w.organization_id
                          AND ((rb.principal_type = 'subject' AND rb.principal_value = :subject)
                               OR (rb.principal_type = 'idpGroup' AND rb.principal_value IN (:groups)))))
                ORDER BY w.name, w.id
                """)
                .param("subject", caller.subjectId().toString()).param("groups", groupsParam(caller))
                .query(UUID.class).list();
        List<WorkspaceAccess> result = new ArrayList<>();
        for (UUID id : ids) {
            access(caller, id).filter(WorkspaceAccess::visible).ifPresent(result::add);
        }
        return result;
    }

    /** Evicts this instance's cache; other pods converge within the TTL (S6 section 1). */
    public void evictAll() {
        cache.clear();
    }

    /** Current workspace groups of the given principals, never cached (used by eligibility re-checks). */
    @Transactional(readOnly = true)
    public Set<String> groupKeys(UUID workspaceId, UUID subjectId, List<String> idpGroups) {
        return new HashSet<>(jdbc.sql("""
                SELECT DISTINCT g.key FROM workspace_group g
                JOIN workspace_group_member m ON m.workspace_id = g.workspace_id AND m.group_id = g.id
                WHERE g.workspace_id = :ws AND ((m.principal_type = 'subject' AND m.principal_value = :subject)
                      OR (m.principal_type = 'idpGroup' AND m.principal_value IN (:groups)))
                """)
                .param("ws", workspaceId).param("subject", subjectId.toString())
                .param("groups", idpGroups.isEmpty() ? List.of("") : idpGroups)
                .query(String.class).list());
    }

    private Optional<WorkspaceAccess> compute(Caller caller, UUID workspaceId) {
        Optional<WorkspaceAccess.Workspace> workspace = jdbc.sql("""
                SELECT w.id, w.organization_id, o.name AS org_name, w.key, w.name, w.engine_tenant_id, w.is_test,
                       w.allow_self_approval_workflows
                FROM workspace w JOIN organization o ON o.id = w.organization_id
                WHERE w.id = :ws AND w.archived_at IS NULL
                """)
                .param("ws", workspaceId)
                .query((rs, n) -> new WorkspaceAccess.Workspace(Sql.uuid(rs, "id"), Sql.uuid(rs, "organization_id"),
                        rs.getString("org_name"), rs.getString("key"), rs.getString("name"),
                        rs.getString("engine_tenant_id"), rs.getBoolean("is_test"),
                        rs.getBoolean("allow_self_approval_workflows")))
                .optional();
        if (workspace.isEmpty()) {
            return Optional.empty();
        }
        String subject = caller.subjectId().toString();
        List<String> groups = groupsParam(caller);
        record Binding(String scope, String role) {
        }
        List<Binding> bindings = jdbc.sql("""
                SELECT scope_type, role FROM role_binding
                WHERE ((principal_type = 'subject' AND principal_value = :subject)
                       OR (principal_type = 'idpGroup' AND principal_value IN (:groups)))
                  AND (scope_type = 'platform'
                       OR (scope_type = 'organization' AND organization_id = :org)
                       OR (scope_type = 'workspace' AND workspace_id = :ws))
                """)
                .param("subject", subject).param("groups", groups).param("org", workspace.get().organizationId())
                .param("ws", workspaceId)
                .query((rs, n) -> new Binding(rs.getString("scope_type"), rs.getString("role"))).list();
        record Grant(String permission, UUID workflowId) {
        }
        List<Grant> grants = jdbc.sql("""
                SELECT permission, workflow_id FROM permission_grant
                WHERE workspace_id = :ws AND ((principal_type = 'subject' AND principal_value = :subject)
                       OR (principal_type = 'idpGroup' AND principal_value IN (:groups)))
                """)
                .param("ws", workspaceId).param("subject", subject).param("groups", groups)
                .query((rs, n) -> new Grant(rs.getString("permission"), Sql.uuid(rs, "workflow_id"))).list();

        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        boolean member = false;
        boolean platformAdmin = false;
        for (Binding binding : bindings) {
            Role role = Role.valueOf(binding.role());
            permissions.addAll(Permission.ROLE_PERMISSIONS.getOrDefault(role, Set.of()));
            if ("workspace".equals(binding.scope())) {
                member = true;
            }
            if (role == Role.PLATFORM_ADMIN) {
                platformAdmin = true;
            }
        }
        Set<UUID> workflowStart = new HashSet<>();
        for (Grant grant : grants) {
            member = true;
            if (grant.workflowId() == null) {
                permissions.add(Permission.of(grant.permission()));
            } else {
                workflowStart.add(grant.workflowId());
            }
        }
        Set<String> groupKeys = groupKeys(workspaceId, caller.subjectId(), caller.groups());
        return Optional.of(new WorkspaceAccess(workspace.get(), caller.subjectId(), member, permissions, workflowStart,
                groupKeys, platformAdmin));
    }

    private static List<String> groupsParam(Caller caller) {
        // principal_value is never empty (DB check), so "" matches nothing and keeps IN (...) valid.
        return caller.groups().isEmpty() ? List.of("") : caller.groups();
    }
}
