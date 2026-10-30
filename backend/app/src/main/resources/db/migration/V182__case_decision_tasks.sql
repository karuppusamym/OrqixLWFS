-- WM04 (S118): case decision tasks that evaluate a shared decision table version pinned at publication.

CREATE TABLE case_definition_decision (
    workspace_id   uuid NOT NULL,
    definition_id  uuid NOT NULL,
    task_id        text NOT NULL CHECK (task_id ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,63}$'),
    asset_id       uuid NOT NULL,
    asset_version  integer NOT NULL CHECK (asset_version >= 1),
    input_mapping  jsonb NOT NULL,
    result_mapping jsonb NOT NULL,
    PRIMARY KEY (workspace_id, definition_id, task_id),
    FOREIGN KEY (workspace_id, definition_id) REFERENCES case_definition (workspace_id, id),
    FOREIGN KEY (asset_id, asset_version) REFERENCES decision_asset_version (asset_id, version)
);
GRANT SELECT, INSERT ON case_definition_decision TO ${app_role};

-- A decision-task attempt is evaluated at once; it records the pinned table instead of a run or a child case.
ALTER TABLE case_workflow_task ADD COLUMN decision_asset_id uuid;
ALTER TABLE case_workflow_task DROP CONSTRAINT case_workflow_task_run;
ALTER TABLE case_workflow_task ADD CONSTRAINT case_workflow_task_run CHECK (status = 'failed' OR run_id IS NOT NULL OR child_case_id IS NOT NULL OR decision_asset_id IS NOT NULL);
