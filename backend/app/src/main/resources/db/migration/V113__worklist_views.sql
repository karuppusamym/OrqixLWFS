ALTER TABLE application_view DROP CONSTRAINT application_view_kind_check;
ALTER TABLE application_view ADD CONSTRAINT application_view_kind_check CHECK (kind IN ('request','approval','case','worklist'));
CREATE TABLE workspace_worklist_view (
    workspace_id uuid PRIMARY KEY REFERENCES workspace(id),
    revision integer NOT NULL CHECK (revision>=0),
    view_id uuid,
    view_version integer,
    definition jsonb,
    CHECK ((view_id IS NULL AND view_version IS NULL AND definition IS NULL) OR (view_id IS NOT NULL AND view_version IS NOT NULL AND definition IS NOT NULL)),
    FOREIGN KEY(workspace_id,view_id) REFERENCES application_view(workspace_id,id),
    FOREIGN KEY(view_id,view_version) REFERENCES application_view_version(view_id,version)
);
CREATE TABLE worklist_view_history (
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    revision integer NOT NULL CHECK (revision>0),
    binding jsonb,
    changed_by uuid NOT NULL REFERENCES subject(id),
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(workspace_id,revision)
);
GRANT SELECT,INSERT,UPDATE ON workspace_worklist_view TO ${app_role};
GRANT SELECT,INSERT ON worklist_view_history TO ${app_role};
