-- UI02 / S111: application pages are a view kind; routes pin one exact published page version.
ALTER TABLE application_view DROP CONSTRAINT application_view_kind_check;
ALTER TABLE application_view ADD CONSTRAINT application_view_kind_check
    CHECK (kind IN ('request', 'approval', 'case', 'worklist', 'page'));

CREATE TABLE application_page_route (
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    slug text NOT NULL CHECK (slug ~ '^[a-z][a-z0-9-]{0,38}[a-z0-9]$'),
    revision integer NOT NULL CHECK (revision >= 1),
    view_id uuid,
    view_version integer,
    title text,
    changed_by uuid NOT NULL REFERENCES subject (id),
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, slug),
    CHECK ((view_id IS NULL AND view_version IS NULL AND title IS NULL)
        OR (view_id IS NOT NULL AND view_version IS NOT NULL AND title IS NOT NULL)),
    FOREIGN KEY (workspace_id, view_id) REFERENCES application_view (workspace_id, id),
    FOREIGN KEY (view_id, view_version) REFERENCES application_view_version (view_id, version)
);

-- Append-only: the application role cannot rewrite or delete earlier assignments.
CREATE TABLE application_page_route_history (
    workspace_id uuid NOT NULL,
    slug text NOT NULL,
    revision integer NOT NULL CHECK (revision >= 1),
    view_id uuid,
    view_version integer,
    changed_by uuid NOT NULL REFERENCES subject (id),
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, slug, revision),
    FOREIGN KEY (workspace_id, slug) REFERENCES application_page_route (workspace_id, slug),
    FOREIGN KEY (view_id, view_version) REFERENCES application_view_version (view_id, version)
);

-- A caller's own saved request values for one request component of a page (the saveDraft chain step).
CREATE TABLE application_page_draft (
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    view_id uuid NOT NULL,
    component_id text NOT NULL CHECK (component_id ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$'),
    subject_id uuid NOT NULL REFERENCES subject (id),
    draft_values jsonb NOT NULL CHECK (jsonb_typeof(draft_values) = 'object' AND octet_length(draft_values::text) <= 32768),
    saved_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, view_id, component_id, subject_id),
    FOREIGN KEY (workspace_id, view_id) REFERENCES application_view (workspace_id, id)
);

GRANT SELECT, INSERT, UPDATE ON application_page_route TO ${app_role};
GRANT SELECT, INSERT ON application_page_route_history TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON application_page_draft TO ${app_role};
