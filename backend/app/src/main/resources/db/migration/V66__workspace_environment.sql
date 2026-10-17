-- PL02: an explicit lifecycle stage for a workspace. Promotion only moves definitions to a later stage of the same
-- organization. A workspace without a row is "test" when flagged is_test and "production" otherwise.
CREATE TABLE workspace_environment (
    workspace_id uuid PRIMARY KEY REFERENCES workspace(id),
    stage text NOT NULL CHECK (stage IN ('development', 'test', 'staging', 'production')),
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by uuid NOT NULL REFERENCES subject(id),
    updated_at timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON workspace_environment TO ${app_role};
