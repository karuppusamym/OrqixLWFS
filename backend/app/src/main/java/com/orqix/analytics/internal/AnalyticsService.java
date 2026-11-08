package com.orqix.analytics.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.analytics.api.AnalyticsDtos;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/**
 * Analytics reads and KPI targets (docs/build/S145). Order of checks as for Insights (S24): workspace visible, then the
 * operation's permission, then parameters, then aggregates over the reporting tables in one read-only transaction with
 * a statement timeout. Reads need {@code run.read}; targets are workflow settings ({@code workflow.read} to see,
 * {@code workflow.edit} to change); a backfill re-reads run timelines and needs {@code run.operate}.
 */
@Service
public class AnalyticsService {

    public static final int MAX_DAYS = 365;
    public static final int DEFAULT_DAYS = 30;
    /** A measure with fewer samples than this is {@code insufficientData}, neither on nor off target. */
    public static final int MIN_SAMPLES = 5;
    static final String STATEMENT_TIMEOUT = "10s";
    /** Longest cycle time target: 366 days. */
    static final long MAX_TARGET_SECONDS = 31_622_400L;

    private final PolicyService policy;
    private final AnalyticsQueries queries;
    private final JdbcClient jdbc;
    private final AuditWriter audit;
    private final Clock clock;

    public AnalyticsService(PolicyService policy, AnalyticsQueries queries, JdbcClient jdbc, AuditWriter audit,
            Clock clock) {
        this.policy = policy;
        this.queries = queries;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    record Params(AnalyticsQueries.Window window, LocalDate from, LocalDate to, Instant now) {
        AnalyticsDtos.Range range() {
            return new AnalyticsDtos.Range(from, to, (int) ChronoUnit.DAYS.between(from, to) + 1, now);
        }
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.Heatmap heatmap(Caller caller, UUID workspaceId, UUID workflowId, UUID releaseId, String from,
            String to) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_READ, "getAnalyticsHeatmap", false);
        if (workflowId == null) {
            throw ApiException.malformed("workflowId is required.");
        }
        Params p = params(workspaceId, workflowId, from, to);
        if (releaseId != null && !queries.releaseExists(workspaceId, workflowId, releaseId)) {
            throw ApiException.notFound();
        }
        return bounded(() -> {
            int[] counts = queries.runCounts(p.window(), releaseId);
            return new AnalyticsDtos.Heatmap(p.range(), workflowId, releaseId, counts[0], counts[1],
                    queries.heatSteps(p.window(), releaseId), queries.heatRoutes(p.window(), releaseId, counts[0]),
                    queries.freshness(workspaceId));
        });
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.HumanWork humanWork(Caller caller, UUID workspaceId, UUID workflowId, String from, String to) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_READ, "getHumanWorkAnalytics", false);
        Params p = params(workspaceId, workflowId, from, to);
        return bounded(() -> {
            List<AnalyticsDtos.HumanStep> steps = queries.humanSteps(p.window());
            boolean truncated = steps.size() > AnalyticsQueries.STEP_LIMIT;
            return new AnalyticsDtos.HumanWork(p.range(), truncated ? steps.subList(0, AnalyticsQueries.STEP_LIMIT) : steps,
                    queries.humanGroups(p.window(), p.now()), truncated, queries.freshness(workspaceId));
        });
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.KpiOverview kpiOverview(Caller caller, UUID workspaceId, UUID workflowId, String from, String to) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_READ, "getKpiOverview", false);
        Params p = params(workspaceId, workflowId, from, to);
        return bounded(() -> new AnalyticsDtos.KpiOverview(p.range(), evaluate(queries.kpis(p.window())),
                queries.freshness(workspaceId)));
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.Status status(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_READ, "getAnalyticsStatus", false);
        return queries.status(workspaceId);
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.KpiTargets targets(Caller caller, UUID workspaceId, UUID workflowId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_READ, "getWorkflowKpiTargets", false);
        if (!queries.workflowExists(workspaceId, workflowId)) {
            throw ApiException.notFound();
        }
        return queries.targets(workspaceId, workflowId);
    }

    /**
     * Sets every target at once (an absent target is cleared); all targets absent removes the row. Optimistic:
     * {@code expectedVersion} is the version read (0 when none was set).
     */
    @Transactional
    public AnalyticsDtos.KpiTargets setTargets(Caller caller, UUID workspaceId, UUID workflowId,
            AnalyticsDtos.SetKpiTargets body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!queries.workflowExists(workspaceId, workflowId)) {
            throw ApiException.notFound();
        }
        policy.require(access, Permission.WORKFLOW_EDIT, "setWorkflowKpiTargets", true);
        validate(body);
        AnalyticsDtos.KpiTargets current = queries.targets(workspaceId, workflowId);
        if (body.expectedVersion() != current.version()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The targets changed since you loaded them.").with("currentVersion", current.version());
        }
        Instant now = clock.instant();
        boolean empty = body.cycleP50Seconds() == null && body.cycleP95Seconds() == null && body.automationRate() == null
                && body.slaAttainment() == null && body.delayCostPerHour() == null;
        if (empty) {
            jdbc.sql("DELETE FROM analytics_kpi_target WHERE workspace_id = :ws AND workflow_id = :wf")
                    .param("ws", workspaceId).param("wf", workflowId).update();
        } else {
            jdbc.sql("""
                    INSERT INTO analytics_kpi_target (workspace_id, workflow_id, cycle_p50_seconds, cycle_p95_seconds,
                                                      automation_rate, sla_attainment, delay_cost_per_hour, currency,
                                                      version, updated_by, updated_at)
                    VALUES (:ws, :wf, :p50, :p95, :auto, :sla, :cost, :currency, 1, :by, :now)
                    ON CONFLICT (workspace_id, workflow_id) DO UPDATE SET
                        cycle_p50_seconds = EXCLUDED.cycle_p50_seconds, cycle_p95_seconds = EXCLUDED.cycle_p95_seconds,
                        automation_rate = EXCLUDED.automation_rate, sla_attainment = EXCLUDED.sla_attainment,
                        delay_cost_per_hour = EXCLUDED.delay_cost_per_hour, currency = EXCLUDED.currency,
                        version = analytics_kpi_target.version + 1, updated_by = EXCLUDED.updated_by,
                        updated_at = EXCLUDED.updated_at
                    """)
                    .param("ws", workspaceId).param("wf", workflowId).param("p50", body.cycleP50Seconds())
                    .param("p95", body.cycleP95Seconds()).param("auto", body.automationRate())
                    .param("sla", body.slaAttainment()).param("cost", body.delayCostPerHour())
                    .param("currency", body.delayCostPerHour() == null ? null : body.currency())
                    .param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
            // States of measures that no longer have a target are dropped, so a later target starts fresh.
            jdbc.sql("""
                    DELETE FROM analytics_kpi_state s USING analytics_kpi_target t
                    WHERE s.workspace_id = :ws AND s.workflow_id = :wf AND t.workspace_id = s.workspace_id
                      AND t.workflow_id = s.workflow_id
                      AND ((s.measure = 'cycleP50' AND t.cycle_p50_seconds IS NULL)
                        OR (s.measure = 'cycleP95' AND t.cycle_p95_seconds IS NULL)
                        OR (s.measure = 'automationRate' AND t.automation_rate IS NULL)
                        OR (s.measure = 'slaAttainment' AND t.sla_attainment IS NULL))
                    """).param("ws", workspaceId).param("wf", workflowId).update();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflowId.toString());
        metadata.put("cleared", empty);
        metadata.put("targets", targetNames(body));
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "analytics.kpi-targets.updated", "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return queries.targets(workspaceId, workflowId);
    }

    /**
     * Queues every run of the workspace (optionally only runs started on or after {@code since}) for projection; a run
     * that already waits for projection is not queued twice. Restart-safe: the queue is the outbox table.
     */
    @Transactional
    public AnalyticsDtos.Backfill backfill(Caller caller, UUID workspaceId, AnalyticsDtos.BackfillRequest body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_OPERATE, "startAnalyticsBackfill", true);
        LocalDate since = body == null ? null : body.since();
        if (since != null && (since.getYear() < 1 || since.getYear() > 9999)) {
            throw ApiException.malformed("since must have a year between 0001 and 9999.");
        }
        Instant now = clock.instant();
        int enqueued = jdbc.sql("""
                INSERT INTO analytics_outbox (workspace_id, run_id, reason, created_at, next_attempt_at)
                SELECT r.workspace_id, r.id, 'backfill', :now, :now FROM workflow_run r
                WHERE r.workspace_id = :ws AND (CAST(:since AS timestamptz) IS NULL OR r.created_at >= :since)
                  AND NOT EXISTS (SELECT 1 FROM analytics_outbox o WHERE o.run_id = r.id AND NOT o.dead)
                """)
                .param("ws", workspaceId).param("now", Sql.ts(now))
                .param("since", since == null ? null : Sql.ts(since.atStartOfDay().toInstant(ZoneOffset.UTC)))
                .update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("enqueued", enqueued);
        if (since != null) {
            metadata.put("since", since.toString());
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "analytics.backfill.requested", "workspace", workspaceId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new AnalyticsDtos.Backfill(enqueued);
    }

    /** Measures, gaps and business value per workflow; off-target workflows first, the costliest first. */
    static List<AnalyticsDtos.KpiWorkflow> evaluate(List<AnalyticsQueries.KpiRow> rows) {
        List<AnalyticsDtos.KpiWorkflow> items = new ArrayList<>();
        for (AnalyticsQueries.KpiRow r : rows) {
            List<AnalyticsDtos.KpiMeasure> measures = measures(r);
            int off = (int) measures.stream().filter(m -> "offTarget".equals(m.status())).count();
            double excessHours = Math.round(r.excessSeconds() / 36.0) / 100.0;
            BigDecimal cost = r.targets().delayCostPerHour() == null ? null
                    : r.targets().delayCostPerHour().multiply(BigDecimal.valueOf(r.excessSeconds() / 3600.0))
                            .setScale(2, RoundingMode.HALF_UP);
            items.add(new AnalyticsDtos.KpiWorkflow(r.workflow(), r.targets(), measures, off, r.completed(), excessHours,
                    cost, r.targets().currency()));
        }
        items.sort(Comparator.comparing((AnalyticsDtos.KpiWorkflow k) -> k.offTarget() == 0)
                .thenComparing(k -> k.delayCost() == null ? BigDecimal.ZERO : k.delayCost(), Comparator.reverseOrder())
                .thenComparing(AnalyticsDtos.KpiWorkflow::excessHours, Comparator.reverseOrder())
                .thenComparing(AnalyticsDtos.KpiWorkflow::offTarget, Comparator.reverseOrder())
                .thenComparing(k -> k.workflow().name()));
        return items;
    }

    static List<AnalyticsDtos.KpiMeasure> measures(AnalyticsQueries.KpiRow r) {
        List<AnalyticsDtos.KpiMeasure> out = new ArrayList<>();
        AnalyticsDtos.KpiTargets t = r.targets();
        if (t.cycleP50Seconds() != null) {
            out.add(lowerIsBetter("cycleP50", t.cycleP50Seconds(), r.cycleP50(), r.completed()));
        }
        if (t.cycleP95Seconds() != null) {
            out.add(lowerIsBetter("cycleP95", t.cycleP95Seconds(), r.cycleP95(), r.completed()));
        }
        if (t.automationRate() != null) {
            Double actual = r.completed() == 0 ? null : AnalyticsQueries.share(r.automated(), r.completed());
            out.add(higherIsBetter("automationRate", t.automationRate().doubleValue(), actual, r.completed()));
        }
        if (t.slaAttainment() != null) {
            out.add(higherIsBetter("slaAttainment", t.slaAttainment().doubleValue(),
                    AnalyticsQueries.attainment(r.slaMet(), r.slaMissed()), r.slaMet() + r.slaMissed()));
        }
        return out;
    }

    private static AnalyticsDtos.KpiMeasure lowerIsBetter(String measure, double target, Double actual, int samples) {
        Double rounded = actual == null ? null : (double) Math.round(actual);
        if (rounded == null || samples < MIN_SAMPLES) {
            return new AnalyticsDtos.KpiMeasure(measure, target, rounded, samples, "insufficientData", null);
        }
        boolean on = rounded <= target;
        return new AnalyticsDtos.KpiMeasure(measure, target, rounded, samples, on ? "onTarget" : "offTarget",
                on ? 0.0 : rounded - target);
    }

    private static AnalyticsDtos.KpiMeasure higherIsBetter(String measure, double target, Double actual, int samples) {
        if (actual == null || samples < MIN_SAMPLES) {
            return new AnalyticsDtos.KpiMeasure(measure, target, actual, samples, "insufficientData", null);
        }
        boolean on = actual >= target;
        return new AnalyticsDtos.KpiMeasure(measure, target, actual, samples, on ? "onTarget" : "offTarget",
                on ? 0.0 : Math.round((target - actual) * 10000.0) / 10000.0);
    }

    private static List<String> targetNames(AnalyticsDtos.SetKpiTargets b) {
        List<String> names = new ArrayList<>();
        if (b.cycleP50Seconds() != null) {
            names.add("cycleP50");
        }
        if (b.cycleP95Seconds() != null) {
            names.add("cycleP95");
        }
        if (b.automationRate() != null) {
            names.add("automationRate");
        }
        if (b.slaAttainment() != null) {
            names.add("slaAttainment");
        }
        if (b.delayCostPerHour() != null) {
            names.add("delayCost");
        }
        return names;
    }

    private static void validate(AnalyticsDtos.SetKpiTargets b) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (b.expectedVersion() == null || b.expectedVersion() < 0) {
            errors.put("expectedVersion", "Give the version you loaded (0 when no targets were set).");
        }
        checkSeconds(errors, "cycleP50Seconds", b.cycleP50Seconds());
        checkSeconds(errors, "cycleP95Seconds", b.cycleP95Seconds());
        if (b.cycleP50Seconds() != null && b.cycleP95Seconds() != null && b.cycleP50Seconds() > b.cycleP95Seconds()) {
            errors.put("cycleP95Seconds", "The 95th percentile target cannot be shorter than the median target.");
        }
        checkRate(errors, "automationRate", b.automationRate());
        checkRate(errors, "slaAttainment", b.slaAttainment());
        if (b.delayCostPerHour() != null) {
            if (b.delayCostPerHour().signum() < 0 || b.delayCostPerHour().scale() > 2
                    || b.delayCostPerHour().compareTo(new BigDecimal("999999999999.99")) > 0) {
                errors.put("delayCostPerHour", "Give an amount of 0 or more with at most two decimals.");
            }
            if (b.currency() == null || !b.currency().matches("[A-Z]{3}")) {
                errors.put("currency", "Give a three-letter currency code such as EUR.");
            }
        }
        if (!errors.isEmpty()) {
            List<ApiException.FieldError> list = new ArrayList<>();
            errors.forEach((field, message) -> list.add(new ApiException.FieldError("/" + field, "invalid", message)));
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some targets are not valid.").withFieldErrors(list);
        }
    }

    private static void checkSeconds(Map<String, String> errors, String field, Long value) {
        if (value != null && (value < 1 || value > MAX_TARGET_SECONDS)) {
            errors.put(field, "Give a time between 1 second and 366 days.");
        }
    }

    private static void checkRate(Map<String, String> errors, String field, BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0 || value.scale() > 4)) {
            errors.put(field, "Give a share between 0 and 1 with at most four decimals.");
        }
    }

    Params params(UUID workspaceId, UUID workflowId, String from, String to) {
        Instant now = clock.instant();
        LocalDate end = date(to, "to", now.atZone(ZoneOffset.UTC).toLocalDate());
        LocalDate start = date(from, "from", end.minusDays(DEFAULT_DAYS - 1L));
        if (start.isAfter(end)) {
            throw ApiException.malformed("from must not be after to.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw ApiException.malformed("The date range may be at most " + MAX_DAYS + " days.");
        }
        if (workflowId != null && !queries.workflowExists(workspaceId, workflowId)) {
            throw ApiException.notFound();
        }
        return new Params(new AnalyticsQueries.Window(workspaceId, workflowId,
                start.atStartOfDay().toInstant(ZoneOffset.UTC), end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)),
                start, end, now);
    }

    private static LocalDate date(String value, String name, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1 || parsed.getYear() > 9999) {
                throw ApiException.malformed(name + " must have a year between 0001 and 9999.");
            }
            return parsed;
        } catch (DateTimeParseException e) {
            throw ApiException.malformed(name + " must be a date such as 2026-10-01.");
        }
    }

    <T> T bounded(Supplier<T> work) {
        jdbc.sql("SET LOCAL statement_timeout = '" + STATEMENT_TIMEOUT + "'").update();
        try {
            return work.get();
        } catch (QueryTimeoutException e) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE,
                    "The figures took too long to calculate. Try a shorter date range or a single workflow.")
                    .withHeader("Retry-After", "30");
        }
    }
}
