package com.orqix.operations.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.runtime.RunRetention;
import com.orqix.shared.web.Sql;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * CG-P04 (docs/build/S144): batch purge of engine history. Only process instances recorded on a purged archive are
 * candidates, so history of requests that still exist (or were never archived) is never touched. Each batch of
 * {@code orqix.retention.history-batch} instances is one transaction; rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so several nodes can run it. An instance the engine still runs is left pending and
 * reported.
 */
@Service
public class HistoryPurger {

    static final int MAX_BATCHES_PER_RUN = 20;

    public record Result(int purged, int skipped, int batches) {
    }

    private record Pending(UUID id, UUID workspaceId, String processInstanceId) {
    }

    private final JdbcClient jdbc;
    private final RunRetention runs;
    private final RetentionSettings settings;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Counter purgedCounter;

    public HistoryPurger(JdbcClient jdbc, RunRetention runs, RetentionSettings settings, AuditWriter audit,
            PlatformTransactionManager txm, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.settings = settings;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
        this.purgedCounter = Counter.builder("orqix_retention_history_purged")
                .description("Engine process instances whose history was purged after archiving").register(meters);
    }

    /** Purges pending engine history in batches until none is left or the per-run cap is reached. */
    public Result purgePending(Instant now) {
        int purged = 0;
        int skipped = 0;
        int batches = 0;
        Set<UUID> seen = new HashSet<>();
        while (batches < MAX_BATCHES_PER_RUN) {
            int[] batch = tx.execute(s -> batch(now, seen));
            if (batch == null || batch[0] + batch[1] == 0) {
                break;
            }
            batches++;
            purged += batch[0];
            skipped += batch[1];
            if (batch[0] == 0) {
                break;
            }
        }
        return new Result(purged, skipped, batches);
    }

    private int[] batch(Instant now, Set<UUID> seen) {
        List<Pending> pending = jdbc.sql("""
                SELECT id, workspace_id, engine_process_instance_id FROM run_archive
                WHERE status = 'purged' AND history_status = 'pending' AND NOT (id = ANY(CAST(:seen AS uuid[])))
                ORDER BY purged_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("seen", "{" + String.join(",", seen.stream().map(UUID::toString).toList()) + "}").param("limit", settings.historyBatch())
                .query((rs, n) -> new Pending(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"),
                        rs.getString("engine_process_instance_id")))
                .list();
        if (pending.isEmpty()) {
            return new int[] {0, 0};
        }
        pending.forEach(p -> seen.add(p.id()));
        RunRetention.HistoryPurged result = runs.purgeEngineHistory(
                pending.stream().map(Pending::processInstanceId).toList());
        Set<String> done = new HashSet<>(result.purged());
        Map<UUID, Integer> perWorkspace = new LinkedHashMap<>();
        for (Pending p : pending) {
            if (done.contains(p.processInstanceId())) {
                jdbc.sql("UPDATE run_archive SET history_status = 'purged', history_purged_at = :now WHERE id = :id")
                        .param("now", Sql.ts(now)).param("id", p.id()).update();
                perWorkspace.merge(p.workspaceId(), 1, Integer::sum);
            }
        }
        perWorkspace.forEach((ws, count) -> audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.SYSTEM, null,
                "run.history-purged", "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("instances", count, "skipped", result.skipped().size()))));
        purgedCounter.increment(done.size());
        return new int[] {done.size(), result.skipped().size()};
    }
}
