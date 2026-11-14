package com.orqix.analytics.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.orqix.analytics.api.AnalyticsDtos;
import com.orqix.shared.web.Sql;

/**
 * Aggregates over the reporting tables only ({@code analytics_*}, docs/build/S145), plus single-row lookups of the
 * workflow, release and group names. Every query is scoped by workspace, bounded by the window or by LIMIT, and
 * returns figures, never request data.
 */
@Repository
public class AnalyticsQueries {

    public static final int STEP_LIMIT = 200;
    public static final int GROUP_LIMIT = 200;
    public static final int KPI_LIMIT = 500;
    /** Pending updates are counted up to this many. */
    public static final int PENDING_CAP = 10_000;

    public record Window(UUID workspaceId, UUID workflowId, Instant from, Instant to) {
    }

    /** Figures of one workflow with targets, over runs and tasks that ended in the window. */
    public record KpiRow(AnalyticsDtos.WorkflowRef workflow, AnalyticsDtos.KpiTargets targets, int completed,
            Double cycleP50, Double cycleP95, int automated, double excessSeconds, int slaMet, int slaMissed) {
    }

    private final JdbcClient jdbc;

    public AnalyticsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean workflowExists(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id = :ws AND id = :wf")
                .param("ws", workspaceId).param("wf", workflowId).query(Integer.class).single() > 0;
    }

    public boolean releaseExists(UUID workspaceId, UUID workflowId, UUID releaseId) {
        return jdbc.sql("SELECT count(*) FROM workflow_release WHERE workspace_id = :ws AND workflow_id = :wf AND id = :rel")
                .param("ws", workspaceId).param("wf", workflowId).param("rel", releaseId).query(Integer.class).single() > 0;
    }

    public AnalyticsDtos.Freshness freshness(UUID workspaceId) {
        Instant last = jdbc.sql("SELECT max(projected_at) FROM analytics_run WHERE workspace_id = :ws")
                .param("ws", workspaceId).query((rs, n) -> Sql.instant(rs, "max")).single();
        int pending = jdbc.sql("""
                SELECT count(*) FROM (SELECT 1 FROM analytics_outbox WHERE workspace_id = :ws AND NOT dead LIMIT :cap) p
                """).param("ws", workspaceId).param("cap", PENDING_CAP).query(Integer.class).single();
        return new AnalyticsDtos.Freshness(last, pending);
    }

    public AnalyticsDtos.Status status(UUID workspaceId) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM (SELECT 1 FROM analytics_outbox
                         WHERE workspace_id = :ws AND NOT dead LIMIT :cap) p) AS pending,
                       (SELECT count(*) FROM (SELECT 1 FROM analytics_outbox
                         WHERE workspace_id = :ws AND dead LIMIT :cap) d) AS failed,
                       (SELECT min(created_at) FROM analytics_outbox WHERE workspace_id = :ws AND NOT dead) AS oldest,
                       (SELECT max(projected_at) FROM analytics_run WHERE workspace_id = :ws) AS last,
                       (SELECT count(*) FROM analytics_run WHERE workspace_id = :ws) AS runs
                """).param("ws", workspaceId).param("cap", PENDING_CAP)
                .query((rs, n) -> new AnalyticsDtos.Status(rs.getInt("pending"), rs.getInt("failed"),
                        Sql.instant(rs, "oldest"), Sql.instant(rs, "last"), rs.getInt("runs")))
                .single();
    }

    /** Runs of the workflow (and release) started in the window, and how many of them ended. */
    public int[] runCounts(Window w, UUID releaseId) {
        return jdbc.sql("""
                SELECT count(*) AS runs, count(*) FILTER (WHERE ended_at IS NOT NULL) AS finished
                FROM analytics_run
                WHERE workspace_id = :ws AND workflow_id = :wf AND started_at >= :from AND started_at < :to
                  AND (CAST(:rel AS uuid) IS NULL OR release_id = :rel)
                """).param("ws", w.workspaceId()).param("wf", w.workflowId()).param("rel", releaseId)
                .param("from", Sql.ts(w.from())).param("to", Sql.ts(w.to()))
                .query((rs, n) -> new int[] {rs.getInt("runs"), rs.getInt("finished")}).single();
    }

    public List<AnalyticsDtos.HeatStep> heatSteps(Window w, UUID releaseId) {
        return jdbc.sql("""
                SELECT node_id, (array_agg(kind ORDER BY run_started_at DESC))[1] AS kind, count(*) AS visits,
                       count(DISTINCT run_id) AS runs, avg(duration_seconds) AS average,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_seconds) AS p95, max(duration_seconds) AS longest
                FROM analytics_step_visit
                WHERE workspace_id = :ws AND workflow_id = :wf AND run_started_at >= :from AND run_started_at < :to
                  AND (CAST(:rel AS uuid) IS NULL OR release_id = :rel)
                GROUP BY node_id
                ORDER BY visits DESC, node_id
                LIMIT :limit
                """).param("ws", w.workspaceId()).param("wf", w.workflowId()).param("rel", releaseId)
                .param("from", Sql.ts(w.from())).param("to", Sql.ts(w.to())).param("limit", STEP_LIMIT * 5)
                .query((rs, n) -> new AnalyticsDtos.HeatStep(rs.getString("node_id"), rs.getString("kind"),
                        rs.getInt("visits"), rs.getInt("runs"), seconds(rs, "average"), seconds(rs, "p95"),
                        seconds(rs, "longest")))
                .list();
    }

    public List<AnalyticsDtos.HeatRoute> heatRoutes(Window w, UUID releaseId, int runs) {
        return jdbc.sql("""
                SELECT transition_id, count(*) AS runs
                FROM analytics_route
                WHERE workspace_id = :ws AND workflow_id = :wf AND run_started_at >= :from AND run_started_at < :to
                  AND (CAST(:rel AS uuid) IS NULL OR release_id = :rel)
                GROUP BY transition_id
                ORDER BY runs DESC, transition_id
                LIMIT :limit
                """).param("ws", w.workspaceId()).param("wf", w.workflowId()).param("rel", releaseId)
                .param("from", Sql.ts(w.from())).param("to", Sql.ts(w.to())).param("limit", STEP_LIMIT * 5)
                .query((rs, n) -> new AnalyticsDtos.HeatRoute(rs.getString("transition_id"), rs.getInt("runs"),
                        share(rs.getInt("runs"), runs)))
                .list();
    }

    /** Human tasks that ended in the window, per workflow, step and group. */
    public List<AnalyticsDtos.HumanStep> humanSteps(Window w) {
        return jdbc.sql("""
                SELECT t.workflow_id, wf.key AS workflow_key, wf.name AS workflow_name, t.node_id, t.group_key,
                       (array_agg(t.label ORDER BY t.ended_at DESC))[1] AS label, g.name AS group_name,
                       count(*) AS finished,
                       avg(t.waiting_seconds) AS avg_waiting,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY t.waiting_seconds) AS p95_waiting,
                       avg(t.working_seconds) AS avg_working,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY t.working_seconds) AS p95_working,
                       count(*) FILTER (WHERE t.sla = 'met') AS sla_met, count(*) FILTER (WHERE t.sla = 'missed') AS sla_missed,
                       coalesce(sum(t.reassignments), 0) AS reassignments, count(*) FILTER (WHERE t.approximate) AS approximate
                FROM analytics_task t
                     JOIN workflow wf ON wf.workspace_id = t.workspace_id AND wf.id = t.workflow_id
                     LEFT JOIN workspace_group g ON g.workspace_id = t.workspace_id AND g.key = t.group_key
                WHERE t.workspace_id = :ws AND t.ended_at >= :from AND t.ended_at < :to
                  AND (CAST(:wf AS uuid) IS NULL OR t.workflow_id = :wf)
                GROUP BY t.workflow_id, wf.key, wf.name, t.node_id, t.group_key, g.name
                ORDER BY avg_waiting DESC NULLS LAST, finished DESC, t.node_id
                LIMIT :limit
                """).param("ws", w.workspaceId()).param("wf", w.workflowId()).param("from", Sql.ts(w.from()))
                .param("to", Sql.ts(w.to())).param("limit", STEP_LIMIT + 1)
                .query((rs, n) -> {
                    int met = rs.getInt("sla_met");
                    int missed = rs.getInt("sla_missed");
                    return new AnalyticsDtos.HumanStep(workflow(rs), rs.getString("node_id"), rs.getString("label"),
                            rs.getString("group_key"), rs.getString("group_name"), rs.getInt("finished"),
                            seconds(rs, "avg_waiting"), seconds(rs, "p95_waiting"), seconds(rs, "avg_working"),
                            seconds(rs, "p95_working"), met, missed, attainment(met, missed), rs.getInt("reassignments"),
                            rs.getInt("approximate"));
                })
                .list();
    }

    /**
     * Load and figures per group: open now (whatever their age, from the projected open tasks) and the tasks that
     * ended in the window.
     */
    public List<AnalyticsDtos.HumanGroup> humanGroups(Window w, Instant now) {
        return jdbc.sql("""
                WITH fin AS (
                    SELECT group_key, count(*) AS finished, avg(waiting_seconds) AS avg_waiting,
                           avg(working_seconds) AS avg_working,
                           count(*) FILTER (WHERE sla = 'met') AS sla_met, count(*) FILTER (WHERE sla = 'missed') AS sla_missed,
                           coalesce(sum(reassignments), 0) AS reassignments
                    FROM analytics_task
                    WHERE workspace_id = :ws AND ended_at >= :from AND ended_at < :to
                      AND (CAST(:wf AS uuid) IS NULL OR workflow_id = :wf)
                    GROUP BY group_key
                ), open AS (
                    SELECT group_key, count(*) AS open_now, count(*) FILTER (WHERE NOT assigned) AS open_unassigned,
                           min(created_at) AS oldest
                    FROM analytics_task
                    WHERE workspace_id = :ws AND status = 'open' AND (CAST(:wf AS uuid) IS NULL OR workflow_id = :wf)
                    GROUP BY group_key
                )
                SELECT coalesce(f.group_key, o.group_key) AS group_key, g.name AS group_name,
                       coalesce(o.open_now, 0) AS open_now, coalesce(o.open_unassigned, 0) AS open_unassigned,
                       EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - o.oldest)) AS oldest_seconds,
                       coalesce(f.finished, 0) AS finished, f.avg_waiting, f.avg_working,
                       coalesce(f.sla_met, 0) AS sla_met, coalesce(f.sla_missed, 0) AS sla_missed,
                       coalesce(f.reassignments, 0) AS reassignments
                FROM fin f FULL JOIN open o ON o.group_key = f.group_key
                     LEFT JOIN workspace_group g ON g.workspace_id = :ws AND g.key = coalesce(f.group_key, o.group_key)
                ORDER BY open_now DESC, finished DESC, group_key
                LIMIT :limit
                """).param("ws", w.workspaceId()).param("wf", w.workflowId()).param("from", Sql.ts(w.from()))
                .param("to", Sql.ts(w.to())).param("now", Sql.ts(now)).param("limit", GROUP_LIMIT)
                .query((rs, n) -> {
                    int met = rs.getInt("sla_met");
                    int missed = rs.getInt("sla_missed");
                    return new AnalyticsDtos.HumanGroup(rs.getString("group_key"), rs.getString("group_name"),
                            rs.getInt("open_now"), rs.getInt("open_unassigned"), seconds(rs, "oldest_seconds"),
                            rs.getInt("finished"), seconds(rs, "avg_waiting"), seconds(rs, "avg_working"), met, missed,
                            attainment(met, missed), rs.getInt("reassignments"));
                })
                .list();
    }

    public AnalyticsDtos.KpiTargets targets(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("""
                SELECT workflow_id, cycle_p50_seconds, cycle_p95_seconds, automation_rate, sla_attainment,
                       delay_cost_per_hour, currency, version, updated_at
                FROM analytics_kpi_target WHERE workspace_id = :ws AND workflow_id = :wf
                """).param("ws", workspaceId).param("wf", workflowId).query((rs, n) -> targets(rs)).optional()
                .orElse(new AnalyticsDtos.KpiTargets(workflowId, null, null, null, null, null, null, 0, null));
    }

    /** Workspaces that have targets, for the evaluator. */
    public List<UUID> workspacesWithTargets() {
        return jdbc.sql("SELECT DISTINCT workspace_id FROM analytics_kpi_target ORDER BY workspace_id")
                .query((rs, n) -> Sql.uuid(rs, "workspace_id")).list();
    }

    /** Workflows with targets and their figures over completed runs and finished tasks of the window. */
    public List<KpiRow> kpis(Window w) {
        Map<UUID, KpiRow> rows = new LinkedHashMap<>();
        jdbc.sql("""
                WITH runs AS (
                    SELECT r.workflow_id, count(*) AS completed,
                           percentile_cont(0.5) WITHIN GROUP (ORDER BY r.cycle_seconds) AS p50,
                           percentile_cont(0.95) WITHIN GROUP (ORDER BY r.cycle_seconds) AS p95,
                           count(*) FILTER (WHERE r.automated) AS automated,
                           coalesce(sum(greatest(0, r.cycle_seconds
                               - coalesce(t.cycle_p95_seconds, t.cycle_p50_seconds)))
                               FILTER (WHERE coalesce(t.cycle_p95_seconds, t.cycle_p50_seconds) IS NOT NULL), 0) AS excess
                    FROM analytics_run r
                         JOIN analytics_kpi_target t ON t.workspace_id = r.workspace_id AND t.workflow_id = r.workflow_id
                    WHERE r.workspace_id = :ws AND r.lifecycle = 'completed' AND r.ended_at >= :from AND r.ended_at < :to
                      AND (CAST(:wf AS uuid) IS NULL OR r.workflow_id = :wf)
                    GROUP BY r.workflow_id
                ), sla AS (
                    SELECT k.workflow_id, count(*) FILTER (WHERE k.sla = 'met') AS met,
                           count(*) FILTER (WHERE k.sla = 'missed') AS missed
                    FROM analytics_task k
                         JOIN analytics_kpi_target t ON t.workspace_id = k.workspace_id AND t.workflow_id = k.workflow_id
                    WHERE k.workspace_id = :ws AND k.ended_at >= :from AND k.ended_at < :to AND k.sla IS NOT NULL
                      AND (CAST(:wf AS uuid) IS NULL OR k.workflow_id = :wf)
                    GROUP BY k.workflow_id
                )
                SELECT t.workflow_id, wf.key AS workflow_key, wf.name AS workflow_name, t.cycle_p50_seconds,
                       t.cycle_p95_seconds, t.automation_rate, t.sla_attainment, t.delay_cost_per_hour, t.currency,
                       t.version, t.updated_at, coalesce(r.completed, 0) AS completed, r.p50, r.p95,
                       coalesce(r.automated, 0) AS automated, coalesce(r.excess, 0) AS excess,
                       coalesce(s.met, 0) AS met, coalesce(s.missed, 0) AS missed
                FROM analytics_kpi_target t
                     JOIN workflow wf ON wf.workspace_id = t.workspace_id AND wf.id = t.workflow_id
                     LEFT JOIN runs r ON r.workflow_id = t.workflow_id
                     LEFT JOIN sla s ON s.workflow_id = t.workflow_id
                WHERE t.workspace_id = :ws AND (CAST(:wf AS uuid) IS NULL OR t.workflow_id = :wf)
                ORDER BY wf.name, t.workflow_id
                LIMIT :limit
                """).param("ws", w.workspaceId()).param("wf", w.workflowId()).param("from", Sql.ts(w.from()))
                .param("to", Sql.ts(w.to())).param("limit", KPI_LIMIT)
                .query((rs, n) -> {
                    rows.put(Sql.uuid(rs, "workflow_id"), new KpiRow(workflow(rs), targets(rs), rs.getInt("completed"),
                            doubleOrNull(rs, "p50"), doubleOrNull(rs, "p95"), rs.getInt("automated"),
                            rs.getDouble("excess"), rs.getInt("met"), rs.getInt("missed")));
                    return null;
                }).list();
        return new ArrayList<>(rows.values());
    }

    private static AnalyticsDtos.KpiTargets targets(ResultSet rs) throws SQLException {
        return new AnalyticsDtos.KpiTargets(Sql.uuid(rs, "workflow_id"), longOrNull(rs, "cycle_p50_seconds"),
                longOrNull(rs, "cycle_p95_seconds"), rate(rs.getBigDecimal("automation_rate")),
                rate(rs.getBigDecimal("sla_attainment")), money(rs.getBigDecimal("delay_cost_per_hour")),
                rs.getString("currency"), rs.getInt("version"), Sql.instant(rs, "updated_at"));
    }

    private static BigDecimal rate(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().scale() < 0 ? v.setScale(0) : v.stripTrailingZeros();
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static AnalyticsDtos.WorkflowRef workflow(ResultSet rs) throws SQLException {
        return new AnalyticsDtos.WorkflowRef(Sql.uuid(rs, "workflow_id"), rs.getString("workflow_key"),
                rs.getString("workflow_name"));
    }

    static Double attainment(int met, int missed) {
        return met + missed == 0 ? null : Math.round(met * 10000.0 / (met + missed)) / 10000.0;
    }

    static double share(int part, int whole) {
        return whole <= 0 ? 0 : Math.min(1.0, Math.round(part * 10000.0 / whole) / 10000.0);
    }

    private static Long seconds(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : Math.max(0, Math.round(value));
    }

    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
