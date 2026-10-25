package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.notification.NotificationService;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

/** Flags stale empty projections only after proving that the engine has no work. Never invents completion. */
@Component
@Profile("!migrate")
public class RunWatchdog {
    public static final String REASON = "RUN_WAITING_ON_NOTHING";
    private static final Logger LOG = LoggerFactory.getLogger(RunWatchdog.class);
    private static final Duration GRACE = Duration.ofMinutes(5);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RunStore runs;
    private final RunEvents events;
    private final EngineCommands engine;
    private final NotificationService notifications;
    private final Json json;
    private final Clock clock;
    private final RunConsistencyScan scan;

    public RunWatchdog(JdbcClient jdbc, TransactionTemplate transactions, RunStore runs, RunEvents events,
            EngineCommands engine, NotificationService notifications, Json json, Clock clock, RunConsistencyScan scan) {
        this.scan = scan;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.runs = runs;
        this.events = events;
        this.engine = engine;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.watchdog.poll-delay:60s}", initialDelayString = "${orqix.watchdog.poll-delay:60s}")
    public void sweep() {
        try { inspect(); }
        catch (RuntimeException e) { LOG.warn("Run watchdog will retry ({})", e.getClass().getSimpleName()); }
        // Opt-in, bounded, rate-limited and report-only (docs/build/S72); a failure here never affects the pass above.
        scan.sweep();
    }

    /** Bounded pass; competing sweepers skip locked rows. Existing waits, jobs and suspended runs are retained. */
    public int inspect() {
        Integer result = transactions.execute(status -> {
            var cutoff = clock.instant().minus(GRACE);
            var candidates = jdbc.sql("""
                    SELECT workspace_id,id FROM workflow_run
                    WHERE lifecycle IN ('running','waiting') AND current_steps='[]'::jsonb AND updated_at < :cutoff
                    ORDER BY updated_at,id LIMIT 50 FOR UPDATE SKIP LOCKED
                    """).param("cutoff", Sql.ts(cutoff))
                    .query((rs,n) -> Map.entry(Sql.uuid(rs,"workspace_id"),Sql.uuid(rs,"id"))).list();
            int flagged = 0;
            for (var candidate : candidates) {
                UUID workspace = candidate.getKey(); UUID id = candidate.getValue();
                var run = runs.find(workspace,id).orElseThrow();
                // Child completion callbacks and external message delivery are engine work, not orphaned requests.
                if (engine.hasOutstandingWork(run.engineProcessInstanceId())
                        || jdbc.sql("SELECT count(*) FROM workflow_run WHERE workspace_id=:ws AND parent_run_id=:id AND lifecycle IN ('running','waiting','needsAttention')")
                                .param("ws",workspace).param("id",id).query(Long.class).single() > 0) {
                    continue;
                }
                var attention = json.object().put("reason",REASON).putNull("nodeId").putNull("effectId")
                        .put("message","This request has no active step or pending engine work. An operator must inspect it and may cancel it.")
                        .put("since",clock.instant().toString());
                runs.mutate(workspace,id,m -> { m.lifecycle=RunStore.NEEDS_ATTENTION; m.attention=attention; });
                events.append(workspace,id,"RUN_NEEDS_ATTENTION",null,null,Map.of("reason",REASON));
                notifications.create(workspace,run.initiator(),id,NotificationService.Kind.RUN_ATTENTION,
                        id+":waiting-on-nothing",FailedJobRecorder.ATTENTION_NOTIFICATION);
                flagged++;
            }
            return flagged;
        });
        return result == null ? 0 : result;
    }
}
