-- UI02 / S111: one row per chain request so a retried Idempotency-Key replays the recorded step results
-- instead of running the chain again. Each step also carries a derived key into its own operation.
CREATE TABLE application_page_chain_run (
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    subject_id uuid NOT NULL REFERENCES subject (id),
    idem_key text NOT NULL CHECK (idem_key ~ '^[A-Za-z0-9._:-]{8,128}$'),
    request_hash char(64) NOT NULL,
    slug text NOT NULL,
    chain_id text NOT NULL,
    result jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    PRIMARY KEY (workspace_id, subject_id, idem_key),
    CHECK ((result IS NULL) = (completed_at IS NULL))
);
GRANT SELECT, INSERT, UPDATE ON application_page_chain_run TO ${app_role};
