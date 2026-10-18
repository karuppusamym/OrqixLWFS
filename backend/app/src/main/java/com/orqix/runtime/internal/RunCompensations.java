package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.web.Sql;

/**
 * The automatic undo plan of an "Undo completed calls" step (docs/build/S55, V70). Written in short transactions of
 * their own (like the effect journal) so that the plan and each completed undo survive a failed job: a retry follows
 * the same plan and never resends an undo call that already succeeded.
 */
@Repository
public class RunCompensations {

    public record Entry(int position, String targetNode, String handlerNode, String state, UUID effectId,
            Instant plannedAt, Instant undoneAt) {
        public boolean undone() {
            return "undone".equals(state);
        }
    }

    /** One call to undo: the completed call and its automatic undo call. */
    public record Planned(String targetNode, String handlerNode) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public RunCompensations(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return the plan of the step, in run order; empty when it was never planned */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Entry> plan(UUID workspaceId, UUID runId, String stepId) {
        return read(workspaceId, runId, stepId);
    }

    /**
     * Fixes the plan the first time; a later call returns the stored plan unchanged.
     *
     * @return the plan and whether this call created it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Fixed fix(UUID workspaceId, UUID runId, String stepId, List<Planned> calls) {
        List<Entry> existing = read(workspaceId, runId, stepId);
        if (!existing.isEmpty() || calls.isEmpty()) {
            return new Fixed(existing, false);
        }
        Instant now = clock.instant();
        for (int i = 0; i < calls.size(); i++) {
            jdbc.sql("""
                    INSERT INTO run_compensation (run_id, workspace_id, step_id, position, target_node, handler_node,
                      state, planned_at)
                    VALUES (:run, :ws, :step, :position, :target, :handler, 'pending', :at)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("run", runId).param("ws", workspaceId).param("step", stepId).param("position", i)
                    .param("target", calls.get(i).targetNode()).param("handler", calls.get(i).handlerNode())
                    .param("at", Sql.ts(now)).update();
        }
        return new Fixed(read(workspaceId, runId, stepId), true);
    }

    public record Fixed(List<Entry> entries, boolean created) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void undone(UUID workspaceId, UUID runId, String stepId, String targetNode, UUID effectId) {
        jdbc.sql("""
                UPDATE run_compensation SET state = 'undone', effect_id = :effect, undone_at = :at
                WHERE workspace_id = :ws AND run_id = :run AND step_id = :step AND target_node = :target
                  AND state = 'pending'
                """)
                .param("effect", effectId).param("at", Sql.ts(clock.instant())).param("ws", workspaceId)
                .param("run", runId).param("step", stepId).param("target", targetNode).update();
    }

    /** @return every planned undo of the run, by step and position (read side, run detail and tests) */
    @Transactional(readOnly = true)
    public List<Entry> forRun(UUID workspaceId, UUID runId) {
        return jdbc.sql("""
                SELECT position, target_node, handler_node, state, effect_id, planned_at, undone_at
                FROM run_compensation WHERE workspace_id = :ws AND run_id = :run ORDER BY step_id, position
                """)
                .param("ws", workspaceId).param("run", runId).query((rs, n) -> new Entry(rs.getInt("position"),
                        rs.getString("target_node"), rs.getString("handler_node"), rs.getString("state"),
                        Sql.uuid(rs, "effect_id"), Sql.instant(rs, "planned_at"), Sql.instant(rs, "undone_at")))
                .list();
    }

    private List<Entry> read(UUID workspaceId, UUID runId, String stepId) {
        return jdbc.sql("""
                SELECT position, target_node, handler_node, state, effect_id, planned_at, undone_at
                FROM run_compensation WHERE workspace_id = :ws AND run_id = :run AND step_id = :step ORDER BY position
                """)
                .param("ws", workspaceId).param("run", runId).param("step", stepId)
                .query((rs, n) -> new Entry(rs.getInt("position"), rs.getString("target_node"),
                        rs.getString("handler_node"), rs.getString("state"), Sql.uuid(rs, "effect_id"),
                        Sql.instant(rs, "planned_at"), Sql.instant(rs, "undone_at")))
                .list();
    }
}
