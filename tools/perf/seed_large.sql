-- S92 large-dataset seed (docs/build/S92). QUALIFICATION STACKS ONLY: writes synthetic history straight into the
-- application tables of the 'purchasing' workspace so list, search and insights queries can be measured at volume.
-- The seeded runs have no Flowable process instance (they are history: completed/cancelled, or open with a synthetic
-- open task that nobody can complete). Never run this against a database whose data matters.
--
--   psql -v runs=100000 -v tasks_per_run=3 -f tools/perf/seed_large.sql      (as the superuser, search_path orqix)
--
-- Shape per run: 90 % completed (success/rejected), 2 % cancelled, 7 % waiting with one open claimable task in group
-- 'managers', 1 % needsAttention; created_at spread over the last 180 days; :tasks_per_run tasks per run, one decision per
-- completed task; 8 run events per run (incl. WAIT_STARTED/WAIT_ENDED pairs on 20 % and TASK_TIMED_OUT on 2 %).
\set ON_ERROR_STOP on
SET search_path TO orqix, flowable, public;
SET synchronous_commit = off;

BEGIN;
CREATE TEMP TABLE seed_ctx AS
SELECT w.id AS ws, wf.id AS wf, wf.active_release_id AS rel,
       (SELECT id FROM subject WHERE email = 'rhea.requester@orqix.example') AS requester,
       (SELECT id FROM subject WHERE email = 'max.manager@orqix.example') AS manager,
       (SELECT run_seq FROM workspace WHERE id = w.id) AS seq0
FROM workspace w JOIN workflow wf ON wf.workspace_id = w.id
WHERE w.key = 'purchasing' AND wf.active_release_id IS NOT NULL AND wf.key LIKE 'scale-%'
ORDER BY wf.created_at LIMIT 1;

CREATE TEMP TABLE seed_runs AS
SELECT gen_random_uuid() AS id, g AS n,
       now() - make_interval(secs => (random() * 180 * 86400)::int) AS created_at,
       CASE WHEN g % 100 < 90 THEN 'completed' WHEN g % 100 < 92 THEN 'cancelled'
            WHEN g % 100 < 99 THEN 'waiting' ELSE 'needsAttention' END AS lifecycle
FROM generate_series(1, :runs) g;

INSERT INTO workflow_run (id, workspace_id, workflow_id, release_id, engine_process_instance_id, business_key,
                          initiator_subject_id, lifecycle, outcome, outcome_kind, current_steps, attention, input,
                          output, event_seq, version, created_at, updated_at, ended_at, reference)
SELECT r.id, c.ws, c.wf, c.rel, NULL, CASE WHEN r.n % 10 = 0 THEN 'PO-' || r.n END, c.requester, r.lifecycle,
       CASE WHEN r.lifecycle = 'completed' THEN (CASE WHEN r.n % 7 = 0 THEN 'Rejected' ELSE 'Fulfilled' END) END,
       CASE WHEN r.lifecycle = 'completed' THEN (CASE WHEN r.n % 7 = 0 THEN 'rejected' ELSE 'success' END) END,
       CASE WHEN r.lifecycle IN ('waiting', 'needsAttention')
            THEN '[{"nodeId":"manager","label":"Review request","kind":"task"}]'::jsonb ELSE '[]'::jsonb END,
       CASE WHEN r.lifecycle = 'needsAttention'
            THEN jsonb_build_object('reason', 'EFFECT_FAILED', 'nodeId', 'manager', 'message', 'seeded', 'since', to_char((r.created_at + interval '2 hours') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')) END,
       jsonb_build_object('title', 'Seeded ' || r.n, 'purpose', 'S92 seed', 'costCenter', 'OPS', 'amount', (r.n % 4500) + 1),
       CASE WHEN r.lifecycle = 'completed' THEN '{"orderId":"seed"}'::jsonb END,
       8, 1, r.created_at, r.created_at + interval '2 hours',
       CASE WHEN r.lifecycle IN ('completed', 'cancelled') THEN r.created_at + make_interval(mins => 5 + (r.n % 2000)) END,
       'ORQ-' || (c.seq0 + r.n)
FROM seed_runs r CROSS JOIN seed_ctx c;

UPDATE workspace SET run_seq = run_seq + :runs WHERE id = (SELECT ws FROM seed_ctx);

-- Tasks: node t of each run; the last task of a waiting run is open and claimable by 'managers'.
CREATE TEMP TABLE seed_tasks AS
SELECT 'seed-' || r.n || '-' || t AS task_id, r.id AS run_id, r.n, t, r.created_at + make_interval(mins => t * 3) AS created_at,
       CASE WHEN r.lifecycle IN ('waiting', 'needsAttention') AND t = :tasks_per_run THEN 'open'
            WHEN r.lifecycle = 'cancelled' AND t = :tasks_per_run THEN 'cancelled' ELSE 'completed' END AS status
FROM seed_runs r CROSS JOIN generate_series(1, :tasks_per_run) t;

INSERT INTO task_context (engine_task_id, workspace_id, run_id, node_id, label, candidate_group_key, initiator_subject_id,
                          self_approval_allowed, assignee_subject_id, status, version, due_at, created_at, claimed_at,
                          ended_at, priority, required_skills, separate_from)
SELECT s.task_id, c.ws, s.run_id, 'step' || s.t, 'Seeded step ' || s.t, 'managers', c.requester, false,
       CASE WHEN s.status = 'completed' THEN c.manager END, s.status, CASE WHEN s.status = 'completed' THEN 3 ELSE 1 END,
       NULL, s.created_at, CASE WHEN s.status = 'completed' THEN s.created_at + interval '1 minute' END,
       CASE WHEN s.status <> 'open' THEN s.created_at + make_interval(mins => 2 + (s.n % 600)) END,
       (s.n % 5) * 20, '[]'::jsonb, '[]'::jsonb
FROM seed_tasks s CROSS JOIN seed_ctx c;

INSERT INTO task_decision (id, workspace_id, engine_task_id, run_id, node_id, actor_subject_id, action, reason, comment,
                           policy_flags, decided_at, distinct_actor)
SELECT gen_random_uuid(), c.ws, s.task_id, s.run_id, 'step' || s.t, c.manager, 'approve', NULL, NULL, '[]'::jsonb,
       s.created_at + make_interval(mins => 2 + (s.n % 600)), false
FROM seed_tasks s CROSS JOIN seed_ctx c WHERE s.status = 'completed';

INSERT INTO run_event (run_id, seq, workspace_id, type, node_id, actor_subject_id, occurred_at, data)
SELECT r.id, e.seq, c.ws,
       CASE e.seq WHEN 1 THEN 'RUN_STARTED' WHEN 2 THEN 'TASK_CREATED' WHEN 3 THEN 'TASK_CLAIMED'
                  WHEN 4 THEN 'TASK_DECIDED'
                  WHEN 5 THEN CASE WHEN r.n % 5 = 0 THEN 'WAIT_STARTED' ELSE 'DECISION_EVALUATED' END
                  WHEN 6 THEN CASE WHEN r.n % 5 = 0 THEN 'WAIT_ENDED' WHEN r.n % 50 = 1 THEN 'TASK_TIMED_OUT'
                                   ELSE 'NOTIFICATION_CREATED' END
                  WHEN 7 THEN 'EFFECT_SUCCEEDED' ELSE 'RUN_COMPLETED' END,
       CASE WHEN e.seq BETWEEN 2 AND 6 THEN 'step1' END, NULL, r.created_at + make_interval(mins => e.seq * 2),
       CASE WHEN e.seq = 6 AND r.n % 5 = 0 THEN '{"outcome":"timedOut"}'::jsonb ELSE '{}'::jsonb END
FROM seed_runs r CROSS JOIN seed_ctx c CROSS JOIN generate_series(1, 8) AS e(seq);

INSERT INTO notification (id, workspace_id, recipient_subject_id, run_id, kind, dedup_key, message, created_at, read_at)
SELECT gen_random_uuid(), c.ws, c.requester, r.id, 'workflowMessage', 'seed:' || r.id, 'Seeded notification ' || r.n,
       r.created_at + interval '10 minutes', CASE WHEN r.n % 3 = 0 THEN NULL ELSE r.created_at + interval '1 day' END
FROM seed_runs r CROSS JOIN seed_ctx c WHERE r.n % 2 = 0;
COMMIT;

ANALYZE workflow_run; ANALYZE task_context; ANALYZE task_decision; ANALYZE run_event; ANALYZE notification;
SELECT 'runs' AS t, count(*) FROM workflow_run UNION ALL SELECT 'tasks', count(*) FROM task_context
UNION ALL SELECT 'open tasks', count(*) FROM task_context WHERE status = 'open'
UNION ALL SELECT 'decisions', count(*) FROM task_decision UNION ALL SELECT 'events', count(*) FROM run_event
UNION ALL SELECT 'notifications', count(*) FROM notification;
