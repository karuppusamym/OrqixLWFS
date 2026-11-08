-- CG-S03 (docs/build/S141): workspace step templates (presets) next to the connector package templates of S133.
-- Created from a configured step; every change is a new immutable version; offered to the organization's other
-- workspaces when shared is set.
CREATE TABLE step_template (
    id              uuid PRIMARY KEY,
    workspace_id    uuid NOT NULL REFERENCES workspace (id),
    key             text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    name            text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    description     text CHECK (description IS NULL OR char_length(description) <= 500),
    step_type       text NOT NULL CHECK (char_length(step_type) BETWEEN 1 AND 40),
    operation       text CHECK (operation IS NULL OR char_length(operation) <= 200),
    shared          boolean NOT NULL DEFAULT false,
    status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    latest_version  integer NOT NULL CHECK (latest_version >= 1),
    created_by      uuid NOT NULL REFERENCES subject (id),
    created_at      timestamptz NOT NULL,
    updated_by      uuid NOT NULL REFERENCES subject (id),
    updated_at      timestamptz NOT NULL,
    UNIQUE (workspace_id, key)
);
CREATE INDEX step_template_shared ON step_template (shared) WHERE shared AND status = 'active';
GRANT SELECT, INSERT, UPDATE ON step_template TO ${app_role};

CREATE TABLE step_template_version (
    template_id  uuid NOT NULL REFERENCES step_template (id),
    version      integer NOT NULL CHECK (version >= 1),
    step         jsonb NOT NULL,
    sha256       text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    note         text CHECK (note IS NULL OR char_length(note) <= 500),
    created_by   uuid NOT NULL REFERENCES subject (id),
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (template_id, version)
);
GRANT SELECT, INSERT ON step_template_version TO ${app_role};
