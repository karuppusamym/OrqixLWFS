CREATE TABLE run_comment (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    run_id uuid NOT NULL,
    author_subject_id uuid NOT NULL REFERENCES subject(id),
    text text NOT NULL CHECK (length(btrim(text)) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run(workspace_id, id)
);
CREATE INDEX run_comments_page ON run_comment(workspace_id, run_id, id DESC);
GRANT SELECT, INSERT ON run_comment TO ${app_role};
