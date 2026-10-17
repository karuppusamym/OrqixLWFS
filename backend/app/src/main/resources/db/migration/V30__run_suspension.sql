-- EX04: an operator can suspend an active request and resume it later. The lifecycle projection is unchanged; a row
-- here means the engine process instance is suspended. The row is removed on resume or cancellation.
CREATE TABLE run_suspension (
    run_id uuid PRIMARY KEY REFERENCES workflow_run(id),
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    suspended_at timestamptz NOT NULL,
    suspended_by uuid NOT NULL REFERENCES subject(id),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500)
);
CREATE INDEX run_suspension_workspace ON run_suspension(workspace_id, suspended_at DESC);
GRANT SELECT, INSERT, DELETE ON run_suspension TO ${app_role};
