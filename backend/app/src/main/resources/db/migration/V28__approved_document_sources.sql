ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start', 'connection.manage', 'message.send', 'document.browse'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage', 'message.send', 'document.browse') OR workflow_id IS NULL);

CREATE TABLE document_source (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    label text NOT NULL CHECK (length(label) BETWEEN 1 AND 100),
    connection_key text NOT NULL,
    definition_version integer NOT NULL,
    browse_operation text NOT NULL,
    versions_operation text NOT NULL,
    drive_id text NOT NULL CHECK (length(drive_id) BETWEEN 1 AND 200),
    folder_id text NOT NULL CHECK (length(folder_id) BETWEEN 1 AND 200),
    audience jsonb NOT NULL CHECK (jsonb_typeof(audience) = 'array' AND jsonb_array_length(audience) BETWEEN 1 AND 100),
    enabled boolean NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    updated_by uuid NOT NULL REFERENCES subject(id),
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (workspace_id, connection_key, definition_version) REFERENCES connection_definition(workspace_id, key, version)
);
CREATE INDEX document_source_workspace ON document_source(workspace_id, label, id);
GRANT SELECT, INSERT, UPDATE ON document_source TO ${app_role};
