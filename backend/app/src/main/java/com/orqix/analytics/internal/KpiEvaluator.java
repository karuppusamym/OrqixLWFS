package com.orqix.analytics.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.analytics.KpiAlertHook;
import com.orqix.analytics.api.AnalyticsDtos;
import com.orqix.shared.web.Sql;

/**
 * Evaluates every workflow's KPI targets over the trailing {@value #WINDOW_DAYS} days (CG-N03, docs/build/S145),
 * stores each measure's state and calls the {@link KpiAlertHook} beans for each measure whose state changed. One
 * replica evaluates at a time (transaction advisory lock); hooks run after the states are committed.
 */
@Component
public class KpiEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(KpiEvaluator.class);
    public static final int WINDOW_DAYS = 30;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AnalyticsQueries queries;
    private final ObjectProvider<KpiAlertHook> hooks;
    private final Clock clock;

    public KpiEvaluator(JdbcClient jdbc, TransactionTemplate transactions, AnalyticsQueries queries,
            ObjectProvider<KpiAlertHook> hooks, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.queries = queries;
        this.hooks = hooks;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.analytics.kpi-check-interval:15m}",
            initialDelayString = "${orqix.analytics.kpi-check-interval:15m}")
    public void tick() {
        try {
            evaluate();
        } catch (RuntimeException e) {
            LOG.warn("KPI evaluation failed; it runs again later ({})", e.getClass().getSimpleName());
        }
    }

    /** One evaluation of every workspace (also called by tests). @return the state changes passed to the hooks */
    public List<KpiAlertHook.KpiStatusChange> evaluate() {
        List<KpiAlertHook.KpiStatusChange> changes = transactions.execute(status -> {
            boolean locked = Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended('analytics:kpi', 0))")
                    .query(Boolean.class).single());
            if (!locked) {
                return List.<KpiAlertHook.KpiStatusChange>of();
            }
            Instant now = clock.instant();
            LocalDate end = now.atZone(ZoneOffset.UTC).toLocalDate();
            Instant from = end.minusDays(WINDOW_DAYS - 1L).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant to = end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            List<KpiAlertHook.KpiStatusChange> found = new ArrayList<>();
            for (UUID workspaceId : queries.workspacesWithTargets()) {
                Map<String, String> previous = previous(workspaceId);
                for (AnalyticsDtos.KpiWorkflow item : AnalyticsService.evaluate(
                        queries.kpis(new AnalyticsQueries.Window(workspaceId, null, from, to)))) {
                    for (AnalyticsDtos.KpiMeasure m : item.measures()) {
                        String key = item.workflow().id() + "|" + m.measure();
                        String before = previous.get(key);
                        store(workspaceId, item.workflow().id(), m, now, !m.status().equals(before));
                        if (!m.status().equals(before)) {
                            found.add(new KpiAlertHook.KpiStatusChange(workspaceId, item.workflow().id(),
                                    item.workflow().key(), m.measure(), before, m.status(), m.actual(), m.target(),
                                    m.samples(), WINDOW_DAYS, now));
                        }
                    }
                }
            }
            return found;
        });
        List<KpiAlertHook.KpiStatusChange> result = changes == null ? List.of() : changes;
        for (KpiAlertHook.KpiStatusChange change : result) {
            hooks.orderedStream().forEach(hook -> {
                try {
                    hook.kpiStatusChanged(change);
                } catch (RuntimeException e) {
                    LOG.warn("A KPI alert hook failed ({})", e.getClass().getSimpleName());
                }
            });
        }
        return result;
    }

    private Map<String, String> previous(UUID workspaceId) {
        Map<String, String> states = new HashMap<>();
        jdbc.sql("SELECT workflow_id, measure, status FROM analytics_kpi_state WHERE workspace_id = :ws")
                .param("ws", workspaceId)
                .query((rs, n) -> states.put(Sql.uuid(rs, "workflow_id") + "|" + rs.getString("measure"),
                        rs.getString("status")))
                .list();
        return states;
    }

    private void store(UUID workspaceId, UUID workflowId, AnalyticsDtos.KpiMeasure m, Instant now, boolean changed) {
        jdbc.sql("""
                INSERT INTO analytics_kpi_state (workspace_id, workflow_id, measure, status, actual, target, samples,
                                                 evaluated_at, changed_at)
                VALUES (:ws, :wf, :measure, :status, :actual, :target, :samples, :now, :now)
                ON CONFLICT (workspace_id, workflow_id, measure) DO UPDATE SET
                    status = EXCLUDED.status, actual = EXCLUDED.actual, target = EXCLUDED.target,
                    samples = EXCLUDED.samples, evaluated_at = EXCLUDED.evaluated_at,
                    changed_at = CASE WHEN :changed THEN EXCLUDED.changed_at ELSE analytics_kpi_state.changed_at END
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("measure", m.measure()).param("status", m.status())
                .param("actual", m.actual()).param("target", m.target()).param("samples", m.samples())
                .param("now", Sql.ts(now)).param("changed", changed).update();
    }
}
