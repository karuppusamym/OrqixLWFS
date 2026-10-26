-- S92: EXPLAIN (ANALYZE, BUFFERS) of the hot list/search/insights/sweeper queries on a seeded database
-- (tools/perf/seed_large.sql). Query texts are copied from the application (RunQueries, TaskQueries, InsightsQueries,
-- RunWatchdog, RunStore). The catch-all run list is also run as a prepared statement with plan_cache_mode =
-- force_generic_plan, because the JDBC driver switches to server-side prepared statements after 5 executions and
-- PostgreSQL may then use one generic plan for every filter combination.
--   psql -U postgres -d orqix -f tools/perf/explain_hot.sql
\set ON_ERROR_STOP on
SET search_path TO orqix, flowable, public;
SELECT id AS ws FROM workspace WHERE key = 'purchasing' \gset
SELECT id AS mgr FROM subject WHERE email = 'max.manager@orqix.example' \gset
SELECT id AS req FROM subject WHERE email = 'rhea.requester@orqix.example' \gset
SELECT id AS wf FROM workflow WHERE workspace_id = :'ws' AND key LIKE 'scale-%' ORDER BY created_at LIMIT 1 \gset

\echo '=== Q1 run list, first page, no filter'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT id FROM workflow_run WHERE workspace_id = :'ws'
  ORDER BY created_at DESC, id DESC LIMIT 21;

PREPARE runs(uuid, uuid, text, text, text, timestamptz, timestamptz, uuid, boolean, timestamptz, timestamptz, uuid, int) AS
SELECT id FROM workflow_run
 WHERE workspace_id = $1
   AND (CAST($2 AS uuid) IS NULL OR workflow_id = $2)
   AND (CAST($3 AS text) IS NULL OR lifecycle = $3)
   AND (CAST($4 AS text) IS NULL OR outcome_kind = $4)
   AND (CAST($5 AS text) IS NULL OR business_key = $5 OR reference = $5)
   AND (CAST($6 AS timestamptz) IS NULL OR created_at >= $6)
   AND (CAST($7 AS timestamptz) IS NULL OR created_at < $7)
   AND (CAST($8 AS uuid) IS NULL OR initiator_subject_id = $8)
   AND ($9 = false OR EXISTS (SELECT 1 FROM run_stage s WHERE s.run_id = workflow_run.id
        AND s.completed_at IS NULL AND s.due_at IS NOT NULL AND s.due_at < $10))
   AND (CAST($11 AS timestamptz) IS NULL OR (created_at, id) < (CAST($11 AS timestamptz), CAST($12 AS uuid)))
 ORDER BY created_at DESC, id DESC LIMIT $13;

\echo '=== Q2 run list lifecycle=needsAttention (custom plan)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE runs(:'ws', NULL, 'needsAttention', NULL, NULL, NULL, NULL, NULL, false, now(), NULL, NULL, 21);
\echo '=== Q3 run list business key search, no match (custom plan)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE runs(:'ws', NULL, NULL, NULL, 'PO-NOPE', NULL, NULL, NULL, false, now(), NULL, NULL, 21);
\echo '=== Q4 run list workflow + completed (custom plan)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE runs(:'ws', :'wf', 'completed', NULL, NULL, NULL, NULL, NULL, false, now(), NULL, NULL, 21);
\echo '=== Q5 run list overdue (custom plan)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE runs(:'ws', NULL, NULL, NULL, NULL, NULL, NULL, NULL, true, now(), NULL, NULL, 21);
SET plan_cache_mode = force_generic_plan;
\echo '=== Q2g needsAttention (GENERIC plan)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE runs(:'ws', NULL, 'needsAttention', NULL, NULL, NULL, NULL, NULL, false, now(), NULL, NULL, 21);
\echo '=== Q3g business key no match (GENERIC plan)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE runs(:'ws', NULL, NULL, NULL, 'PO-NOPE', NULL, NULL, NULL, false, now(), NULL, NULL, 21);
RESET plan_cache_mode;

\echo '=== Q6 claimable inbox, newest first'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT t.engine_task_id FROM task_context t WHERE t.workspace_id = :'ws' AND t.status = 'open'
  AND t.assignee_subject_id IS NULL AND t.candidate_group_key IN ('managers') AND t.required_skills <@ '["managers"]'::jsonb
  AND (t.self_approval_allowed OR t.initiator_subject_id <> :'mgr')
  AND NOT EXISTS (SELECT 1 FROM task_decision d WHERE d.workspace_id = t.workspace_id AND d.run_id = t.run_id
        AND d.actor_subject_id = :'mgr' AND d.node_id IN (SELECT jsonb_array_elements_text(t.separate_from)))
  AND t.priority >= 0 ORDER BY t.created_at DESC, t.engine_task_id DESC LIMIT 51;
\echo '=== Q7 claimable count'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM task_context t WHERE t.workspace_id = :'ws' AND t.status = 'open' AND t.assignee_subject_id IS NULL
  AND t.candidate_group_key IN ('managers') AND (t.self_approval_allowed OR t.initiator_subject_id <> :'mgr')
  AND t.required_skills <@ '["managers"]'::jsonb
  AND NOT EXISTS (SELECT 1 FROM task_decision d WHERE d.workspace_id = t.workspace_id AND d.run_id = t.run_id
        AND d.actor_subject_id = :'mgr' AND d.node_id IN (SELECT jsonb_array_elements_text(t.separate_from)));
\echo '=== Q8 completed tasks of a person'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT t.engine_task_id, d.decided_at FROM task_context t JOIN task_decision d
  ON d.workspace_id = t.workspace_id AND d.engine_task_id = t.engine_task_id
 WHERE t.workspace_id = :'ws' AND d.actor_subject_id = :'mgr' ORDER BY d.decided_at DESC, t.engine_task_id DESC LIMIT 51;

\echo '=== Q9 insights: open requests by step (workspace)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT r.workflow_id, s ->> 'nodeId', count(*) FROM workflow_run r, jsonb_array_elements(r.current_steps) s
 WHERE r.workspace_id = :'ws' AND r.lifecycle IN ('running', 'waiting', 'needsAttention') GROUP BY 1, 2;
\echo '=== Q10 insights: task step durations, 90 days'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
WITH tmo AS (SELECT run_id, node_id, occurred_at FROM run_event
             WHERE workspace_id = :'ws' AND type = 'TASK_TIMED_OUT' AND occurred_at >= now() - interval '90 days')
SELECT count(*) FROM tmo;
\echo '=== Q11 insights: wait durations, 90 days'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM run_event e
 WHERE e.workspace_id = :'ws' AND e.type IN ('WAIT_STARTED', 'WAIT_ENDED') AND e.occurred_at >= now() - interval '90 days';
\echo '=== Q12 insights totals, 30 days'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*), count(*) FILTER (WHERE lifecycle = 'completed') FROM workflow_run
 WHERE workspace_id = :'ws' AND created_at >= now() - interval '30 days' AND created_at < now();
\echo '=== Q13 insights completed per day, 30 days'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT date_trunc('day', ended_at), count(*) FROM workflow_run
 WHERE workspace_id = :'ws' AND lifecycle = 'completed' AND ended_at >= now() - interval '30 days' AND ended_at < now() GROUP BY 1;

\echo '=== Q14 watchdog candidates (every pod, every 60 s)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT workspace_id, id FROM workflow_run WHERE lifecycle IN ('running', 'waiting') AND current_steps = '[]'::jsonb
   AND updated_at < now() - interval '5 minutes' ORDER BY updated_at, id LIMIT 50 FOR UPDATE SKIP LOCKED;
\echo '=== Q15 active runs of a workflow (quota check on every start)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM workflow_run WHERE workspace_id = :'ws' AND workflow_id = :'wf' AND lifecycle IN ('running', 'waiting', 'needsAttention');
\echo '=== Q16 notifications inbox + unread'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM notification WHERE workspace_id = :'ws' AND recipient_subject_id = :'req' ORDER BY created_at DESC, id DESC LIMIT 51;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM notification WHERE workspace_id = :'ws' AND recipient_subject_id = :'req' AND read_at IS NULL;
