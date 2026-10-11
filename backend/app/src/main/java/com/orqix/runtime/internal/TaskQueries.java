package com.orqix.runtime.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/** Inbox queries over {@code task_context} / {@code task_decision} (S2 section 8 indexes), workspace scoped. */
@Repository
public class TaskQueries {

    public record DecisionRow(UUID id, String taskId, UUID runId, String nodeId, UUID actor, String action,
            String reason, String comment, JsonNode policyFlags, java.time.Instant decidedAt) {
    }

    private static final String T_COLUMNS = """
            t.engine_task_id, t.workspace_id, t.run_id, t.node_id, t.label, t.candidate_group_key, t.initiator_subject_id,
            t.self_approval_allowed, t.assignee_subject_id, t.status, t.version, t.priority, t.due_at, t.created_at, t.claimed_at,
            t.ended_at
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public TaskQueries(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<TaskStore.TaskRow> open(UUID workspaceId, UUID subject, Collection<String> groups,
            boolean assigned, CursorCodec.Position after, int limit, String sort, int minPriority) {
        if (!assigned && groups.isEmpty()) return List.of();
        String id = after == null ? null : after.id();
        Integer priority = null;
        if (after != null && "priority".equals(sort)) {
            int colon = id.indexOf(':');
            try {
                priority = Integer.valueOf(id.substring(0, colon));
                if (priority < 0 || priority > 100) throw new IllegalArgumentException();
                id = id.substring(colon + 1);
            } catch (RuntimeException e) { throw new com.orqix.shared.problem.ApiException(com.orqix.shared.problem.ProblemCode.INVALID_CURSOR); }
        }
        String comparison = switch (sort) {
            case "priority" -> "(t.priority < :afterPriority OR (t.priority = :afterPriority AND (t.created_at,t.engine_task_id) > (CAST(:afterAt AS timestamptz),CAST(:afterId AS text))))";
            case "oldest" -> "(t.created_at,t.engine_task_id) > (CAST(:afterAt AS timestamptz),CAST(:afterId AS text))";
            default -> "(t.created_at,t.engine_task_id) < (CAST(:afterAt AS timestamptz),CAST(:afterId AS text))";
        };
        String order = switch (sort) {
            case "priority" -> "t.priority DESC,t.created_at ASC,t.engine_task_id ASC";
            case "oldest" -> "t.created_at ASC,t.engine_task_id ASC";
            default -> "t.created_at DESC,t.engine_task_id DESC";
        };
        String ownership = assigned ? "t.assignee_subject_id=:subject" : "t.assignee_subject_id IS NULL AND t.candidate_group_key IN (:groups) AND (t.self_approval_allowed OR t.initiator_subject_id<>:subject)";
        var query = jdbc.sql("SELECT " + T_COLUMNS + " FROM task_context t WHERE t.workspace_id=:ws AND t.status='open' AND " + ownership
                + " AND t.priority>=:minPriority AND (CAST(:afterAt AS timestamptz) IS NULL OR " + comparison + ") ORDER BY " + order + " LIMIT :limit")
                .param("ws",workspaceId).param("subject",subject).param("minPriority",minPriority)
                .param("afterAt",after == null ? null : Sql.ts(after.at())).param("afterId",id).param("limit",limit);
        if (!assigned) query.param("groups",List.copyOf(groups));
        if ("priority".equals(sort)) query.param("afterPriority",priority);
        return query.query(TaskStore::map).list();
    }

    /** Tasks the subject decided, newest decision first (cursor on decided_at). */
    @Transactional(readOnly = true)
    public List<TaskStore.TaskRow> completed(UUID workspaceId, UUID subject, CursorCodec.Position after, int limit) {
        return jdbc.sql("SELECT " + T_COLUMNS + ", d.decided_at AS sort_at" + """
                 FROM task_context t JOIN task_decision d
                   ON d.workspace_id = t.workspace_id AND d.engine_task_id = t.engine_task_id
                 WHERE t.workspace_id = :ws AND d.actor_subject_id = :subject
                   AND (CAST(:afterAt AS timestamptz) IS NULL
                        OR (d.decided_at, t.engine_task_id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS text)))
                 ORDER BY d.decided_at DESC, t.engine_task_id DESC LIMIT :limit
                """)
                .param("ws", workspaceId).param("subject", subject)
                .param("afterAt", after == null ? null : Sql.ts(after.at())).param("afterId", after == null ? null : after.id())
                .param("limit", limit).query(TaskStore::map).list();
    }

    @Transactional(readOnly = true)
    public int countAssigned(UUID workspaceId, UUID subject) {
        return jdbc.sql("SELECT count(*) FROM task_context WHERE workspace_id = :ws AND status = 'open' AND assignee_subject_id = :s")
                .param("ws", workspaceId).param("s", subject).query(Integer.class).single();
    }

    @Transactional(readOnly = true)
    public int countClaimable(UUID workspaceId, UUID subject, Collection<String> groups) {
        if (groups.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                SELECT count(*) FROM task_context
                WHERE workspace_id = :ws AND status = 'open' AND assignee_subject_id IS NULL
                  AND candidate_group_key IN (:groups) AND (self_approval_allowed OR initiator_subject_id <> :s)
                """)
                .param("ws", workspaceId).param("s", subject).param("groups", List.copyOf(groups))
                .query(Integer.class).single();
    }

    @Transactional(readOnly = true)
    public Optional<DecisionRow> decisionForTask(UUID workspaceId, String taskId) {
        return jdbc.sql("""
                SELECT id, engine_task_id, run_id, node_id, actor_subject_id, action, reason, comment,
                       policy_flags::text AS policy_flags, decided_at
                FROM task_decision WHERE workspace_id = :ws AND engine_task_id = :task
                """)
                .param("ws", workspaceId).param("task", taskId).query(this::mapDecision).optional();
    }

    @Transactional(readOnly = true)
    public List<DecisionRow> decisionsForRun(UUID workspaceId, UUID runId) {
        return jdbc.sql("""
                SELECT id, engine_task_id, run_id, node_id, actor_subject_id, action, reason, comment,
                       policy_flags::text AS policy_flags, decided_at
                FROM task_decision WHERE workspace_id = :ws AND run_id = :run ORDER BY decided_at, id
                """)
                .param("ws", workspaceId).param("run", runId).query(this::mapDecision).list();
    }

    @Transactional(readOnly = true)
    public Optional<DecisionRow> decision(UUID workspaceId, UUID decisionId) {
        return jdbc.sql("""
                SELECT id, engine_task_id, run_id, node_id, actor_subject_id, action, reason, comment,
                       policy_flags::text AS policy_flags, decided_at
                FROM task_decision WHERE workspace_id = :ws AND id = :id
                """)
                .param("ws", workspaceId).param("id", decisionId).query(this::mapDecision).optional();
    }

    /** True when the subject holds or held a task of the run, or decided one. */
    @Transactional(readOnly = true)
    public boolean heldOrDecided(UUID workspaceId, UUID runId, UUID subject) {
        return jdbc.sql("""
                SELECT (EXISTS (SELECT 1 FROM task_context WHERE workspace_id = :ws AND run_id = :run
                                AND assignee_subject_id = :s)
                        OR EXISTS (SELECT 1 FROM task_decision WHERE workspace_id = :ws AND run_id = :run
                                AND actor_subject_id = :s))
                """)
                .param("ws", workspaceId).param("run", runId).param("s", subject).query(Boolean.class).single();
    }

    /** The runs among {@code runIds} for which {@link #heldOrDecided} is true, in one query. */
    @Transactional(readOnly = true)
    public java.util.Set<UUID> heldOrDecidedAmong(UUID workspaceId, java.util.Collection<UUID> runIds, UUID subject) {
        if (runIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(jdbc.sql("""
                SELECT run_id FROM task_context WHERE workspace_id = :ws AND run_id IN (:ids) AND assignee_subject_id = :s
                UNION
                SELECT run_id FROM task_decision WHERE workspace_id = :ws AND run_id IN (:ids) AND actor_subject_id = :s
                """)
                .param("ws", workspaceId).param("ids", runIds).param("s", subject).query(UUID.class).list());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID insertDecision(UUID workspaceId, String taskId, UUID runId, String nodeId, UUID actor, String action,
            String reason, String comment, List<String> policyFlags, java.time.Instant decidedAt) {
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO task_decision (id, workspace_id, engine_task_id, run_id, node_id, actor_subject_id, action,
                                           reason, comment, policy_flags, decided_at)
                VALUES (:id, :ws, :task, :run, :node, :actor, :action, :reason, :comment, CAST(:flags AS jsonb), :at)
                ON CONFLICT (engine_task_id) DO NOTHING
                """)
                .param("id", id).param("ws", workspaceId).param("task", taskId).param("run", runId).param("node", nodeId)
                .param("actor", actor).param("action", action).param("reason", reason).param("comment", comment)
                .param("flags", json.write(policyFlags)).param("at", Sql.ts(decidedAt))
                .update();
        return inserted == 1 ? id : null;
    }

    private DecisionRow mapDecision(ResultSet rs, int n) throws SQLException {
        return new DecisionRow(Sql.uuid(rs, "id"), rs.getString("engine_task_id"), Sql.uuid(rs, "run_id"),
                rs.getString("node_id"), Sql.uuid(rs, "actor_subject_id"), rs.getString("action"),
                rs.getString("reason"), rs.getString("comment"), json.parse(rs.getString("policy_flags")),
                Sql.instant(rs, "decided_at"));
    }
}
