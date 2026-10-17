-- PL02: a bounded canary. At most one canary release per workflow receives a stated share (1-50%) of new starts; the
-- rest, and every start when no canary is set, use the active release. Runs stay pinned to the release they started on.
CREATE TABLE workflow_canary (
    workspace_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    release_id uuid NOT NULL,
    percent integer NOT NULL CHECK (percent BETWEEN 1 AND 50),
    set_by uuid NOT NULL REFERENCES subject(id),
    set_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, workflow_id),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    FOREIGN KEY (workflow_id, release_id) REFERENCES workflow_release (workflow_id, id)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON workflow_canary TO ${app_role};
