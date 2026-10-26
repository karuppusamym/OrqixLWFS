-- S92 scalability qualification (docs/build/S92-scalability-qualification.md): indexes for queries that scanned whole
-- tables on a seeded database (100k requests, 300k tasks, 800k events) and grew with history. Expand-only; no query or
-- behavior change. Plain CREATE INDEX (Flyway runs each script in a transaction): it blocks writes to the table while
-- it builds, which on the seeded database took a few seconds per index (S92 records the measured times). The migration
-- job runs before the pods are rolled, as for every migration.

-- Insights "Steps" (InsightsQueries.approvalSteps / waitSteps): TASK_TIMED_OUT and WAIT_STARTED/WAIT_ENDED events of a
-- workspace in the date window. run_event had only its primary key (run_id, seq), so every Insights page view read the
-- whole event table, on every replica's request.
CREATE INDEX run_event_ws_type_time ON run_event (workspace_id, type, occurred_at);

-- Insights "Steps" (approvalSteps): finished tasks created in the window. task_context had no created_at index outside
-- the open-task partial indexes.
CREATE INDEX task_context_ws_created_ended ON task_context (workspace_id, created_at) WHERE status <> 'open';

-- Open requests of a workspace (InsightsQueries.oldestOpen / openBySteps, run list filtered by an open lifecycle):
-- a small partial index instead of walking every request of the workspace newest first.
CREATE INDEX workflow_run_ws_open ON workflow_run (workspace_id, created_at, id)
    WHERE lifecycle IN ('running', 'waiting', 'needsAttention');

-- Not added: a watchdog index (RunWatchdog uses workflow_run_active and took 5 ms on the seeded database) and any
-- index on updated_at (it would add write cost to the most-updated table for no measured gain).

-- Insights throughput (InsightsQueries.daily): completed requests by end time in the window.
CREATE INDEX workflow_run_ws_completed_ended ON workflow_run (workspace_id, ended_at) WHERE lifecycle = 'completed';
