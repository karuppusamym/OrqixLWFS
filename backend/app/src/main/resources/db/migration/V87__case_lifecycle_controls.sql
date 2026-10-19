ALTER TABLE case_instance DROP CONSTRAINT case_instance_status_check;
ALTER TABLE case_instance ADD CONSTRAINT case_instance_status_check CHECK (status IN ('active','suspended','completed','terminated'));
ALTER TABLE case_command_response DROP CONSTRAINT case_command_response_operation_check;
ALTER TABLE case_command_response ADD CONSTRAINT case_command_response_operation_check CHECK (operation IN (
    'startCase','completeCaseTask','terminateCaseInstance','suspendCaseInstance','resumeCaseInstance','closeCaseInstance',
    'startCasePlanItem','enableCasePlanItem','disableCasePlanItem','activateCaseStage','completeCaseStage','terminateCasePlanItem'));

CREATE TABLE case_event (
    workspace_id uuid NOT NULL,
    case_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    action text NOT NULL,
    plan_item_id text,
    from_status text,
    to_status text NOT NULL,
    actor_subject_id uuid NOT NULL REFERENCES subject(id),
    reason text CHECK (reason IS NULL OR length(reason) BETWEEN 1 AND 1000),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id,case_id,version),
    FOREIGN KEY (workspace_id,case_id) REFERENCES case_instance(workspace_id,id)
);
GRANT SELECT, INSERT ON case_event TO ${app_role};
