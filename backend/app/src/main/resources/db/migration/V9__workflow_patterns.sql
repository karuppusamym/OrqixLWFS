-- S12 workflow patterns (docs/build/S12-workflow-patterns.md): a run can be the child of another run ("Start another
-- workflow"). Expand-only: new nullable columns on workflow_run, one unique index and one lookup index.
--
--   parent_run_id       run that started this one (same workspace); null for ordinary runs
--   parent_node_id      the "Start another workflow" step of the parent
--   parent_occurrence   visit counter of that step (always 1 while workflows are acyclic); with the two columns above
--                       it is the idempotency key of the child start: a retried or duplicated job finds the child it
--                       already created instead of starting a second one
--   call_depth          0 for ordinary runs, parent depth + 1 for children (bounded by the compiler; checked again at
--                       start so a defect cannot create an unbounded chain)
--
-- The child's release is pinned in the parent's release manifest (workflows[]), not here: a release never changes, so
-- republishing the child never changes what a running parent starts.

ALTER TABLE workflow_run
    ADD COLUMN parent_run_id      uuid,
    ADD COLUMN parent_node_id     text,
    ADD COLUMN parent_occurrence  integer,
    ADD COLUMN call_depth         integer NOT NULL DEFAULT 0;

ALTER TABLE workflow_run
    ADD CONSTRAINT workflow_run_parent_fk FOREIGN KEY (workspace_id, parent_run_id)
        REFERENCES workflow_run (workspace_id, id),
    ADD CONSTRAINT workflow_run_parent_complete CHECK (
        (parent_run_id IS NULL AND parent_node_id IS NULL AND parent_occurrence IS NULL AND call_depth = 0)
        OR (parent_run_id IS NOT NULL AND parent_node_id IS NOT NULL AND parent_occurrence >= 1 AND call_depth >= 1)),
    ADD CONSTRAINT workflow_run_call_depth_bound CHECK (call_depth BETWEEN 0 AND 4),
    ADD CONSTRAINT workflow_run_not_own_parent CHECK (parent_run_id IS DISTINCT FROM id);

-- Exactly one child per (parent run, step, visit).
CREATE UNIQUE INDEX workflow_run_child_once
    ON workflow_run (workspace_id, parent_run_id, parent_node_id, parent_occurrence)
    WHERE parent_run_id IS NOT NULL;

-- Children of a run (run detail, cancel cascade).
CREATE INDEX workflow_run_by_parent
    ON workflow_run (workspace_id, parent_run_id, created_at)
    WHERE parent_run_id IS NOT NULL;
