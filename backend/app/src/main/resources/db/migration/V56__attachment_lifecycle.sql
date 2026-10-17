-- DOC08: attachment quarantine and retention (docs/build/S52).
-- Quarantine is an operator action that blocks download (and OCR) while the bytes are kept for review; release reverses
-- it. Retention is a per-workspace number of days after a request ended; the sweep soft-removes (bytes deleted, metadata
-- kept) files of ended requests older than that, never a quarantined file. A system purge has no deleting subject.
ALTER TABLE run_attachment
    ADD COLUMN quarantined_at timestamptz,
    ADD COLUMN quarantined_by uuid REFERENCES subject(id),
    ADD COLUMN quarantine_reason text CHECK (quarantine_reason IS NULL OR length(quarantine_reason) BETWEEN 3 AND 300),
    ADD COLUMN purged_at timestamptz;
ALTER TABLE run_attachment DROP CONSTRAINT run_attachment_deleted;
ALTER TABLE run_attachment ADD CONSTRAINT run_attachment_deleted
    CHECK (deleted_by IS NULL OR deleted_at IS NOT NULL);
ALTER TABLE run_attachment ADD CONSTRAINT run_attachment_quarantine
    CHECK ((quarantined_at IS NULL) = (quarantined_by IS NULL) AND (quarantined_at IS NULL) = (quarantine_reason IS NULL));
ALTER TABLE run_attachment ADD CONSTRAINT run_attachment_purged
    CHECK (purged_at IS NULL OR (deleted_at IS NOT NULL AND deleted_by IS NULL));

CREATE TABLE workspace_attachment_policy (
    workspace_id   uuid PRIMARY KEY REFERENCES workspace(id),
    retention_days integer CHECK (retention_days IS NULL OR retention_days BETWEEN 1 AND 3650),
    version        integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by     uuid NOT NULL REFERENCES subject(id),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE ON workspace_attachment_policy TO ${app_role};
