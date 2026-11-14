-- S138 (CG-E01 bounded loops, CG-E06 for each on any step).
--
-- task_context.occurrence: the visit of the approval step the task belongs to. Outside bounded loops it is always 1;
-- inside a loop every visit creates new tasks with the next number, so tasks, decisions and SLA breaches of each
-- visit stay apart while separation of duties still looks at every decision of the request.
ALTER TABLE task_context ADD COLUMN occurrence integer NOT NULL DEFAULT 1 CHECK (occurrence BETWEEN 1 AND 51);
CREATE INDEX task_by_run_node ON task_context (run_id, node_id, occurrence);

-- One row per finished item of a for-each step visit. Written in the engine transaction by the step's delegate (or
-- trigger) instead of the step variable; read by the completion condition and, when the step is left, to build the
-- step outputs in item order. The position is the 0-based index in the request list.
CREATE TABLE step_item_result (
    workspace_id  uuid NOT NULL,
    run_id        uuid NOT NULL,
    node_id       text NOT NULL CHECK (length(node_id) BETWEEN 1 AND 64),
    occurrence    integer NOT NULL CHECK (occurrence BETWEEN 1 AND 51),
    item_index    integer NOT NULL CHECK (item_index BETWEEN 0 AND 499),
    succeeded     boolean NOT NULL,
    result        jsonb NOT NULL,
    completed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, node_id, occurrence, item_index),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id)
);
CREATE INDEX step_item_result_by_workspace ON step_item_result (workspace_id, run_id);

GRANT SELECT, INSERT, UPDATE ON step_item_result TO ${app_role};
