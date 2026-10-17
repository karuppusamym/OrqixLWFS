-- PL02: provenance of a release promoted to another workspace. A promotion creates a draft in the target (bindings and
-- secrets are never copied); the target publishes it through its normal gates. The report is the compatibility snapshot.
CREATE TABLE release_promotion (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    source_workspace_id uuid NOT NULL REFERENCES workspace(id),
    source_workspace_key text NOT NULL,
    source_workflow_key text NOT NULL,
    source_release_number integer NOT NULL,
    source_semantic_hash text NOT NULL,
    target_workspace_id uuid NOT NULL,
    target_workflow_id uuid NOT NULL,
    target_draft_revision integer NOT NULL,
    created_target boolean NOT NULL,
    report jsonb NOT NULL,
    promoted_by uuid NOT NULL REFERENCES subject(id),
    promoted_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (target_workspace_id, target_workflow_id) REFERENCES workflow (workspace_id, id)
);
CREATE INDEX release_promotion_target ON release_promotion (target_workspace_id, target_workflow_id, promoted_at DESC);
CREATE INDEX release_promotion_source ON release_promotion (source_workspace_id, promoted_at DESC);
GRANT SELECT, INSERT ON release_promotion TO ${app_role};
