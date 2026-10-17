package com.orqix.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.web.Sql;

/** Workspace task-assignment groups (candidate groups of approval steps). */
@Service
public class GroupDirectory {

    public record Member(String type, String value) {
    }

    public record Group(UUID id, String key, String name, int revision, List<Member> members) {
    }

    private final JdbcClient jdbc;

    public GroupDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Group> groups(UUID workspaceId) {
        Map<UUID, Group> groups = new LinkedHashMap<>();
        jdbc.sql("SELECT id, key, name, revision FROM workspace_group WHERE workspace_id = :ws ORDER BY key")
                .param("ws", workspaceId)
                .query((rs, n) -> groups.put(Sql.uuid(rs, "id"), new Group(Sql.uuid(rs, "id"), rs.getString("key"),
                        rs.getString("name"), rs.getInt("revision"), new ArrayList<>())))
                .list();
        jdbc.sql("""
                SELECT group_id, principal_type, principal_value FROM workspace_group_member
                WHERE workspace_id = :ws ORDER BY principal_type, principal_value
                """)
                .param("ws", workspaceId)
                .query((rs, n) -> {
                    Group group = groups.get(Sql.uuid(rs, "group_id"));
                    if (group != null) {
                        group.members().add(new Member(rs.getString("principal_type"), rs.getString("principal_value")));
                    }
                    return null;
                })
                .list();
        return List.copyOf(groups.values());
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Group> group(UUID workspaceId, String key) {
        return groups(workspaceId).stream().filter(g -> g.key().equals(key)).findFirst();
    }

    /**
     * Subjects that may be eligible through a group: direct subject members, plus subjects whose last login
     * snapshot is unknown (IdP groups cannot be enumerated). Used for reassignment validation only.
     */
    @Transactional(readOnly = true)
    public List<String> idpGroupMembers(UUID workspaceId, String key) {
        return group(workspaceId, key).map(g -> g.members().stream().filter(m -> "idpGroup".equals(m.type()))
                .map(Member::value).toList()).orElse(List.of());
    }
}
