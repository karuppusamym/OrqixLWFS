-- PL02b (docs/build/S69): optional approval gate before a release is promoted into a production-stage workspace.
-- A gated target takes promotions only through a request that a second person, holding the explicit grant
-- workflow.approve-promotion in the target, approves. The database refuses a decision by the requester.

ALTER TABLE workspace_environment ADD COLUMN promotion_approval boolean NOT NULL DEFAULT false;

ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read','workflow.approve-promotion'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read','workflow.approve-promotion') OR workflow_id IS NULL);

CREATE TABLE promotion_request (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    source_workspace_id uuid NOT NULL REFERENCES workspace(id),
    source_workspace_key text NOT NULL,
    source_workflow_id uuid NOT NULL,
    source_workflow_key text NOT NULL,
    source_release_id uuid NOT NULL,
    source_release_number integer NOT NULL,
    target_workspace_id uuid NOT NULL REFERENCES workspace(id),
    expected_draft_revision integer,
    status text NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled')),
    request_note text CHECK (request_note IS NULL OR char_length(request_note) <= 500),
    report jsonb NOT NULL,
    requested_by uuid NOT NULL REFERENCES subject(id),
    requested_at timestamptz NOT NULL DEFAULT now(),
    decided_by uuid REFERENCES subject(id),
    decided_at timestamptz,
    decision_note text CHECK (decision_note IS NULL OR char_length(decision_note) <= 500),
    promotion_id uuid REFERENCES release_promotion(id),
    -- Separation of duties: nobody approves or rejects their own request (they cancel it instead).
    CONSTRAINT promotion_request_separation CHECK (status NOT IN ('approved', 'rejected') OR decided_by <> requested_by),
    CONSTRAINT promotion_request_approved_has_promotion CHECK ((status = 'approved') = (promotion_id IS NOT NULL))
);
CREATE UNIQUE INDEX promotion_request_one_pending ON promotion_request (target_workspace_id, source_workflow_key) WHERE status = 'pending';
CREATE INDEX promotion_request_target ON promotion_request (target_workspace_id, requested_at DESC);
GRANT SELECT, INSERT, UPDATE ON promotion_request TO ${app_role};

ALTER TABLE release_promotion ADD COLUMN approved_by uuid REFERENCES subject(id);
ALTER TABLE release_promotion ADD COLUMN approval_request_id uuid;
