-- CG-S02 release bundles (docs/build/S147). A bundle is published in the same transaction as its workflow release and
-- lists every component the release pins (rule tables, forms, views, connections, document templates, child
-- workflows, the publication test suite) with its version and SHA-256. bundle_hash covers that list. A bundle is
-- immutable; promotion moves the whole list into a later-stage workspace as a unit.
CREATE TABLE release_bundle (
    id              uuid PRIMARY KEY,
    workspace_id    uuid NOT NULL REFERENCES workspace (id),
    workflow_id     uuid NOT NULL,
    release_id      uuid NOT NULL UNIQUE,
    bundle_hash     char(64) NOT NULL,
    components      jsonb NOT NULL CHECK (jsonb_typeof(components) = 'array'),
    -- Set when this bundle was published from a draft that a bundle promotion created.
    source_bundle_id   uuid,
    source_bundle_hash char(64),
    source_workspace_id uuid,
    published_by    uuid NOT NULL REFERENCES subject (id),
    published_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    FOREIGN KEY (workspace_id, release_id) REFERENCES workflow_release (workspace_id, id)
);
CREATE INDEX release_bundle_workflow ON release_bundle (workspace_id, workflow_id, published_at DESC);

-- One promotion of a bundle into another workspace of the same organization: what each component became there
-- (reused, created, new version, copied) and the draft revision that carries the rewritten pins.
CREATE TABLE release_bundle_promotion (
    id                  uuid PRIMARY KEY,
    organization_id     uuid NOT NULL,
    source_workspace_id uuid NOT NULL REFERENCES workspace (id),
    source_bundle_id    uuid NOT NULL REFERENCES release_bundle (id),
    source_bundle_hash  char(64) NOT NULL,
    target_workspace_id uuid NOT NULL REFERENCES workspace (id),
    target_workflow_id  uuid NOT NULL,
    target_draft_revision integer NOT NULL CHECK (target_draft_revision >= 1),
    created_target      boolean NOT NULL,
    components          jsonb NOT NULL CHECK (jsonb_typeof(components) = 'array'),
    promoted_by         uuid NOT NULL REFERENCES subject (id),
    promoted_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (target_workspace_id, target_workflow_id) REFERENCES workflow (workspace_id, id)
);
CREATE INDEX release_bundle_promotion_target ON release_bundle_promotion (target_workspace_id, target_workflow_id, target_draft_revision);
CREATE INDEX release_bundle_promotion_source ON release_bundle_promotion (source_bundle_id);

GRANT SELECT, INSERT ON release_bundle, release_bundle_promotion TO ${app_role};
