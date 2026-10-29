-- WM04 (S113): case tasks that start a pinned child case, timer listeners and data-triggered sentries.

-- The child case definition version each case task starts, pinned when the parent definition is published, with
-- explicit mappings: child data field <- parent data field, parent data field <- child data field (or outcome).
CREATE TABLE case_definition_child (
    workspace_id        uuid NOT NULL,
    definition_id       uuid NOT NULL,
    task_id             text NOT NULL CHECK (task_id ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,63}$'),
    child_definition_id uuid NOT NULL,
    input_mapping       jsonb NOT NULL,
    result_mapping      jsonb NOT NULL,
    PRIMARY KEY (workspace_id, definition_id, task_id),
    FOREIGN KEY (workspace_id, definition_id) REFERENCES case_definition (workspace_id, id),
    FOREIGN KEY (workspace_id, child_definition_id) REFERENCES case_definition (workspace_id, id)
);
GRANT SELECT, INSERT ON case_definition_child TO ${app_role};

-- A child case knows the case task that started it; nesting is bounded.
ALTER TABLE case_instance ADD COLUMN parent_case_id uuid;
ALTER TABLE case_instance ADD COLUMN parent_plan_item_id text;
ALTER TABLE case_instance ADD COLUMN depth integer NOT NULL DEFAULT 0 CHECK (depth BETWEEN 0 AND 3);
ALTER TABLE case_instance ADD CONSTRAINT case_instance_parent FOREIGN KEY (workspace_id, parent_case_id) REFERENCES case_instance (workspace_id, id);
ALTER TABLE case_instance ADD CONSTRAINT case_instance_parent_pair CHECK ((parent_case_id IS NULL) = (parent_plan_item_id IS NULL));
CREATE INDEX case_instance_parent_idx ON case_instance (workspace_id, parent_case_id) WHERE parent_case_id IS NOT NULL;

-- A case-task attempt points at the child case instead of a workflow run.
ALTER TABLE case_workflow_task ADD COLUMN child_case_id uuid;
ALTER TABLE case_workflow_task ADD CONSTRAINT case_workflow_task_child FOREIGN KEY (workspace_id, child_case_id) REFERENCES case_instance (workspace_id, id);
ALTER TABLE case_workflow_task DROP CONSTRAINT case_workflow_task_run;
ALTER TABLE case_workflow_task ADD CONSTRAINT case_workflow_task_run CHECK (status = 'failed' OR run_id IS NOT NULL OR child_case_id IS NOT NULL);
ALTER TABLE case_workflow_task ADD CONSTRAINT case_workflow_task_one_target CHECK (run_id IS NULL OR child_case_id IS NULL);

-- Definitions with timer listeners are swept for timer occurrences the engine fired on its own.
ALTER TABLE case_definition ADD COLUMN has_timers boolean NOT NULL DEFAULT false;
CREATE INDEX case_event_timer ON case_event (workspace_id, case_id, plan_item_id) WHERE action = 'timerOccurred';
