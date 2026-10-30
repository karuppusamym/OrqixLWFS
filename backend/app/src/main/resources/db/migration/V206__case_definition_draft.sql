-- S126: the working copy of a case type. One draft per case key; it is saved freely, checked against every admission
-- rule without deploying, and published as the next immutable case_definition version. The draft stays after
-- publishing and becomes the working copy of the following version.
CREATE TABLE case_definition_draft (
    id                       uuid PRIMARY KEY,
    workspace_id             uuid NOT NULL REFERENCES workspace (id),
    key                      text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{1,62}$'),
    name                     text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    source_xml               text CHECK (source_xml IS NULL OR octet_length(source_xml) <= 65536),
    content                  jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
    version                  integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_by               uuid NOT NULL REFERENCES subject (id),
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_by               uuid NOT NULL REFERENCES subject (id),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    published_definition_id  uuid REFERENCES case_definition (id),
    published_draft_version  integer CHECK (published_draft_version IS NULL OR published_draft_version >= 1),
    UNIQUE (workspace_id, key),
    UNIQUE (workspace_id, id)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON case_definition_draft TO ${app_role};
