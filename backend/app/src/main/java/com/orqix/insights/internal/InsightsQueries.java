package com.orqix.insights.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.orqix.insights.api.InsightsDtos;
import com.orqix.shared.web.Sql;

/**
 * Aggregate queries over the projection tables (workflow_run, task_context, run_event). Every query is scoped by
 * workspace and optionally by workflow, selects no request data (no input, output or titles), and is bounded by LIMITs
 * or by the date window. Percentiles are computed by PostgreSQL ({@code percentile_cont}).
 */
@Repository
public class InsightsQueries {

    /** What to look at: workspace, optional workflow, runs started in [from, to). */
    public record Window(UUID workspaceId, UUID workflowId, Instant from, Instant to) {
    }

    /** One row of the per-step table before the two sources (approvals, waits) are merged. */
    public record StepRow(InsightsDtos.WorkflowRef workflow, String nodeId, String label, String kind, int finished,
            Long averageSeconds, Long p90Seconds, int timeouts) {
    }

    public static final int TOP = 10;
    public static final int OVERDUE_LIMIT = 20;
    public static final int STEP_LIMIT = 200;

    private final JdbcClient jdbc;

    public InsightsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean workflowExists(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id = :ws AND id = :wf")
                .param("ws", workspaceId).param("wf", workflowId).query(Integer.class).single() > 0;
    }

    public InsightsDtos.Totals totals(Window w) {
        return jdbc.sql("""
                SELECT count(*) AS started,
                       count(*) FILTER (WHERE lifecycle = 'completed') AS completed,
                       count(*) FILTER (WHERE lifecycle = 'completed' AND outcome_kind = 'success') AS fulfilled,
                       count(*) FILTER (WHERE lifecycle = 'completed' AND outcome_kind = 'rejected') AS rejected,
                       count(*) FILTER (WHERE lifecycle = 'cancelled') AS cancelled,
                       count(*) FILTER (WHERE lifecycle IN ('running', 'waiting', 'needsAttention')) AS open,
                       count(*) FILTER (WHERE lifecycle = 'needsAttention') AS needs_attention
                FROM workflow_run
                WHERE workspace_id = :ws AND created_at >= :from AND created_at < :to
                  AND (CAST(:wf AS uuid) IS NULL OR workflow_id = :wf)
                """)
                .param("ws", w.workspaceId()).param("wf", w.workflowId()).param("from", Sql.ts(w.from()))
                .param("to", Sql.ts(w.to()))
                .query((rs, n) -> new InsightsDtos.Totals(rs.getInt("started"), rs.getInt("completed"),
                        rs.getInt("fulfilled"), rs.getInt("rejected"), rs.getInt("cancelled"), rs.getInt("open"),
                        rs.getInt("needs_attention")))
                .single();
    }

    public List<InsightsDtos.CycleTime> cycleTimes(Window w) {
        return jdbc.sql("""
                SELECT r.workflow_id, w.key AS workflow_key, w.name AS workflow_name, count(*) AS n,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (r.ended_at - r.created_at))) AS median,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM (r.ended_at - r.created_at))) AS p90
                FROM workflow_run r JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                WHERE r.workspace_id = :ws AND r.created_at >= :from AND r.created_at < :to
                  AND r.lifecycle = 'completed' AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                GROUP BY r.workflow_id, w.key, w.name
                ORDER BY n DESC, w.name
                LIMIT 100
                """)
                .param("ws", w.workspaceId()).param("wf", w.workflowId()).param("from", Sql.ts(w.from()))
                .param("to", Sql.ts(w.to()))
                .query((rs, n) -> new InsightsDtos.CycleTime(workflow(rs), rs.getInt("n"), seconds(rs, "median"),
                        seconds(rs, "p90")))
                .list();
    }

    /** Open requests of the workspace (not only those started in the window), oldest first. */
    public List<InsightsDtos.OpenRun> oldestOpen(UUID workspaceId, UUID workflowId, Instant now) {
        return jdbc.sql("""
                SELECT r.id, r.reference, r.workflow_id, w.key AS workflow_key, w.name AS workflow_name, r.lifecycle,
                       r.created_at, r.current_steps -> 0 ->> 'label' AS step_label
                FROM workflow_run r JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                WHERE r.workspace_id = :ws AND r.lifecycle IN ('running', 'waiting', 'needsAttention')
                  AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                ORDER BY r.created_at, r.id
                LIMIT :limit
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("limit", TOP)
                .query((rs, n) -> {
                    Instant started = Sql.instant(rs, "created_at");
                    return new InsightsDtos.OpenRun(Sql.uuid(rs, "id"), rs.getString("reference"), workflow(rs),
                            rs.getString("lifecycle"), started, Math.max(0, now.getEpochSecond() - started.getEpochSecond()),
                            rs.getString("step_label"));
                })
                .list();
    }

    public List<InsightsDtos.AttentionRun> needsAttention(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("""
                SELECT r.id, r.reference, r.workflow_id, w.key AS workflow_key, w.name AS workflow_name,
                       r.attention ->> 'reason' AS reason, r.attention ->> 'since' AS since,
                       (SELECT s ->> 'label' FROM jsonb_array_elements(r.current_steps) s
                         WHERE s ->> 'nodeId' = r.attention ->> 'nodeId' LIMIT 1) AS step_label
                FROM workflow_run r JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                WHERE r.workspace_id = :ws AND r.lifecycle = 'needsAttention'
                  AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                ORDER BY r.updated_at DESC, r.id
                LIMIT :limit
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("limit", TOP)
                .query((rs, n) -> new InsightsDtos.AttentionRun(Sql.uuid(rs, "id"), rs.getString("reference"), workflow(rs),
                        rs.getString("reason"), Instant.parse(rs.getString("since")), rs.getString("step_label")))
                .list();
    }

    /** Started and completed requests per UTC day, zero-filled for every day of the window. */
    public List<InsightsDtos.Day> daily(Window w, LocalDate from, LocalDate to) {
        Map<LocalDate, int[]> days = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.put(d, new int[4]);
        }
        jdbc.sql("""
                SELECT (created_at AT TIME ZONE 'UTC')::date AS day, count(*) AS n
                FROM workflow_run
                WHERE workspace_id = :ws AND created_at >= :from AND created_at < :to
                  AND (CAST(:wf AS uuid) IS NULL OR workflow_id = :wf)
                GROUP BY 1
                """)
                .param("ws", w.workspaceId()).param("wf", w.workflowId()).param("from", Sql.ts(w.from()))
                .param("to", Sql.ts(w.to()))
                .query((rs, n) -> {
                    int[] d = days.get(rs.getObject("day", LocalDate.class));
                    if (d != null) {
                        d[0] = rs.getInt("n");
                    }
                    return null;
                }).list();
        jdbc.sql("""
                SELECT (ended_at AT TIME ZONE 'UTC')::date AS day, count(*) AS n,
                       count(*) FILTER (WHERE outcome_kind = 'success') AS fulfilled,
                       count(*) FILTER (WHERE outcome_kind = 'rejected') AS rejected
                FROM workflow_run
                WHERE workspace_id = :ws AND lifecycle = 'completed' AND ended_at >= :from AND ended_at < :to
                  AND (CAST(:wf AS uuid) IS NULL OR workflow_id = :wf)
                GROUP BY 1
                """)
                .param("ws", w.workspaceId()).param("wf", w.workflowId()).param("from", Sql.ts(w.from()))
                .param("to", Sql.ts(w.to()))
                .query((rs, n) -> {
                    int[] d = days.get(rs.getObject("day", LocalDate.class));
                    if (d != null) {
                        d[1] = rs.getInt("n");
                        d[2] = rs.getInt("fulfilled");
                        d[3] = rs.getInt("rejected");
                    }
                    return null;
                }).list();
        List<InsightsDtos.Day> result = new ArrayList<>();
        days.forEach((day, v) -> result.add(new InsightsDtos.Day(day, v[0], v[1], v[2], v[3])));
        return result;
    }

    /**
     * Approvals that finished (decided, or ended by their time limit) among those created in the window. An approval
     * that was cancelled for another reason (the request was cancelled or ended elsewhere) is not a wait and is left
     * out. A task counts as timed out when the run recorded TASK_TIMED_OUT for its step while the task was open.
     */
    public List<StepRow> approvalSteps(Window w) {
        return jdbc.sql("""
                WITH tmo AS MATERIALIZED (
                    SELECT run_id, node_id, occurred_at FROM run_event
                    WHERE workspace_id = :ws AND type = 'TASK_TIMED_OUT' AND occurred_at >= :from
                ), tasks AS MATERIALIZED (
                    SELECT t.engine_task_id, t.run_id, r.workflow_id, t.node_id, t.label, t.status, t.created_at, t.ended_at,
                           extract(epoch FROM (t.ended_at - t.created_at)) AS secs
                    FROM task_context t JOIN workflow_run r ON r.workspace_id = t.workspace_id AND r.id = t.run_id
                    WHERE t.workspace_id = :ws AND t.created_at >= :from AND t.created_at < :to AND t.status <> 'open'
                      AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                ), timed AS MATERIALIZED (
                    SELECT DISTINCT x.engine_task_id FROM tasks x
                    JOIN tmo e ON e.run_id = x.run_id AND e.node_id = x.node_id
                              AND e.occurred_at >= x.created_at AND e.occurred_at <= x.ended_at + interval '5 seconds'
                    WHERE x.status = 'cancelled'
                ), fin AS (
                    SELECT x.*, (x.engine_task_id IN (SELECT engine_task_id FROM timed)) AS timed_out FROM tasks x
                )
                SELECT f.workflow_id, w.key AS workflow_key, w.name AS workflow_name, f.node_id,
                       (array_agg(f.label ORDER BY f.created_at DESC))[1] AS label,
                       count(*) FILTER (WHERE f.status = 'completed' OR f.timed_out) AS finished,
                       avg(f.secs) FILTER (WHERE f.status = 'completed' OR f.timed_out) AS average,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY f.secs) FILTER (WHERE f.status = 'completed' OR f.timed_out) AS p90,
                       count(*) FILTER (WHERE f.timed_out) AS timeouts
                FROM fin f JOIN workflow w ON w.workspace_id = :ws AND w.id = f.workflow_id
                GROUP BY f.workflow_id, w.key, w.name, f.node_id
                HAVING count(*) FILTER (WHERE f.status = 'completed' OR f.timed_out) > 0
                ORDER BY average DESC NULLS LAST
                LIMIT :limit
                """)
                .param("ws", w.workspaceId()).param("wf", w.workflowId()).param("from", Sql.ts(w.from()))
                .param("to", Sql.ts(w.to())).param("limit", STEP_LIMIT + 1)
                .query((rs, n) -> new StepRow(workflow(rs), rs.getString("node_id"), rs.getString("label"), "approval",
                        rs.getInt("finished"), secondsOrNull(rs, "average"), secondsOrNull(rs, "p90"),
                        rs.getInt("timeouts")))
                .list();
    }

    /** Waits that ended, from the WAIT_STARTED / WAIT_ENDED pair of the same step in the same run. */
    public List<StepRow> waitSteps(Window w) {
        return jdbc.sql("""
                WITH w AS (
                    SELECT e.run_id, e.node_id, e.type, e.occurred_at, e.data ->> 'label' AS label,
                           lead(e.type) OVER (PARTITION BY e.run_id, e.node_id ORDER BY e.seq) AS next_type,
                           lead(e.occurred_at) OVER (PARTITION BY e.run_id, e.node_id ORDER BY e.seq) AS next_at,
                           lead(e.data ->> 'outcome') OVER (PARTITION BY e.run_id, e.node_id ORDER BY e.seq) AS next_outcome
                    FROM run_event e
                    WHERE e.workspace_id = :ws AND e.type IN ('WAIT_STARTED', 'WAIT_ENDED') AND e.occurred_at >= :from
                )
                SELECT r.workflow_id, wf.key AS workflow_key, wf.name AS workflow_name, w.node_id,
                       (array_agg(w.label ORDER BY w.occurred_at DESC))[1] AS label,
                       count(*) AS finished, count(*) FILTER (WHERE w.next_outcome = 'timedOut') AS timeouts,
                       avg(extract(epoch FROM (w.next_at - w.occurred_at))) AS average,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM (w.next_at - w.occurred_at))) AS p90
                FROM w JOIN workflow_run r ON r.workspace_id = :ws AND r.id = w.run_id
                       JOIN workflow wf ON wf.workspace_id = :ws AND wf.id = r.workflow_id
                WHERE w.type = 'WAIT_STARTED' AND w.next_type = 'WAIT_ENDED' AND w.occurred_at < :to
                  AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                GROUP BY r.workflow_id, wf.key, wf.name, w.node_id
                ORDER BY average DESC NULLS LAST
                LIMIT :limit
                """)
                .param("ws", w.workspaceId()).param("wf", w.workflowId()).param("from", Sql.ts(w.from()))
                .param("to", Sql.ts(w.to())).param("limit", STEP_LIMIT + 1)
                .query((rs, n) -> new StepRow(workflow(rs), rs.getString("node_id"), rs.getString("label"), "wait",
                        rs.getInt("finished"), secondsOrNull(rs, "average"), secondsOrNull(rs, "p90"), rs.getInt("timeouts")))
                .list();
    }

    /** Requests waiting at an approval right now, by workflow and step (all open tasks, whatever their age). */
    public Map<String, Integer> openApprovals(UUID workspaceId, UUID workflowId) {
        return openCounts("""
                SELECT r.workflow_id, t.node_id, count(*) AS n
                FROM task_context t JOIN workflow_run r ON r.workspace_id = t.workspace_id AND r.id = t.run_id
                WHERE t.workspace_id = :ws AND t.status = 'open' AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                GROUP BY r.workflow_id, t.node_id
                """, workspaceId, workflowId);
    }

    /** Requests waiting at a wait step right now. */
    public Map<String, Integer> openWaits(UUID workspaceId, UUID workflowId) {
        return openCounts("""
                SELECT r.workflow_id, s ->> 'nodeId' AS node_id, count(*) AS n
                FROM workflow_run r, jsonb_array_elements(r.current_steps) s
                WHERE r.workspace_id = :ws AND r.lifecycle IN ('running', 'waiting', 'needsAttention')
                  AND s ->> 'kind' = 'wait' AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                GROUP BY r.workflow_id, s ->> 'nodeId'
                """, workspaceId, workflowId);
    }

    private Map<String, Integer> openCounts(String sql, UUID workspaceId, UUID workflowId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.sql(sql).param("ws", workspaceId).param("wf", workflowId)
                .query((rs, n) -> {
                    counts.put(key(Sql.uuid(rs, "workflow_id"), rs.getString("node_id")), rs.getInt("n"));
                    return null;
                }).list();
        return counts;
    }

    public static String key(UUID workflowId, String nodeId) {
        return workflowId + "|" + nodeId;
    }

    /** Workflow and label of steps that are open right now but finished nothing in the window (so a row can still show them). */
    public List<StepRow> openOnlySteps(UUID workspaceId, UUID workflowId) {
        List<StepRow> rows = new ArrayList<>();
        jdbc.sql("""
                SELECT r.workflow_id, w.key AS workflow_key, w.name AS workflow_name, t.node_id,
                       (array_agg(t.label ORDER BY t.created_at DESC))[1] AS label
                FROM task_context t JOIN workflow_run r ON r.workspace_id = t.workspace_id AND r.id = t.run_id
                     JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                WHERE t.workspace_id = :ws AND t.status = 'open' AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                GROUP BY r.workflow_id, w.key, w.name, t.node_id
                LIMIT :limit
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("limit", STEP_LIMIT)
                .query((rs, n) -> rows.add(new StepRow(workflow(rs), rs.getString("node_id"), rs.getString("label"),
                        "approval", 0, null, null, 0)))
                .list();
        jdbc.sql("""
                SELECT r.workflow_id, w.key AS workflow_key, w.name AS workflow_name, s ->> 'nodeId' AS node_id,
                       (array_agg(s ->> 'label'))[1] AS label
                FROM workflow_run r JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id,
                     jsonb_array_elements(r.current_steps) s
                WHERE r.workspace_id = :ws AND r.lifecycle IN ('running', 'waiting', 'needsAttention')
                  AND s ->> 'kind' = 'wait' AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                GROUP BY r.workflow_id, w.key, w.name, s ->> 'nodeId'
                LIMIT :limit
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("limit", STEP_LIMIT)
                .query((rs, n) -> rows.add(new StepRow(workflow(rs), rs.getString("node_id"), rs.getString("label"),
                        "wait", 0, null, null, 0)))
                .list();
        return rows;
    }

    /** Open approvals whose due date has passed, most overdue first, and how many there are. */
    public List<InsightsDtos.Overdue> overdueApprovals(UUID workspaceId, UUID workflowId, Instant now) {
        return jdbc.sql("""
                SELECT r.id, r.reference, r.workflow_id, w.key AS workflow_key, w.name AS workflow_name, t.label, t.due_at
                FROM task_context t JOIN workflow_run r ON r.workspace_id = t.workspace_id AND r.id = t.run_id
                     JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                WHERE t.workspace_id = :ws AND t.status = 'open' AND t.due_at < :now
                  AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                ORDER BY t.due_at, t.engine_task_id
                LIMIT :limit
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("now", Sql.ts(now)).param("limit", OVERDUE_LIMIT)
                .query((rs, n) -> overdue(rs, "approval", Sql.instant(rs, "due_at"), now))
                .list();
    }

    public int countOverdueApprovals(UUID workspaceId, UUID workflowId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM task_context t JOIN workflow_run r ON r.workspace_id = t.workspace_id AND r.id = t.run_id
                WHERE t.workspace_id = :ws AND t.status = 'open' AND t.due_at < :now
                  AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("now", Sql.ts(now)).query(Integer.class).single();
    }

    /** Waits whose end time has passed while the request still sits at the step. */
    public List<InsightsDtos.Overdue> overdueWaits(UUID workspaceId, UUID workflowId, Instant now) {
        return jdbc.sql("""
                SELECT r.id, r.reference, r.workflow_id, w.key AS workflow_key, w.name AS workflow_name,
                       s ->> 'label' AS label, (s ->> 'dueAt')::timestamptz AS due_at
                FROM workflow_run r JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id,
                     jsonb_array_elements(r.current_steps) s
                WHERE r.workspace_id = :ws AND r.lifecycle IN ('running', 'waiting', 'needsAttention')
                  AND s ->> 'kind' = 'wait' AND (s ->> 'dueAt') IS NOT NULL AND (s ->> 'dueAt')::timestamptz < :now
                  AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                ORDER BY due_at, r.id
                LIMIT :limit
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("now", Sql.ts(now)).param("limit", OVERDUE_LIMIT)
                .query((rs, n) -> overdue(rs, "wait", Sql.instant(rs, "due_at"), now))
                .list();
    }

    public int countOverdueWaits(UUID workspaceId, UUID workflowId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM workflow_run r, jsonb_array_elements(r.current_steps) s
                WHERE r.workspace_id = :ws AND r.lifecycle IN ('running', 'waiting', 'needsAttention')
                  AND s ->> 'kind' = 'wait' AND (s ->> 'dueAt') IS NOT NULL AND (s ->> 'dueAt')::timestamptz < :now
                  AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("now", Sql.ts(now)).query(Integer.class).single();
    }

    private static InsightsDtos.Overdue overdue(ResultSet rs, String kind, Instant due, Instant now) throws SQLException {
        return new InsightsDtos.Overdue(Sql.uuid(rs, "id"), rs.getString("reference"), workflow(rs), kind,
                rs.getString("label"), due, Math.max(1, now.getEpochSecond() - due.getEpochSecond()));
    }

    private static InsightsDtos.WorkflowRef workflow(ResultSet rs) throws SQLException {
        return new InsightsDtos.WorkflowRef(Sql.uuid(rs, "workflow_id"), rs.getString("workflow_key"),
                rs.getString("workflow_name"));
    }

    private static long seconds(ResultSet rs, String column) throws SQLException {
        return Math.max(0, Math.round(rs.getDouble(column)));
    }

    private static Long secondsOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : Math.max(0, Math.round(value));
    }
}
