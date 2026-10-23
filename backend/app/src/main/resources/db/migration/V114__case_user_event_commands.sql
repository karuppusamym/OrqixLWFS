-- Native user events use the existing case-command version/idempotency/history boundary.
ALTER TABLE case_command_response DROP CONSTRAINT case_command_response_operation_check;
ALTER TABLE case_command_response ADD CONSTRAINT case_command_response_operation_check CHECK (operation IN (
    'startCase','completeCaseTask','terminateCaseInstance','suspendCaseInstance','resumeCaseInstance','closeCaseInstance',
    'startCasePlanItem','enableCasePlanItem','disableCasePlanItem','activateCaseStage','completeCaseStage','terminateCasePlanItem',
    'updateCaseData','occurCaseUserEvent'));
