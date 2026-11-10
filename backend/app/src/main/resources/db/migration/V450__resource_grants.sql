-- CG-I01 (docs/build/S151): grants on one folder, workflow, shared decision table or connection, next to the
-- workspace-wide role bindings and grants. A resource grant only ever adds one permission on one resource: it never
-- removes workspace-wide access and never applies to another resource. Folder grants cover the workflows filed in the
-- folder at the moment of the check (moving a workflow out of the folder ends the access). Permissions per type:
--   folder, workflow: workflow.read, workflow.edit, workflow.publish, workflow.start, run.read, run.operate
--   decision (decision_asset key): workflow.read, workflow.edit
--   connection (connection key): connection.read, connection.manage
-- resource_id is the folder or workflow ID as text, or the decision or connection key.
CREATE TABLE resource_grant (
    id              uuid PRIMARY KEY,
    workspace_id    uuid NOT NULL REFERENCES workspace (id),
    resource_type   text NOT NULL CHECK (resource_type IN ('folder', 'workflow', 'decision', 'connection')),
    resource_id     text NOT NULL CHECK (length(resource_id) BETWEEN 1 AND 64),
    permission      text NOT NULL,
    principal_type  text NOT NULL CHECK (principal_type IN ('subject', 'idpGroup')),
    principal_value text NOT NULL CHECK (length(principal_value) BETWEEN 1 AND 256),
    created_by      uuid NOT NULL REFERENCES subject (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT resource_grant_permission_fits_type CHECK (
        (resource_type IN ('folder', 'workflow') AND permission IN ('workflow.read', 'workflow.edit', 'workflow.publish',
                                                                    'workflow.start', 'run.read', 'run.operate'))
        OR (resource_type = 'decision' AND permission IN ('workflow.read', 'workflow.edit'))
        OR (resource_type = 'connection' AND permission IN ('connection.read', 'connection.manage'))),
    CONSTRAINT resource_grant_id_shape CHECK (
        (resource_type IN ('folder', 'workflow')
            AND resource_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
        OR (resource_type IN ('decision', 'connection') AND resource_id ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'))
);
CREATE UNIQUE INDEX resource_grant_unique ON resource_grant
    (workspace_id, resource_type, resource_id, permission, principal_type, principal_value);
CREATE INDEX resource_grant_principal ON resource_grant (workspace_id, principal_type, principal_value);

-- A deleted folder takes its grants with it (its workflows return to "unfiled" and lose the folder's grants).
CREATE FUNCTION resource_grant_folder_deleted() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM resource_grant WHERE workspace_id = OLD.workspace_id AND resource_type = 'folder'
                                 AND resource_id = OLD.id::text;
    RETURN OLD;
END;
$$;
CREATE TRIGGER workflow_folder_deleted_grants AFTER DELETE ON workflow_folder
    FOR EACH ROW EXECUTE FUNCTION resource_grant_folder_deleted();

GRANT SELECT, INSERT, DELETE ON resource_grant TO ${app_role};
