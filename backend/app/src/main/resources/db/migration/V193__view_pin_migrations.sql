-- UI02 / S114: one append-only record per reviewed pin migration. The holders themselves change through their own
-- services (new workflow and page draft revisions, a new case definition version, new worklist or route revisions);
-- this row records who moved which pins of a view to which published version, with the reviewed plan hash and reason.
CREATE TABLE application_view_pin_migration (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    view_id uuid NOT NULL,
    target_version integer NOT NULL CHECK (target_version >= 1),
    plan_hash char(64) NOT NULL CHECK (plan_hash ~ '^[0-9a-f]{64}$'),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500 AND reason = btrim(reason)),
    items jsonb NOT NULL CHECK (jsonb_typeof(items) = 'array' AND jsonb_array_length(items) BETWEEN 1 AND 50),
    applied_by uuid NOT NULL REFERENCES subject (id),
    applied_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, view_id) REFERENCES application_view (workspace_id, id),
    FOREIGN KEY (view_id, target_version) REFERENCES application_view_version (view_id, version)
);
CREATE INDEX application_view_pin_migration_view ON application_view_pin_migration (workspace_id, view_id, applied_at DESC);
GRANT SELECT, INSERT ON application_view_pin_migration TO ${app_role};
