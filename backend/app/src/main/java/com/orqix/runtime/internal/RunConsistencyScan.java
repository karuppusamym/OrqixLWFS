package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EX09 periodic consistency scan (docs/build/S72). Opt-in ({@code orqix.watchdog.consistency-scan.enabled}), bounded
 * (at most {@code batch-size}, hard cap {@value #MAX_BATCH}, requests per pass) and rate-limited (at most one pass per
 * {@code interval} on an instance). Healthy requests are looked at again only after {@code recheck-after}, findings
 * after {@code finding-recheck-after}, so the oldest-first sweep never keeps re-inspecting healthy waiting requests.
 * It only records findings: it never repairs, cancels, notifies or changes a request (no version bump, no event),
 * and the engine is read, never touched. Repair stays an operator action with its own permission and audit.
 */
@Component
@Profile("!migrate")
public class RunConsistencyScan {
    public static final int MAX_BATCH = 25;
    private static final Logger LOG = LoggerFactory.getLogger(RunConsistencyScan.class);
    private static final Duration SETTLE = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RunStore runs;
    private final RunRepair repair;
    private final ConsistencyFindings findings;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final Duration interval;
    private final Duration recheckAfter;
    private final Duration findingRecheckAfter;
    private final AtomicReference<Instant> lastPass = new AtomicReference<>();

    public RunConsistencyScan(JdbcClient jdbc, TransactionTemplate transactions, RunStore runs, RunRepair repair,
            ConsistencyFindings findings, Clock clock,
            @Value("${orqix.watchdog.consistency-scan.enabled:false}") boolean enabled,
            @Value("${orqix.watchdog.consistency-scan.batch-size:10}") int batchSize,
            @Value("${orqix.watchdog.consistency-scan.interval:5m}") Duration interval,
            @Value("${orqix.watchdog.consistency-scan.recheck-after:6h}") Duration recheckAfter,
            @Value("${orqix.watchdog.consistency-scan.finding-recheck-after:30m}") Duration findingRecheckAfter) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.runs = runs;
        this.repair = repair;
        this.findings = findings;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, MAX_BATCH));
        this.interval = interval;
        this.recheckAfter = recheckAfter;
        this.findingRecheckAfter = findingRecheckAfter;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Called by the watchdog sweep: does nothing unless enabled and the minimum gap since the last pass has elapsed. */
    public int sweep() {
        if (!enabled) {
            return 0;
        }
        Instant now = clock.instant();
        Instant previous = lastPass.get();
        if (previous != null && Duration.between(previous, now).compareTo(interval) < 0) {
            return 0;
        }
        lastPass.set(now);
        try {
            return scan();
        } catch (RuntimeException e) {
            LOG.warn("Consistency scan will retry ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** One bounded pass, regardless of the enabled flag and the rate limit (used by tests and by {@link #sweep}). */
    public int scan() {
        Instant now = clock.instant();
        List<UUID[]> due = findings.due(now.minus(SETTLE), now.minus(recheckAfter), now.minus(findingRecheckAfter),
                batchSize);
        int checked = 0;
        for (UUID[] key : due) {
            Boolean done = transactions.execute(status -> inspect(key[0], key[1]));
            if (Boolean.TRUE.equals(done)) {
                checked++;
            }
        }
        transactions.executeWithoutResult(status -> findings.purgeEnded(200));
        return checked;
    }

    /** Locks the request without waiting (a busy request is looked at next time), compares and records. */
    private boolean inspect(UUID workspaceId, UUID runId) {
        boolean locked = jdbc.sql("""
                SELECT id FROM workflow_run WHERE workspace_id=:ws AND id=:id AND lifecycle IN ('running','waiting')
                FOR UPDATE SKIP LOCKED
                """).param("ws", workspaceId).param("id", runId).query(UUID.class).optional().isPresent();
        if (!locked) {
            return false;
        }
        var run = runs.find(workspaceId, runId).orElse(null);
        if (run == null) {
            return false;
        }
        var diagnosis = repair.diagnose(run).diagnosis();
        String state = RunRepair.PROTECTED.equals(diagnosis.state()) || RunRepair.ENDED.equals(diagnosis.state())
                ? RunRepair.IN_SYNC : diagnosis.state();
        findings.record(workspaceId, runId, state, diagnosis.repairable(), diagnosis.message(), run.version(),
                clock.instant());
        return true;
    }
}
