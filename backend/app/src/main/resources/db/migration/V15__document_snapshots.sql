-- Immutable captured bytes, scoped to an approved workspace connection; credentials and signed URLs are excluded.
CREATE TABLE document_snapshot (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    connection_key text NOT NULL,
    drive_id text NOT NULL,
    item_id text NOT NULL,
    etag text NOT NULL,
    content_sha256 char(64) NOT NULL,
    content_type text NOT NULL,
    metadata jsonb NOT NULL,
    content bytea NOT NULL CHECK (octet_length(content) <= 5242880),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    UNIQUE(workspace_id, connection_key, drive_id, item_id, etag, content_sha256),
    UNIQUE(workspace_id, id)
);
CREATE INDEX document_snapshot_expiry ON document_snapshot(workspace_id, expires_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON document_snapshot TO ${app_role};
