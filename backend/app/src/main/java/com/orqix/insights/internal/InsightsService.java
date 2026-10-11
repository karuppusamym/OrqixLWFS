package com.orqix.insights.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.insights.api.InsightsDtos;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * Workflow analytics. Authorization first (workspace visible, then {@code run.read}), then parameter validation, then
 * aggregate queries inside one read-only transaction with a statement timeout. Nothing is cached: the figures are
 * computed from the projection tables on every call, so they always follow the current access rules and data.
 */
@Service
public class InsightsService {

    /** The longest range a caller may ask for. */
    public static final int MAX_DAYS = 365;
    public static final int DEFAULT_DAYS = 30;
    /** Per-statement time limit; a slower aggregate fails with 503 instead of holding a connection. */
    static final String STATEMENT_TIMEOUT = "10s";

    private final PolicyService policy;
    private final InsightsQueries queries;
    private final JdbcClient jdbc;
    private final Clock clock;

    public InsightsService(PolicyService policy, InsightsQueries queries, JdbcClient jdbc, Clock clock) {
        this.policy = policy;
        this.queries = queries;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A validated request: window of whole UTC days and the optional workflow. */
    record Params(InsightsQueries.Window window, LocalDate from, LocalDate to, Instant now) {
        InsightsDtos.Range range() {
            return new InsightsDtos.Range(from, to, (int) ChronoUnit.DAYS.between(from, to) + 1, now);
        }

        UUID workspaceId() {
            return window.workspaceId();
        }

        UUID workflowId() {
            return window.workflowId();
        }
    }

    @Transactional(readOnly = true)
    public InsightsDtos.Summary summary(Caller caller, UUID workspaceId, String from, String to, UUID workflowId) {
        Params p = params(caller, workspaceId, from, to, workflowId, "getInsightsSummary");
        return bounded(() -> {
            List<InsightsQueries.StepRow> approvals = queries.approvalSteps(p.window());
            List<InsightsDtos.Timeout> timeouts = approvals.stream().filter(r -> r.timeouts() > 0)
                    .map(r -> new InsightsDtos.Timeout(r.workflow(), r.nodeId(), r.label(), r.timeouts(), r.finished(),
                            share(r.timeouts(), r.finished())))
                    .sorted(Comparator.comparingDouble(InsightsDtos.Timeout::share).reversed()
                            .thenComparing(Comparator.comparingInt(InsightsDtos.Timeout::timeouts).reversed()))
                    .limit(50).toList();
            List<InsightsDtos.Overdue> overdue = new ArrayList<>(queries.overdueApprovals(workspaceId, workflowId, p.now()));
            overdue.addAll(queries.overdueWaits(workspaceId, workflowId, p.now()));
            overdue.sort(Comparator.comparingLong(InsightsDtos.Overdue::overdueSeconds).reversed());
            int overdueCount = queries.countOverdueApprovals(workspaceId, workflowId, p.now())
                    + queries.countOverdueWaits(workspaceId, workflowId, p.now());
            return new InsightsDtos.Summary(p.range(), queries.totals(p.window()), queries.cycleTimes(p.window()),
                    queries.oldestOpen(workspaceId, workflowId, p.now()), queries.needsAttention(workspaceId, workflowId),
                    new InsightsDtos.Sla(timeouts,
                            overdue.size() > InsightsQueries.OVERDUE_LIMIT ? overdue.subList(0, InsightsQueries.OVERDUE_LIMIT)
                                    : overdue,
                            overdueCount));
        });
    }

    @Transactional(readOnly = true)
    public InsightsDtos.Throughput throughput(Caller caller, UUID workspaceId, String from, String to, UUID workflowId) {
        Params p = params(caller, workspaceId, from, to, workflowId, "getInsightsThroughput");
        return bounded(() -> new InsightsDtos.Throughput(p.range(), queries.daily(p.window(), p.from(), p.to())));
    }

    @Transactional(readOnly = true)
    public InsightsDtos.Steps steps(Caller caller, UUID workspaceId, String from, String to, UUID workflowId) {
        Params p = params(caller, workspaceId, from, to, workflowId, "getInsightsSteps");
        return bounded(() -> {
            Map<String, InsightsDtos.Step> merged = new HashMap<>();
            Map<String, Integer> openApprovals = queries.openApprovals(workspaceId, workflowId);
            Map<String, Integer> openWaits = queries.openWaits(workspaceId, workflowId);
            for (InsightsQueries.StepRow r : queries.approvalSteps(p.window())) {
                add(merged, r, openApprovals);
            }
            for (InsightsQueries.StepRow r : queries.waitSteps(p.window())) {
                add(merged, r, openWaits);
            }
            // A step with requests waiting now but nothing finished in the window is still a bottleneck candidate.
            for (InsightsQueries.StepRow r : queries.openOnlySteps(workspaceId, workflowId)) {
                String key = r.kind() + "|" + InsightsQueries.key(r.workflow().id(), r.nodeId());
                if (!merged.containsKey(key)) {
                    add(merged, r, "wait".equals(r.kind()) ? openWaits : openApprovals);
                }
            }
            List<InsightsDtos.Step> sorted = new ArrayList<>(merged.values());
            sorted.sort(Comparator.comparing(InsightsDtos.Step::averageWaitSeconds,
                    Comparator.nullsLast(Comparator.<Long>reverseOrder()))
                    .thenComparing(Comparator.comparingInt(InsightsDtos.Step::openNow).reversed())
                    .thenComparing(s -> s.workflow().name()).thenComparing(InsightsDtos.Step::label));
            boolean truncated = sorted.size() > InsightsQueries.STEP_LIMIT;
            return new InsightsDtos.Steps(p.range(),
                    truncated ? sorted.subList(0, InsightsQueries.STEP_LIMIT) : sorted, truncated);
        });
    }

    private static void add(Map<String, InsightsDtos.Step> merged, InsightsQueries.StepRow r, Map<String, Integer> open) {
        int openNow = open.getOrDefault(InsightsQueries.key(r.workflow().id(), r.nodeId()), 0);
        merged.put(r.kind() + "|" + InsightsQueries.key(r.workflow().id(), r.nodeId()),
                new InsightsDtos.Step(r.workflow(), r.nodeId(), r.label() == null ? r.nodeId() : r.label(), r.kind(),
                        r.finished(), openNow, r.averageSeconds(), r.p90Seconds(), r.timeouts(),
                        share(r.timeouts(), r.finished())));
    }

    static double share(int part, int whole) {
        return whole <= 0 ? 0 : Math.min(1.0, Math.round(part * 10000.0 / whole) / 10000.0);
    }

    /** Access, then parameters; so a caller without access never learns whether parameters were valid. */
    Params params(Caller caller, UUID workspaceId, String from, String to, UUID workflowId, String operationId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_READ, operationId, false);
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
        InsightsQueries.Window window = new InsightsQueries.Window(workspaceId, workflowId,
                start.atStartOfDay().toInstant(ZoneOffset.UTC), end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        return new Params(window, start, end, now);
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

    /** Runs the aggregates under a statement timeout; a slow aggregate becomes 503 instead of a stuck request. */
    <T> T bounded(Supplier<T> work) {
        jdbc.sql("SET LOCAL statement_timeout = '" + STATEMENT_TIMEOUT + "'").update();
        return guard(work);
    }

    static <T> T guard(Supplier<T> work) {
        try {
            return work.get();
        } catch (QueryTimeoutException e) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE,
                    "The figures took too long to calculate. Try a shorter date range or a single workflow.")
                    .withHeader("Retry-After", "30");
        }
    }
}
