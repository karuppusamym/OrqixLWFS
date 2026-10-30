CREATE TABLE saved_work_view (
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    id uuid NOT NULL,
    owner_id uuid NOT NULL REFERENCES subject(id),
    kind text NOT NULL CHECK (kind IN ('tasks','runs')),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    shared boolean NOT NULL DEFAULT false,
    filters jsonb NOT NULL,
    revision integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(workspace_id,id)
);
CREATE INDEX saved_work_view_owner ON saved_work_view(workspace_id,owner_id,kind);
GRANT SELECT, INSERT, UPDATE, DELETE ON saved_work_view TO ${app_role};
