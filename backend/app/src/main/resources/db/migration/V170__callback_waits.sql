-- S98 (EX10): a "Wait for a message" step answered by an external system. One row per waiting step of a request
-- (its message_subscription). The callback token is stored only as its SHA-256 hex; the clear token is returned once,
-- when it is issued. A resumed wait keeps the idempotency key, request hash and result body so replays return the
-- original answer. No correlation key or message payload is stored here.
CREATE TABLE callback_wait (
    id                  uuid PRIMARY KEY,
    workspace_id        uuid NOT NULL REFERENCES workspace (id),
    run_id              uuid NOT NULL,
    node_id             text NOT NULL,
    subscription_id     uuid NOT NULL UNIQUE REFERENCES message_subscription (id),
    token_valid_for     interval NOT NULL CHECK (token_valid_for BETWEEN interval '1 minute' AND interval '30 days'),
    token_sha256        char(64) UNIQUE,
    token_issued_at     timestamptz,
    token_expires_at    timestamptz,
    token_issued_by     uuid REFERENCES subject (id),
    tokens_issued       integer NOT NULL DEFAULT 0 CHECK (tokens_issued >= 0),
    expiry_audited      boolean NOT NULL DEFAULT false,
    resumed_at          timestamptz,
    resume_idem_key     text CHECK (resume_idem_key IS NULL OR length(resume_idem_key) BETWEEN 1 AND 200),
    resume_request_hash char(64),
    resume_result       jsonb,
    version             integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at          timestamptz NOT NULL,
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    UNIQUE (run_id, node_id),
    CONSTRAINT callback_wait_token_complete CHECK (
        (token_sha256 IS NULL) OR (token_issued_at IS NOT NULL AND token_expires_at IS NOT NULL)),
    CONSTRAINT callback_wait_resume_complete CHECK (
        (resumed_at IS NULL) = (resume_result IS NULL) AND (resumed_at IS NULL) = (resume_request_hash IS NULL))
);
CREATE INDEX callback_wait_by_workspace ON callback_wait (workspace_id, created_at DESC);
-- The expiry sweeper looks for issued, unresumed tokens whose time passed and that were not audited yet.
CREATE INDEX callback_wait_expiring ON callback_wait (token_expires_at)
    WHERE token_sha256 IS NOT NULL AND resumed_at IS NULL AND NOT expiry_audited;

GRANT SELECT, INSERT, UPDATE ON callback_wait TO ${app_role};
