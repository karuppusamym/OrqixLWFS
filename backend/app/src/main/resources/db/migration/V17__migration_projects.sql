CREATE TABLE migration_project (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    created_by uuid NOT NULL REFERENCES subject(id),
    name text NOT NULL CHECK(length(name) BETWEEN 1 AND 200),
    sources jsonb NOT NULL,
    report jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX migration_projects_workspace ON migration_project(workspace_id,created_at DESC,id DESC);
GRANT SELECT,INSERT ON migration_project TO ${app_role};
