package com.orqix.runtime.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.web.Sql;

/** {@code task_context} projection (S2 section 5). Always resolved by (workspace, engine task id) first. */
@Repository
public class TaskStore {

    public record TaskRow(String taskId, UUID workspaceId, UUID runId, String nodeId, String label,
            String candidateGroupKey, UUID initiator, boolean selfApprovalAllowed, UUID assignee, String status,
            int version, int priority, Instant dueAt, Instant createdAt, Instant claimedAt, Instant endedAt) {

        public boolean isOpen() {
            return "open".equals(status);
        }
    }

    static final String COLUMNS = """
            engine_task_id, workspace_id, run_id, node_id, label, candidate_group_key, initiator_subject_id,
            self_approval_allowed, assignee_subject_id, status, version, priority, due_at, created_at, claimed_at, ended_at
            """;

    private final JdbcClient jdbc;
    private final TaskReminderSettings reminders;

    public TaskStore(JdbcClient jdbc, TaskReminderSettings reminders) {
        this.jdbc = jdbc;
        this.reminders = reminders;
    }

    /**
     * Inserts the projection for a new engine task. {@code initiator} and {@code self_approval_allowed} are copied from
     * the run, its release (workflow policy) and the workspace (test-only permission), so the claimable query needs no
     * join to enforce separation of duties.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(String taskId, UUID workspaceId, UUID runId, String nodeId, String label, String candidateGroup,
            Instant dueAt, Instant createdAt) {
        insert(taskId, workspaceId, runId, nodeId, label, candidateGroup, dueAt, createdAt, null, null, null, null);
    }

    /**
     * As {@link #insert(String, UUID, UUID, String, String, String, Instant, Instant)}, for a task that decides one
     * item of a list-driven approval (S88): {@code itemIndex} is its 0-based list position, {@code itemLabel} what it
     * shows for the item and {@code itemScope} the engine execution holding all item tasks of this visit of the step (all null for every other task).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(String taskId, UUID workspaceId, UUID runId, String nodeId, String label, String candidateGroup,
            Instant dueAt, Instant createdAt, Integer itemIndex, String itemLabel, String itemScope, Integer itemCount) {
        int inserted = jdbc.sql("""
                INSERT INTO task_context (engine_task_id, workspace_id, run_id, node_id, label, candidate_group_key,
                                          initiator_subject_id, self_approval_allowed, status, version, due_at, created_at, required_skills, separate_from,
                                          item_index, item_label, item_scope, item_count)
                SELECT :task, run.workspace_id, run.id, :node, :label, :grp, run.initiator_subject_id,
                       (rel.definition -> 'security' ->> 'selfApproval' = 'allow' AND ws.allow_self_approval_workflows),
                       'open', 1, :due, :created,
                       COALESCE((SELECT n->'config'->'requiredSkills' FROM jsonb_array_elements(rel.definition->'nodes') n
                           WHERE n->>'id'=:node),'[]'::jsonb),
                       COALESCE((SELECT n->'config'->'separateFrom' FROM jsonb_array_elements(rel.definition->'nodes') n
                           WHERE n->>'id'=:node),'[]'::jsonb)
                       -- Several approvers (S51) are kept apart from each other: whoever decided one task of this
                       -- step cannot claim or decide another, through the same separation machinery.
                       || COALESCE((SELECT jsonb_build_array(n->>'id') FROM jsonb_array_elements(rel.definition->'nodes') n
                           WHERE n->>'id'=:node AND (n->'config'->'multiInstance' IS NOT NULL
                               -- A list-driven approval (S88) keeps the same rule unless one person may decide several items.
                               OR (n->'config'->'perItem' IS NOT NULL
                                   AND COALESCE((n->'config'->'perItem'->>'allowSamePerson')::boolean, false) = false))),'[]'::jsonb),
                       CAST(:item AS integer), CAST(:itemLabel AS text), CAST(:itemScope AS text), CAST(:itemCount AS integer)
                FROM workflow_run run
                JOIN workflow_release rel ON rel.workspace_id = run.workspace_id AND rel.id = run.release_id
                JOIN workspace ws ON ws.id = run.workspace_id
                WHERE run.workspace_id = :ws AND run.id = :run
                """)
                .param("task", taskId).param("node", nodeId).param("label", label).param("grp", candidateGroup)
                .param("due", Sql.ts(dueAt)).param("created", Sql.ts(createdAt)).param("ws", workspaceId)
                .param("run", runId).param("item", itemIndex).param("itemLabel", itemLabel).param("itemScope", itemScope)
                .param("itemCount", itemCount)
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("Run " + runId + " not found for task " + taskId);
        }
        jdbc.sql("""
                INSERT INTO task_reminder(workspace_id,task_id,next_attempt_at,max_notices,interval_hours)
                SELECT :ws,:task,CASE WHEN cfg->'escalation' IS NOT NULL THEN CAST(:deadline AS timestamptz) ELSE CAST(:due AS timestamptz) END,
                       COALESCE((cfg->'escalation'->>'maxNotices')::integer,0),(cfg->'escalation'->>'intervalHours')::integer
                FROM (SELECT n->'config' cfg FROM workflow_run r JOIN workflow_release rel ON rel.workspace_id=r.workspace_id AND rel.id=r.release_id,
                       jsonb_array_elements(rel.definition->'nodes') n WHERE r.workspace_id=:ws AND r.id=:run AND n->>'id'=:node) config
                ON CONFLICT DO NOTHING
                """)
                .param("ws",workspaceId).param("task",taskId)
                .param("deadline",Sql.ts(dueAt)).param("run",runId).param("node",nodeId)
                .param("due",Sql.ts(createdAt.plus(reminders.after()))).update();
    }

    /**
     * The item a task of a list-driven approval decides (S88). {@code count} is the number of items of the visit
     * (S104), null for tasks created before it was recorded.
     */
    public record Item(int index, String label, String scope, Integer count) {
    }

    /** One item of one visit of a list-driven approval with its result so far ({@code action} null until decided). */
    public record ItemState(int index, String label, String status, String action) {
    }

    /** @return the item of this task, or empty for a task that is not one item of a list-driven approval */
    @Transactional(readOnly = true)
    public Optional<Item> item(UUID workspaceId, String taskId) {
        return jdbc.sql("SELECT item_index, item_label, item_scope, item_count FROM task_context WHERE workspace_id=:ws AND engine_task_id=:task AND item_index IS NOT NULL")
                .param("ws", workspaceId).param("task", taskId)
                .query((rs, n) -> new Item(rs.getInt("item_index"), rs.getString("item_label"), rs.getString("item_scope"),
                        (Integer) rs.getObject("item_count")))
                .optional();
    }

    /** Cancels the open item tasks of one visit of a list-driven approval once its outcome is settled (S104). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelOpenForScope(UUID workspaceId, String itemScope, Instant endedAt) {
        return jdbc.sql("""
                UPDATE task_context SET status = 'cancelled', ended_at = :ended, version = version + 1
                WHERE workspace_id = :ws AND item_scope = :scope AND status = 'open'
                """).param("ended", Sql.ts(endedAt)).param("ws", workspaceId).param("scope", itemScope).update();
    }

    /** Every item of one visit of a list-driven approval, in list order, with the decision taken on it so far. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ItemState> items(UUID workspaceId, String itemScope) {
        return jdbc.sql("""
                SELECT t.item_index, t.item_label, t.status,
                       (SELECT d.action FROM task_decision d WHERE d.workspace_id = t.workspace_id
                           AND d.engine_task_id = t.engine_task_id) AS action
                FROM task_context t WHERE t.workspace_id = :ws AND t.item_scope = :scope ORDER BY t.item_index
                """)
                .param("ws", workspaceId).param("scope", itemScope)
                .query((rs, n) -> new ItemState(rs.getInt("item_index"), rs.getString("item_label"), rs.getString("status"),
                        rs.getString("action")))
                .list();
    }

    /** True when {@code subject} decided one of the earlier steps this task must be kept apart from (S41). */
    @Transactional(readOnly = true)
    public boolean separatedFrom(UUID workspaceId, String taskId, UUID subject) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM task_context t JOIN task_decision d
                       ON d.workspace_id = t.workspace_id AND d.run_id = t.run_id AND d.actor_subject_id = :subject
                      AND d.node_id IN (SELECT jsonb_array_elements_text(t.separate_from))
                WHERE t.workspace_id = :ws AND t.engine_task_id = :task)
                """)
                .param("ws", workspaceId).param("task", taskId).param("subject", subject).query(Boolean.class).single());
    }

    @Transactional(readOnly = true)
    public List<String> requiredSkills(UUID workspaceId, String taskId) {
        return jdbc.sql("SELECT jsonb_array_elements_text(required_skills) skill FROM task_context WHERE workspace_id=:ws AND engine_task_id=:task")
                .param("ws",workspaceId).param("task",taskId).query(String.class).list();
    }

    @Transactional(readOnly = true)
    public Optional<TaskRow> find(UUID workspaceId, String taskId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM task_context WHERE workspace_id = :ws AND engine_task_id = :task")
                .param("ws", workspaceId).param("task", taskId).query(TaskStore::map).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TaskRow> lock(UUID workspaceId, String taskId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM task_context WHERE workspace_id = :ws AND engine_task_id = :task FOR UPDATE")
                .param("ws", workspaceId).param("task", taskId).query(TaskStore::map).optional();
    }

    /** Background reminders never wait for a task lock while holding their queue batch locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TaskRow> tryLock(UUID workspaceId, String taskId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM task_context WHERE workspace_id = :ws AND engine_task_id = :task FOR UPDATE SKIP LOCKED")
                .param("ws", workspaceId).param("task", taskId).query(TaskStore::map).optional();
    }

    @Transactional(readOnly = true)
    public List<TaskRow> forRun(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM task_context WHERE workspace_id = :ws AND run_id = :run ORDER BY created_at, engine_task_id")
                .param("ws", workspaceId).param("run", runId).query(TaskStore::map).list();
    }

    /** Open tasks of several runs in one query (list views). */
    @Transactional(readOnly = true)
    public List<TaskRow> openForRuns(UUID workspaceId, java.util.Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM task_context WHERE workspace_id = :ws AND run_id IN (:ids) AND status = 'open'")
                .param("ws", workspaceId).param("ids", runIds).query(TaskStore::map).list();
    }

    /**
     * Compare-and-set on the task version (S3 section 6). Returns false when the version no longer matches.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean setAssignee(UUID workspaceId, String taskId, int expectedVersion, UUID assignee, Instant claimedAt) {
        return jdbc.sql("""
                UPDATE task_context SET assignee_subject_id = :assignee, claimed_at = :claimed, version = version + 1
                WHERE workspace_id = :ws AND engine_task_id = :task AND version = :version AND status = 'open'
                """)
                .param("assignee", assignee).param("claimed", Sql.ts(claimedAt)).param("ws", workspaceId)
                .param("task", taskId).param("version", expectedVersion)
                .update() == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean setPriority(UUID workspaceId, String taskId, int expectedVersion, int priority) {
        return jdbc.sql("UPDATE task_context SET priority=:priority, version=version+1 WHERE workspace_id=:ws AND engine_task_id=:task AND version=:version AND status='open'")
                .param("priority", priority).param("ws", workspaceId).param("task", taskId).param("version", expectedVersion).update() == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean bumpVersion(UUID workspaceId, String taskId, int expectedVersion) {
        return jdbc.sql("""
                UPDATE task_context SET version = version + 1
                WHERE workspace_id = :ws AND engine_task_id = :task AND version = :version AND status = 'open'
                """)
                .param("ws", workspaceId).param("task", taskId).param("version", expectedVersion).update() == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void end(UUID workspaceId, String taskId, String status, Instant endedAt) {
        jdbc.sql("""
                UPDATE task_context SET status = :status, ended_at = :ended
                WHERE workspace_id = :ws AND engine_task_id = :task AND status = 'open'
                """)
                .param("status", status).param("ended", Sql.ts(endedAt)).param("ws", workspaceId).param("task", taskId)
                .update();
    }

    /** Ends the open task of one step because its time limit passed (stored as cancelled; the run event says why). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelOpenForNode(UUID workspaceId, UUID runId, String nodeId, Instant endedAt) {
        return jdbc.sql("""
                UPDATE task_context SET status = 'cancelled', ended_at = :ended, version = version + 1
                WHERE workspace_id = :ws AND run_id = :run AND node_id = :node AND status = 'open'
                """)
                .param("ended", Sql.ts(endedAt)).param("ws", workspaceId).param("run", runId).param("node", nodeId)
                .update();
    }

    /** Renames the open task of a step after the request moved to a release that words the step differently. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int relabelOpenForNode(UUID workspaceId, UUID runId, String nodeId, String label) {
        return jdbc.sql("""
                UPDATE task_context SET label = :label
                WHERE workspace_id = :ws AND run_id = :run AND node_id = :node AND status = 'open'
                """)
                .param("label", label).param("ws", workspaceId).param("run", runId).param("node", nodeId).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelOpenForRun(UUID workspaceId, UUID runId, Instant endedAt) {
        int count = jdbc.sql("""
                UPDATE task_context SET status = 'cancelled', ended_at = :ended, version = version + 1
                WHERE workspace_id = :ws AND run_id = :run AND status = 'open'
                """)
                .param("ended", Sql.ts(endedAt)).param("ws", workspaceId).param("run", runId).update();
        jdbc.sql("DELETE FROM task_reminder WHERE workspace_id=:ws AND task_id IN (SELECT engine_task_id FROM task_context WHERE workspace_id=:ws AND run_id=:run AND status<>'open')")
                .param("ws",workspaceId).param("run",runId).update();
        jdbc.sql("UPDATE stage_mail SET status='suppressed',failure_code='CASE_ENDED',updated_at=:now WHERE workspace_id=:ws AND run_id=:run AND status='queued' AND event_kind IN ('assignment','overdue')")
                .param("ws",workspaceId).param("run",runId).param("now",Sql.ts(endedAt)).update();
        return count;
    }

    static TaskRow map(ResultSet rs, int n) throws SQLException {
        return new TaskRow(rs.getString("engine_task_id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"),
                rs.getString("node_id"), rs.getString("label"), rs.getString("candidate_group_key"),
                Sql.uuid(rs, "initiator_subject_id"), rs.getBoolean("self_approval_allowed"),
                Sql.uuid(rs, "assignee_subject_id"), rs.getString("status"), rs.getInt("version"), rs.getInt("priority"),
                Sql.instant(rs, "due_at"), Sql.instant(rs, "created_at"), Sql.instant(rs, "claimed_at"),
                Sql.instant(rs, "ended_at"));
    }
}
