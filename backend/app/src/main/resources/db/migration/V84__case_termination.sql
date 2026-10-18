ALTER TABLE case_instance DROP CONSTRAINT case_instance_status_check;
ALTER TABLE case_instance ADD CONSTRAINT case_instance_status_check CHECK (status IN ('active','completed','terminated'));
ALTER TABLE case_command_response DROP CONSTRAINT case_command_response_operation_check;
ALTER TABLE case_command_response ADD CONSTRAINT case_command_response_operation_check
    CHECK (operation IN ('startCase','completeCaseTask','terminateCaseInstance'));
CREATE TABLE case_termination (
    workspace_id uuid NOT NULL,
    case_id uuid NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    terminated_by uuid NOT NULL REFERENCES subject(id),
    terminated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id,case_id),
    FOREIGN KEY (workspace_id,case_id) REFERENCES case_instance(workspace_id,id)
);
GRANT SELECT, INSERT ON case_termination TO ${app_role};
