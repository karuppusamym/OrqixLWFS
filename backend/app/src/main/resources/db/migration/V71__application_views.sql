-- UI02: workspace-owned draft views and immutable published snapshots.
CREATE TABLE application_view (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 80 AND name = btrim(name)),
    description text CHECK (description IS NULL OR length(description) <= 300),
    kind text NOT NULL CHECK (kind IN ('request', 'approval')),
    draft_revision integer NOT NULL DEFAULT 1 CHECK (draft_revision >= 1),
    draft_definition jsonb NOT NULL,
    published_version integer CHECK (published_version IS NULL OR published_version >= 1),
    published_draft_revision integer CHECK (published_draft_revision IS NULL OR published_draft_revision >= 1),
    created_by uuid NOT NULL REFERENCES subject(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id)
);
CREATE UNIQUE INDEX application_view_name_unique ON application_view (workspace_id, lower(name));

CREATE TABLE application_view_version (
    workspace_id uuid NOT NULL,
    view_id uuid NOT NULL,
    version integer NOT NULL CHECK (version >= 1),
    name text NOT NULL,
    description text,
    definition jsonb NOT NULL,
    published_by uuid NOT NULL REFERENCES subject(id),
    published_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (view_id, version),
    FOREIGN KEY (workspace_id, view_id) REFERENCES application_view (workspace_id, id)
);
GRANT SELECT, INSERT, UPDATE ON application_view TO ${app_role};
GRANT SELECT, INSERT ON application_view_version TO ${app_role};
