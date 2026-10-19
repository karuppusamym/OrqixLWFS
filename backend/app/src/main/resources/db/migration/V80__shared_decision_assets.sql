-- PL01 shared assets (docs/build/S61): workspace decision tables that several workflows can use. A version is immutable;
-- changing a shared decision adds a version. A workflow keeps a pinned copy of one version plus a versioned reference
-- (definition dependencies.assets) that is checked at validation and publish. The owner is a label: it grants nothing.
CREATE TABLE decision_asset (
    id               uuid PRIMARY KEY,
    workspace_id     uuid NOT NULL REFERENCES workspace(id),
    key              text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    name             text NOT NULL CHECK (length(name) BETWEEN 1 AND 80 AND name = btrim(name)),
    description      text CHECK (description IS NULL OR length(description) <= 300),
    owner_subject_id uuid NOT NULL REFERENCES subject(id),
    latest_version   integer NOT NULL DEFAULT 1 CHECK (latest_version >= 1),
    revision         integer NOT NULL DEFAULT 1 CHECK (revision >= 1),
    archived_at      timestamptz,
    created_by       uuid NOT NULL REFERENCES subject(id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, key),
    UNIQUE (workspace_id, id)
);
CREATE UNIQUE INDEX decision_asset_name_unique ON decision_asset (workspace_id, lower(name));

CREATE TABLE decision_asset_version (
    workspace_id uuid NOT NULL,
    asset_id     uuid NOT NULL,
    version      integer NOT NULL CHECK (version >= 1),
    content      jsonb NOT NULL,
    sha256       text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    change_note  text CHECK (change_note IS NULL OR length(change_note) <= 300),
    created_by   uuid NOT NULL REFERENCES subject(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_id, version),
    FOREIGN KEY (workspace_id, asset_id) REFERENCES decision_asset (workspace_id, id)
);
GRANT SELECT, INSERT, UPDATE ON decision_asset TO ${app_role};
GRANT SELECT, INSERT ON decision_asset_version TO ${app_role};
