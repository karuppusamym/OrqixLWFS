-- EX08 (S59): the automatic undo plan of an "Undo completed calls" step. The plan is fixed the first time the step runs:
-- one row per call that completed in this request and has an automatic undo call, in the order the undo calls run
-- (position 0 first: the most recent call). A retry after a failed undo call follows the same plan; an undo call that
-- already succeeded is not sent again (its own effect journal entry is reused). Like effect_journal (TX-3), rows are
-- written in their own short transactions from inside a job and touch only this table, so there are no foreign keys
-- that could wait on a lock the suspended job transaction holds.
CREATE TABLE run_compensation (
    run_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    step_id text NOT NULL CHECK (length(step_id) BETWEEN 1 AND 64),
    position integer NOT NULL CHECK (position BETWEEN 0 AND 49),
    target_node text NOT NULL CHECK (length(target_node) BETWEEN 1 AND 64),
    handler_node text NOT NULL CHECK (length(handler_node) BETWEEN 1 AND 64),
    state text NOT NULL CHECK (state IN ('pending', 'undone')),
    effect_id uuid,
    planned_at timestamptz NOT NULL,
    undone_at timestamptz,
    PRIMARY KEY (run_id, step_id, target_node),
    UNIQUE (run_id, step_id, position),
    CHECK ((state = 'undone') = (undone_at IS NOT NULL))
);
CREATE INDEX run_compensation_workspace ON run_compensation (workspace_id, run_id);
GRANT SELECT, INSERT, UPDATE ON run_compensation TO ${app_role};
