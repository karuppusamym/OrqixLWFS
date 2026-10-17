-- DOC08: workspace registry of named, versioned document templates (docs/build/S52). A version is immutable once written;
-- changing a template adds a version. A workflow step still pins the template text it was published with, so a registry
-- change never alters a published release; the registry is a library the designer copies from, with provenance recorded
-- in the version history only.
CREATE TABLE document_template (
    id           uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    name         text NOT NULL CHECK (length(name) BETWEEN 1 AND 80 AND name = btrim(name)),
    description  text CHECK (description IS NULL OR length(description) <= 300),
    latest_version integer NOT NULL DEFAULT 1 CHECK (latest_version >= 1),
    archived_at  timestamptz,
    created_by   uuid NOT NULL REFERENCES subject(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id)
);
CREATE UNIQUE INDEX document_template_name_unique ON document_template (workspace_id, lower(name));

CREATE TABLE document_template_version (
    workspace_id uuid NOT NULL,
    template_id  uuid NOT NULL,
    version      integer NOT NULL CHECK (version >= 1),
    format       text NOT NULL CHECK (format IN ('pdf', 'docx')),
    body         text NOT NULL CHECK (length(body) BETWEEN 1 AND 16000),
    placeholders text[] NOT NULL,
    sha256       text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    change_note  text CHECK (change_note IS NULL OR length(change_note) <= 300),
    created_by   uuid NOT NULL REFERENCES subject(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (template_id, version),
    FOREIGN KEY (workspace_id, template_id) REFERENCES document_template (workspace_id, id)
);
GRANT SELECT, INSERT, UPDATE ON document_template TO ${app_role};
GRANT SELECT, INSERT ON document_template_version TO ${app_role};
