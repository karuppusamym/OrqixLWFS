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
            boolean assigned, CursorCodec.Position after, int limit, String sort, int minPriority, TaskFilter filter) {
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
        String ownership = assigned ? "t.assignee_subject_id=:subject" : "t.assignee_subject_id IS NULL AND t.candidate_group_key IN (:groups) AND t.required_skills <@ CAST(:skills AS jsonb) AND (t.self_approval_allowed OR t.initiator_subject_id<>:subject) AND NOT EXISTS (SELECT 1 FROM task_decision d WHERE d.workspace_id=t.workspace_id AND d.run_id=t.run_id AND d.actor_subject_id=:subject AND d.node_id IN (SELECT jsonb_array_elements_text(t.separate_from)))";
        var query = jdbc.sql("SELECT " + T_COLUMNS + " FROM task_context t" + searchJoins(filter) + " WHERE t.workspace_id=:ws AND t.status='open' AND " + ownership
                + filterSql(filter) + " AND t.priority>=:minPriority AND (CAST(:afterAt AS timestamptz) IS NULL OR " + comparison + ") ORDER BY " + order + " LIMIT :limit")
                .param("ws",workspaceId).param("subject",subject).param("minPriority",minPriority)
                .param("afterAt",after == null ? null : Sql.ts(after.at())).param("afterId",id).param("limit",limit);
        if (!assigned) query.param("groups",List.copyOf(groups)).param("skills",json.write(groups));
        if ("priority".equals(sort)) query.param("afterPriority",priority);
        bindFilter(query, filter);
        return query.query(TaskStore::map).list();
    }

    /** Tasks the subject decided, newest decision first (cursor on decided_at). */
    @Transactional(readOnly = true)
    public List<TaskStore.TaskRow> completed(UUID workspaceId, UUID subject, CursorCodec.Position after, int limit, TaskFilter filter) {
        var query = jdbc.sql("SELECT " + T_COLUMNS + ", d.decided_at AS sort_at" + """
                 FROM task_context t JOIN task_decision d
                   ON d.workspace_id = t.workspace_id AND d.engine_task_id = t.engine_task_id
                """ + searchJoins(filter) + " WHERE t.workspace_id = :ws AND d.actor_subject_id = :subject" + filterSql(filter) + """
                   AND (CAST(:afterAt AS timestamptz) IS NULL
                        OR (d.decided_at, t.engine_task_id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS text)))
                 ORDER BY d.decided_at DESC, t.engine_task_id DESC LIMIT :limit
                """)
                .param("ws", workspaceId).param("subject", subject)
                .param("afterAt", after == null ? null : Sql.ts(after.at())).param("afterId", after == null ? null : after.id())
                .param("limit", limit);
        bindFilter(query, filter);
        return query.query(TaskStore::map).list();
    }

    private static String searchJoins(TaskFilter filter) {
        if (filter.q().isEmpty()) return "";
        return " " + """
            JOIN workflow_run r ON r.workspace_id=t.workspace_id AND r.id=t.run_id
            JOIN workflow_release rel ON rel.workspace_id=r.workspace_id AND rel.id=r.release_id
            JOIN subject person ON person.id=t.initiator_subject_id
            LEFT JOIN workspace_group g ON g.workspace_id=t.workspace_id AND g.key=t.candidate_group_key
            LEFT JOIN LATERAL (
              SELECT left(btrim(r.input->>f.key),120) AS title
              FROM jsonb_each(rel.definition->'inputSchema'->'properties') WITH ORDINALITY AS f(key,value,ord)
              WHERE f.value->>'type'='string' AND NOT jsonb_exists(f.value,'enum') AND NOT jsonb_exists(f.value,'format')
                AND jsonb_typeof(r.input->f.key)='string' AND btrim(r.input->>f.key)<>''
              ORDER BY COALESCE((SELECT min(o.n) FROM jsonb_array_elements_text(COALESCE(rel.definition->'inputUi'->'order','[]'::jsonb))
                WITH ORDINALITY AS o(name,n) WHERE o.name=f.key),9223372036854775807),f.ord LIMIT 1
            ) title ON true
            """;
    }

    private static String filterSql(TaskFilter filter) {
        StringBuilder sql = new StringBuilder();
        if (!filter.queue().isEmpty()) sql.append(" AND t.candidate_group_key=:filterQueue");
        sql.append(switch (filter.due()) {
            case "overdue" -> " AND t.status='open' AND t.due_at<:filterAt";
            case "soon" -> " AND t.status='open' AND t.due_at>=:filterAt AND t.due_at<=:filterUntil";
            case "none" -> " AND t.due_at IS NULL";
            default -> "";
        });
        for (int i=0;i<filter.terms().size();i++)
            sql.append(" AND position(:term").append(i).append(" in lower(concat_ws(' ',title.title,r.reference,r.business_key,t.label,person.display_name,g.name)))>0");
        return sql.toString();
    }

    private static void bindFilter(JdbcClient.StatementSpec query, TaskFilter filter) {
        if (!filter.queue().isEmpty()) query.param("filterQueue",filter.queue());
        if (List.of("overdue","soon").contains(filter.due())) query.param("filterAt",Sql.ts(filter.at()));
        if ("soon".equals(filter.due())) query.param("filterUntil",Sql.ts(filter.at().plusSeconds(86400)));
        for (int i=0;i<filter.terms().size();i++) query.param("term"+i,filter.terms().get(i));
    }

    /** Same eligibility and filter predicates as the page, capped to bound inbox polling cost. */
    @Transactional(readOnly = true)
    public int countMatching(UUID ws, UUID subject, Collection<String> groups, String view, int minPriority, TaskFilter filter) {
        if ("claimable".equals(view) && groups.isEmpty()) return 0;
        String ownership = switch(view) {
            case "assigned" -> "t.status='open' AND t.assignee_subject_id=:subject";
            case "claimable" -> "t.status='open' AND t.assignee_subject_id IS NULL AND t.candidate_group_key IN (:groups) AND t.required_skills <@ CAST(:skills AS jsonb) AND (t.self_approval_allowed OR t.initiator_subject_id<>:subject) AND NOT EXISTS (SELECT 1 FROM task_decision d WHERE d.workspace_id=t.workspace_id AND d.run_id=t.run_id AND d.actor_subject_id=:subject AND d.node_id IN (SELECT jsonb_array_elements_text(t.separate_from)))";
            default -> "EXISTS (SELECT 1 FROM task_decision d WHERE d.workspace_id=t.workspace_id AND d.engine_task_id=t.engine_task_id AND d.actor_subject_id=:subject)";
        };
        var query = jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM task_context t " + searchJoins(filter)
            + " WHERE t.workspace_id=:ws AND " + ownership + filterSql(filter)
            + ("completed".equals(view) ? "" : " AND t.priority>=:minPriority") + " LIMIT :cap) matched")
            .param("ws",ws).param("subject",subject).param("cap",COUNT_CAP);
        if (!"completed".equals(view)) query.param("minPriority",minPriority);
        if ("claimable".equals(view)) query.param("groups",List.copyOf(groups)).param("skills",json.write(groups));
        bindFilter(query,filter);
        return query.query(Integer.class).single();
    }

    /**
     * Inbox counts are exact up to this many and stay at it beyond (S92 F16): counting every open task of a large
     * backlog on each inbox call and on each badge poll was the second-largest user of database time.
     */
    public static final int COUNT_CAP = 1000;

    @Transactional(readOnly = true)
    public int countAssigned(UUID workspaceId, UUID subject) {
        return jdbc.sql("""
                SELECT count(*) FROM (SELECT 1 FROM task_context
                WHERE workspace_id = :ws AND status = 'open' AND assignee_subject_id = :s LIMIT :cap) c
                """)
                .param("ws", workspaceId).param("s", subject).param("cap", COUNT_CAP).query(Integer.class).single();
    }

    @Transactional(readOnly = true)
    public int countClaimable(UUID workspaceId, UUID subject, Collection<String> groups) {
        if (groups.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                SELECT count(*) FROM (SELECT 1 FROM task_context t
                WHERE t.workspace_id = :ws AND t.status = 'open' AND t.assignee_subject_id IS NULL
                  AND t.candidate_group_key IN (:groups) AND (t.self_approval_allowed OR t.initiator_subject_id <> :s)
                  AND t.required_skills <@ CAST(:skills AS jsonb)
                  AND NOT EXISTS (SELECT 1 FROM task_decision d WHERE d.workspace_id = t.workspace_id AND d.run_id = t.run_id
                        AND d.actor_subject_id = :s AND d.node_id IN (SELECT jsonb_array_elements_text(t.separate_from)))
                LIMIT :cap) c
                """)
                .param("ws", workspaceId).param("s", subject).param("groups", List.copyOf(groups)).param("skills",json.write(groups))
                .param("cap", COUNT_CAP)
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
                                           reason, comment, policy_flags, decided_at, distinct_actor)
                VALUES (:id, :ws, :task, :run, :node, :actor, :action, :reason, :comment, CAST(:flags AS jsonb), :at,
                        COALESCE((SELECT t.separate_from @> to_jsonb(CAST(:node AS text)) FROM task_context t
                                  WHERE t.workspace_id = :ws AND t.engine_task_id = :task), false))
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

    // ---------------------------------------------------------------- send-backs (S116)

    @Transactional(readOnly = true)
    public boolean sendBackOpen(UUID workspaceId, String taskId) {
        return jdbc.sql("SELECT count(*) FROM task_send_back WHERE workspace_id = :ws AND task_id = :task AND status = 'open'")
                .param("ws", workspaceId).param("task", taskId).query(Long.class).single() > 0;
    }

    @Transactional(readOnly = true)
    public Optional<com.orqix.runtime.api.RuntimeDtos.SendBackOpen> openSendBack(UUID workspaceId, String taskId) {
        return jdbc.sql("""
                SELECT id, reason, fields::text AS fields, sent_at FROM task_send_back
                WHERE workspace_id = :ws AND task_id = :task AND status = 'open'
                """).param("ws", workspaceId).param("task", taskId)
                .query((rs, n) -> new com.orqix.runtime.api.RuntimeDtos.SendBackOpen(Sql.uuid(rs, "id"), rs.getString("reason"),
                        strings(rs.getString("fields")), Sql.instant(rs, "sent_at"))).optional();
    }

    private List<String> strings(String array) {
        List<String> values = new java.util.ArrayList<>();
        json.parse(array).forEach(value -> values.add(value.asString()));
        return values;
    }
}
