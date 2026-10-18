package com.orqix.identity.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orqix.identity.AccessService;
import com.orqix.identity.AdminService;
import com.orqix.identity.Caller;
import com.orqix.identity.CallerResolver;
import com.orqix.identity.GroupDirectory;
import com.orqix.identity.SubjectService;
import com.orqix.shared.Ids;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.pagination.Limits;
import com.orqix.shared.pagination.Page;
import com.orqix.shared.problem.ApiException;

/** Access administration and platform administration (contracts/openapi.yaml, tags Access and Administration). */
@RestController
@RequestMapping("/api/v1")
public class AccessController {

    static final String KEY = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$";

    public record PrincipalRef(@NotNull @Pattern(regexp = "subject|idpGroup") String type,
            @NotNull @Size(min = 1, max = 256) String value) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Principal(String type, String value, String displayName) {
    }

    public record RoleBinding(UUID id, String role, Principal principal, String source, Instant createdAt) {
    }

    public record PermissionGrant(UUID id, String permission, UUID workflowId, Principal principal, Instant createdAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(String key, String name, List<Principal> members, int revision) {
    }

    public record AccessOverview(List<RoleBinding> roleBindings, List<PermissionGrant> grants, List<Group> groups) {
    }

    public record CreateRoleBindingRequest(
            @NotNull @Pattern(regexp = "WORKSPACE_ADMIN|WORKFLOW_DESIGNER|PUBLISHER|OPERATOR|VIEWER|APPROVER") String role,
            @NotNull @Valid PrincipalRef principal) {
    }

    public record CreateGrantRequest(@NotNull @Pattern(regexp = "workflow\\.start|connection\\.manage|message\\.send|document\\.browse|job\\.work|audit\\.read") String permission,
            UUID workflowId, @NotNull @Valid PrincipalRef principal) {
    }

    public record PutGroupRequest(@NotNull @Size(min = 1, max = 200) String name,
            @NotNull @Size(max = 200) List<@Valid PrincipalRef> members, @Min(1) Integer expectedRevision) {
    }

    public record Organization(UUID id, String key, String name, Instant createdAt) {
    }

    public record OrganizationPage(List<Organization> items, String nextCursor) {
    }

    public record WorkspaceAdminView(UUID id, UUID organizationId, String key, String name, boolean isTest,
            boolean allowSelfApprovalWorkflows, Instant createdAt) {
    }

    public record WorkspacePage(List<WorkspaceAdminView> items, String nextCursor) {
    }

    public record CreateOrganizationRequest(@NotNull @Pattern(regexp = KEY) String key,
            @NotNull @Size(min = 1, max = 200) String name) {
    }

    public record CreateWorkspaceRequest(@NotNull UUID organizationId, @NotNull @Pattern(regexp = KEY) String key,
            @NotNull @Size(min = 1, max = 200) String name, @NotNull Boolean isTest, Boolean allowSelfApprovalWorkflows,
            @NotNull @Size(min = 1, max = 20) List<@Valid PrincipalRef> initialAdmins) {
    }

    private final CallerResolver callers;
    private final AccessService access;
    private final AdminService admin;
    private final SubjectService subjects;
    private final GroupDirectory groups;
    private final CursorCodec cursors;

    public AccessController(CallerResolver callers, AccessService access, AdminService admin, SubjectService subjects,
            GroupDirectory groups, CursorCodec cursors) {
        this.callers = callers;
        this.access = access;
        this.admin = admin;
        this.subjects = subjects;
        this.groups = groups;
        this.cursors = cursors;
    }

    // ---------------------------------------------------------------- access

    @GetMapping("/workspaces/{workspaceId}/access")
    public AccessOverview getAccessOverview(@PathVariable UUID workspaceId) {
        Caller caller = callers.current();
        access.requireManager(caller, workspaceId, "getAccessOverview", false);
        List<AccessService.BindingRow> bindings = access.bindings(workspaceId);
        List<AccessService.GrantRow> grants = access.grants(workspaceId);
        List<GroupDirectory.Group> groupRows = groups.groups(workspaceId);
        Map<UUID, String> names = names(bindings, grants, groupRows);
        return new AccessOverview(
                bindings.stream().map(b -> binding(b, names)).toList(),
                grants.stream().map(g -> grant(g, names)).toList(),
                groupRows.stream().map(g -> group(g, names)).toList());
    }

    @PostMapping("/workspaces/{workspaceId}/access/role-bindings")
    public ResponseEntity<RoleBinding> createRoleBinding(@PathVariable UUID workspaceId,
            @Valid @RequestBody CreateRoleBindingRequest request) {
        AccessService.BindingRow row = access.createBinding(callers.current(), workspaceId, request.role(),
                new AccessService.PrincipalRef(request.principal().type(), request.principal().value()));
        return ResponseEntity.status(HttpStatus.CREATED).body(binding(row, names(List.of(row), List.of(), List.of())));
    }

    @DeleteMapping("/workspaces/{workspaceId}/access/role-bindings/{bindingId}")
    public ResponseEntity<Void> deleteRoleBinding(@PathVariable UUID workspaceId, @PathVariable UUID bindingId) {
        access.deleteBinding(callers.current(), workspaceId, bindingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/workspaces/{workspaceId}/access/grants")
    public ResponseEntity<PermissionGrant> createPermissionGrant(@PathVariable UUID workspaceId,
            @Valid @RequestBody CreateGrantRequest request) {
        AccessService.GrantRow row = access.createGrant(callers.current(), workspaceId, request.permission(),
                request.workflowId(), new AccessService.PrincipalRef(request.principal().type(), request.principal().value()));
        return ResponseEntity.status(HttpStatus.CREATED).body(grant(row, names(List.of(), List.of(row), List.of())));
    }

    @DeleteMapping("/workspaces/{workspaceId}/access/grants/{grantId}")
    public ResponseEntity<Void> deletePermissionGrant(@PathVariable UUID workspaceId, @PathVariable UUID grantId) {
        access.deleteGrant(callers.current(), workspaceId, grantId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/workspaces/{workspaceId}/groups/{groupKey}")
    public Group putGroup(@PathVariable UUID workspaceId, @PathVariable String groupKey,
            @Valid @RequestBody PutGroupRequest request) {
        if (!groupKey.matches(KEY)) {
            throw ApiException.notFound();
        }
        GroupDirectory.Group group = access.putGroup(callers.current(), workspaceId, groupKey, request.name(),
                request.members().stream().map(m -> new AccessService.PrincipalRef(m.type(), m.value())).toList(),
                request.expectedRevision());
        return group(group, names(List.of(), List.of(), List.of(group)));
    }

    // ---------------------------------------------------------------- platform administration

    @GetMapping("/admin/organizations")
    public OrganizationPage listOrganizations(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        admin.requirePlatformAdmin(callers.current(), "listOrganizations", false);
        int size = Limits.of(limit);
        String filterKey = "listOrganizations";
        Page.Result<AdminService.OrganizationRow> page = Page.of(admin.organizations(cursors.decode(cursor, filterKey),
                size + 1), size, o -> new CursorCodec.Position(o.createdAt(), o.id().toString()), cursors, filterKey);
        return new OrganizationPage(page.items().stream()
                .map(o -> new Organization(o.id(), o.key(), o.name(), o.createdAt())).toList(), page.nextCursor());
    }

    @PostMapping("/admin/organizations")
    public ResponseEntity<Organization> createOrganization(@Valid @RequestBody CreateOrganizationRequest request) {
        AdminService.OrganizationRow row = admin.createOrganization(callers.current(), request.key(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Organization(row.id(), row.key(), row.name(),
                row.createdAt()));
    }

    @GetMapping("/admin/workspaces")
    public WorkspacePage listWorkspacesAdmin(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        admin.requirePlatformAdmin(callers.current(), "listWorkspacesAdmin", false);
        int size = Limits.of(limit);
        String filterKey = "listWorkspacesAdmin";
        Page.Result<AdminService.WorkspaceRow> page = Page.of(admin.workspaces(cursors.decode(cursor, filterKey),
                size + 1), size, w -> new CursorCodec.Position(w.createdAt(), w.id().toString()), cursors, filterKey);
        return new WorkspacePage(page.items().stream().map(AccessController::workspace).toList(), page.nextCursor());
    }

    @PostMapping("/admin/workspaces")
    public ResponseEntity<WorkspaceAdminView> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request) {
        AdminService.WorkspaceRow row = admin.createWorkspace(callers.current(), request.organizationId(), request.key(),
                request.name(), request.isTest(), Boolean.TRUE.equals(request.allowSelfApprovalWorkflows()),
                request.initialAdmins().stream().map(p -> new AccessService.PrincipalRef(p.type(), p.value())).toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(workspace(row));
    }

    // ---------------------------------------------------------------- mapping

    private static WorkspaceAdminView workspace(AdminService.WorkspaceRow w) {
        return new WorkspaceAdminView(w.id(), w.organizationId(), w.key(), w.name(), w.isTest(),
                w.allowSelfApprovalWorkflows(), w.createdAt());
    }

    private Map<UUID, String> names(List<AccessService.BindingRow> bindings, List<AccessService.GrantRow> grants,
            List<GroupDirectory.Group> groupRows) {
        Set<UUID> ids = new HashSet<>();
        bindings.stream().filter(b -> "subject".equals(b.principalType())).map(b -> Ids.parseOrNull(b.principalValue()))
                .forEach(ids::add);
        grants.stream().filter(g -> "subject".equals(g.principalType())).map(g -> Ids.parseOrNull(g.principalValue()))
                .forEach(ids::add);
        groupRows.forEach(g -> g.members().stream().filter(m -> "subject".equals(m.type()))
                .map(m -> Ids.parseOrNull(m.value())).forEach(ids::add));
        ids.remove(null);
        return subjects.displayNames(ids);
    }

    private static Principal principal(String type, String value, Map<UUID, String> names) {
        String displayName = "subject".equals(type) ? names.get(Ids.parseOrNull(value)) : null;
        return new Principal(type, value, displayName);
    }

    private static RoleBinding binding(AccessService.BindingRow b, Map<UUID, String> names) {
        return new RoleBinding(b.id(), b.role(), principal(b.principalType(), b.principalValue(), names), b.source(),
                b.createdAt());
    }

    private static PermissionGrant grant(AccessService.GrantRow g, Map<UUID, String> names) {
        return new PermissionGrant(g.id(), g.permission(), g.workflowId(),
                principal(g.principalType(), g.principalValue(), names), g.createdAt());
    }

    private static Group group(GroupDirectory.Group g, Map<UUID, String> names) {
        List<Principal> members = new ArrayList<>();
        g.members().forEach(m -> members.add(principal(m.type(), m.value(), names)));
        return new Group(g.key(), g.name(), members, g.revision());
    }

    @SuppressWarnings("unused")
    private static int max(@Max(100) int value) {
        return value;
    }
}
