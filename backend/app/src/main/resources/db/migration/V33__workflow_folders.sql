-- PL01: one flat level of named folders per workspace for organizing workflows (docs/build/S46). A folder is a label.
-- It grants and restricts nothing: every authorization decision is still made on the workflow itself. A workflow sits
-- in at most one folder; deleting a folder returns its workflows to "unfiled".
CREATE TABLE workflow_folder (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 80 AND name = btrim(name)),
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_by uuid NOT NULL REFERENCES subject(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id)
);
CREATE UNIQUE INDEX workflow_folder_name_unique ON workflow_folder (workspace_id, lower(name));

CREATE TABLE workflow_folder_item (
    workspace_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    folder_id uuid NOT NULL,
    assigned_by uuid NOT NULL REFERENCES subject(id),
    assigned_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, workflow_id),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    FOREIGN KEY (workspace_id, folder_id) REFERENCES workflow_folder (workspace_id, id) ON DELETE CASCADE
);
CREATE INDEX workflow_folder_item_folder ON workflow_folder_item (workspace_id, folder_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON workflow_folder, workflow_folder_item TO ${app_role};
