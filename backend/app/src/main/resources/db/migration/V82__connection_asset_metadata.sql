-- PL01 shared assets (docs/build/S61): owner and archive state for a connection key. Connection definitions stay the
-- immutable, versioned records of V1; this optional header is created on the first owner or archive change. Without a
-- row the owner is the person who registered version 1 and the connection is not archived. Archiving never touches
-- bindings or published releases; it makes validation and publish of workflows that still reference the key fail.
CREATE TABLE connection_asset (
    workspace_id     uuid NOT NULL REFERENCES workspace(id),
    connection_key   text NOT NULL CHECK (connection_key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    owner_subject_id uuid NOT NULL REFERENCES subject(id),
    revision         integer NOT NULL DEFAULT 1 CHECK (revision >= 1),
    archived_at      timestamptz,
    updated_by       uuid NOT NULL REFERENCES subject(id),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_key)
);
GRANT SELECT, INSERT, UPDATE ON connection_asset TO ${app_role};
