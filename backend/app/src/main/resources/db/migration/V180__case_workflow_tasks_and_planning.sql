-- WM04 (S110): case tasks that run a pinned workflow release, and discretionary planning commands.

-- The workflow release each process task of a case definition runs, pinned when the definition is published,
-- with explicit mappings: input field <- case data field, case data field <- output field (or outcome/reference).
CREATE TABLE case_definition_workflow (
    workspace_id   uuid NOT NULL,
    definition_id  uuid NOT NULL,
    task_id        text NOT NULL CHECK (task_id ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,63}$'),
    workflow_id    uuid NOT NULL,
    release_id     uuid NOT NULL,
    input_mapping  jsonb NOT NULL,
    result_mapping jsonb NOT NULL,
    PRIMARY KEY (workspace_id, definition_id, task_id),
    FOREIGN KEY (workspace_id, definition_id) REFERENCES case_definition (workspace_id, id),
    FOREIGN KEY (workflow_id, release_id) REFERENCES workflow_release (workflow_id, id)
);
GRANT SELECT, INSERT ON case_definition_workflow TO ${app_role};

-- One row per attempt to run the workflow of an active case task. A result is applied only while the case and its
-- plan item are still active; otherwise it is fenced (recorded, never applied).
CREATE TABLE case_workflow_task (
    id                    uuid PRIMARY KEY,
    workspace_id          uuid NOT NULL,
    case_id               uuid NOT NULL,
    plan_item_instance_id text NOT NULL,
    task_id               text NOT NULL,
    attempt               integer NOT NULL CHECK (attempt BETWEEN 1 AND 20),
    run_id                uuid,
    status                text NOT NULL CHECK (status IN ('running', 'completed', 'failed', 'cancelled', 'fenced')),
    outcome               text,
    detail                text CHECK (detail IS NULL OR length(detail) <= 1000),
    started_at            timestamptz NOT NULL DEFAULT now(),
    ended_at              timestamptz,
    UNIQUE (workspace_id, plan_item_instance_id, attempt),
    FOREIGN KEY (workspace_id, case_id) REFERENCES case_instance (workspace_id, id),
    CONSTRAINT case_workflow_task_run CHECK ((status = 'failed') OR run_id IS NOT NULL),
    CONSTRAINT case_workflow_task_ended CHECK ((status = 'running') = (ended_at IS NULL))
);
CREATE INDEX case_workflow_task_running ON case_workflow_task (started_at) WHERE status = 'running';
CREATE INDEX case_workflow_task_case ON case_workflow_task (workspace_id, case_id);
GRANT SELECT, INSERT, UPDATE ON case_workflow_task TO ${app_role};

ALTER TABLE case_command_response DROP CONSTRAINT case_command_response_operation_check;
ALTER TABLE case_command_response ADD CONSTRAINT case_command_response_operation_check CHECK (operation IN (
    'startCase','completeCaseTask','terminateCaseInstance','suspendCaseInstance','resumeCaseInstance','closeCaseInstance',
    'startCasePlanItem','enableCasePlanItem','disableCasePlanItem','activateCaseStage','completeCaseStage','terminateCasePlanItem',
    'updateCaseData','occurCaseUserEvent','planCaseDiscretionaryItem','retryCaseWorkflowTask'));
