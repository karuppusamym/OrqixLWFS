package com.orqix.runtime.internal;

import java.time.Clock;
import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.shared.web.Sql;

/**
 * Feeds the analytics read model (docs/build/S145): a timeline event that changes a run's figures adds an outbox row in
 * the transaction that appended the event, so the projector never misses one and never sees a rolled-back change. Only
 * the run id travels; the projector reads the run's timeline itself.
 */
@Component
public class AnalyticsOutboxWriter {

    /** Events after which the run's facts (visits, routes, tasks, cycle time) can differ. */
    static final Set<String> TYPES = Set.of("RUN_COMPLETED", "RUN_CANCELLED", "REQUEST_ENDED", "TASK_CREATED",
            "TASK_CLAIMED", "TASK_RELEASED", "TASK_REASSIGNED", "TASK_DECIDED", "TASK_TIMED_OUT", "TASK_ESCALATED",
            "STEP_INTERRUPTED", "SECTION_LEFT", "HISTORY_CHANGED");

    private final JdbcClient jdbc;
    private final Clock clock;

    public AnalyticsOutboxWriter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @EventListener
    public void onEvent(RunEvents.Appended event) {
        if (!TYPES.contains(event.type())) {
            return;
        }
        var now = Sql.ts(clock.instant());
        jdbc.sql("""
                INSERT INTO analytics_outbox (workspace_id, run_id, reason, event_type, created_at, next_attempt_at)
                VALUES (:ws, :run, 'event', :type, :now, :now)
                """)
                .param("ws", event.workspaceId()).param("run", event.runId()).param("type", event.type())
                .param("now", now).update();
    }
}
