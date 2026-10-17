-- UI03: threaded discussion on a workflow step or connection (docs/build/S53). One level of replies. Comments are
-- review notes only: they never change the workflow, grant anything or block publishing. element_id is a node or
-- transition id and is not a foreign key, because the element may later be deleted while its history stays readable.
CREATE TABLE workflow_element_comment (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    element_id text NOT NULL CHECK (length(element_id) BETWEEN 1 AND 128),
    parent_id uuid,
    body text NOT NULL CHECK (length(body) BETWEEN 1 AND 2000),
    author_subject_id uuid NOT NULL REFERENCES subject(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by uuid REFERENCES subject(id),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    UNIQUE (workflow_id, id),
    FOREIGN KEY (workflow_id, parent_id) REFERENCES workflow_element_comment (workflow_id, id),
    CHECK (parent_id IS NULL OR (resolved_at IS NULL AND resolved_by IS NULL))
);
CREATE INDEX workflow_element_comment_element ON workflow_element_comment (workflow_id, element_id, created_at);
GRANT SELECT, INSERT, UPDATE ON workflow_element_comment TO ${app_role};
