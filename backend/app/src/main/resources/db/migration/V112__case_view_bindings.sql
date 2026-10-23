-- UI02: reusable case-data presentation, copied into immutable case definition versions.
ALTER TABLE application_view DROP CONSTRAINT application_view_kind_check;
ALTER TABLE application_view ADD CONSTRAINT application_view_kind_check CHECK (kind IN ('request','approval','case'));

CREATE TABLE case_definition_view (
    definition_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    view_id uuid NOT NULL,
    view_version integer NOT NULL,
    definition jsonb NOT NULL CHECK (jsonb_typeof(definition)='object' AND octet_length(definition::text)<=65536),
    FOREIGN KEY(workspace_id,definition_id) REFERENCES case_definition(workspace_id,id),
    FOREIGN KEY(workspace_id,view_id) REFERENCES application_view(workspace_id,id),
    FOREIGN KEY(view_id,view_version) REFERENCES application_view_version(view_id,version)
);
GRANT SELECT, INSERT ON case_definition_view TO ${app_role};
