ALTER TABLE case_definition ADD COLUMN data_fields jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(data_fields)='array');
ALTER TABLE case_instance ADD COLUMN data_values jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(data_values)='object');
ALTER TABLE case_command_response DROP CONSTRAINT case_command_response_operation_check;
ALTER TABLE case_command_response ADD CONSTRAINT case_command_response_operation_check CHECK (operation IN (
    'startCase','completeCaseTask','terminateCaseInstance','suspendCaseInstance','resumeCaseInstance','closeCaseInstance',
    'startCasePlanItem','enableCasePlanItem','disableCasePlanItem','activateCaseStage','completeCaseStage','terminateCasePlanItem','updateCaseData'));
CREATE TABLE case_data_event (
    workspace_id uuid NOT NULL,
    case_id uuid NOT NULL,
    version integer NOT NULL,
    before_values jsonb NOT NULL,
    after_values jsonb NOT NULL,
    PRIMARY KEY (workspace_id,case_id,version),
    FOREIGN KEY (workspace_id,case_id,version) REFERENCES case_event(workspace_id,case_id,version)
);
GRANT SELECT,INSERT ON case_data_event TO ${app_role};
