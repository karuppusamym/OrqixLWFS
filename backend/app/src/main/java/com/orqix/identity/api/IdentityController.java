package com.orqix.identity.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orqix.identity.Caller;
import com.orqix.identity.CallerResolver;
import com.orqix.identity.GroupDirectory;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/** {@code getMe}, {@code getWorkspace}, {@code listGroups} (S3 section 10, S6). */
@RestController
@RequestMapping("/api/v1")
public class IdentityController {

    public record MeSubject(UUID id, String displayName, String email) {
    }

    public record Me(MeSubject subject, boolean platformAdmin, List<WorkspaceMembership> workspaces) {
    }

    public record WorkspaceMembership(UUID id, String key, String name, String organizationName, boolean isTest,
            List<String> permissions) {

        static WorkspaceMembership of(WorkspaceAccess access) {
            WorkspaceAccess.Workspace w = access.workspace();
            return new WorkspaceMembership(w.id(), w.key(), w.name(), w.organizationName(), w.isTest(),
                    permissionValues(access.permissions(), access.canStartAnything()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Principal(String type, String value, String displayName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(String key, String name, List<Principal> members, int revision) {
    }

    public record GroupList(List<Group> items) {
    }

    private final CallerResolver callers;
    private final PolicyService policy;
    private final GroupDirectory groups;
    private final SubjectService subjects;

    public IdentityController(CallerResolver callers, PolicyService policy, GroupDirectory groups,
            SubjectService subjects) {
        this.callers = callers;
        this.policy = policy;
        this.groups = groups;
        this.subjects = subjects;
    }

    @GetMapping("/me")
    public Me getMe() {
        Caller caller = callers.current();
        List<WorkspaceMembership> workspaces = policy.memberships(caller).stream().map(WorkspaceMembership::of).toList();
        return new Me(new MeSubject(caller.subjectId(), caller.displayName(), caller.email()),
                policy.isPlatformAdmin(caller), workspaces);
    }

    @GetMapping("/workspaces/{workspaceId}")
    public WorkspaceMembership getWorkspace(@PathVariable UUID workspaceId) {
        Caller caller = callers.current();
        return WorkspaceMembership.of(policy.requireVisible(caller, workspaceId));
    }

    @GetMapping("/workspaces/{workspaceId}/groups")
    public GroupList listGroups(@PathVariable UUID workspaceId) {
        Caller caller = callers.current();
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        boolean manager = access.has(Permission.WORKSPACE_MANAGE_ACCESS);
        if (!manager && !access.has(Permission.WORKFLOW_READ)) {
            throw policy.deny(workspaceId, caller.subjectId(), "workflow.read", "listGroups", false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        List<GroupDirectory.Group> all = groups.groups(workspaceId);
        Map<UUID, String> names = manager ? subjects.displayNames(subjectIds(all)) : Map.of();
        List<Group> items = new ArrayList<>();
        for (GroupDirectory.Group group : all) {
            List<Principal> members = manager ? group.members().stream()
                    .map(m -> new Principal(m.type(), m.value(), "subject".equals(m.type())
                            ? names.getOrDefault(parse(m.value()), null) : null))
                    .toList() : null;
            items.add(new Group(group.key(), group.name(), members, group.revision()));
        }
        return new GroupList(items);
    }

    public static List<String> permissionValues(Collection<Permission> permissions, boolean canStartAnything) {
        return Permission.apiValues(permissions, canStartAnything);
    }

    private static List<UUID> subjectIds(List<GroupDirectory.Group> groups) {
        return groups.stream().flatMap(g -> g.members().stream()).filter(m -> "subject".equals(m.type()))
                .map(m -> parse(m.value())).filter(java.util.Objects::nonNull).toList();
    }

    private static UUID parse(String value) {
        return com.orqix.shared.Ids.parseOrNull(value);
    }
}
