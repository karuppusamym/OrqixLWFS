-- S140 (CG-F05): guest response forms. A person outside the organization answers a "Wait for a message" step whose
-- release declares config.guestResponse, through a one-time link that expires. Lookups (CG-F02) keep nothing in the
-- database: their answers live only in memory caches.

-- Email domains a guest link may be sent to (D-CG7). Checked when a link is sent, not when the workflow is published.
CREATE TABLE guest_domain (
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    domain       text NOT NULL CHECK (domain ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$'
                                     AND length(domain) <= 253),
    added_by     uuid NOT NULL REFERENCES subject (id),
    added_at     timestamptz NOT NULL,
    PRIMARY KEY (workspace_id, domain)
);

-- One row per waiting step of a request with a guest form. The link token is stored only as its SHA-256 hex; the clear
-- token is shown once to the operator who issued it, or only in the email. A sent link records the recipient's domain
-- (never the address). The submitted answer is kept so a repeat of the same request replays it.
CREATE TABLE guest_response (
    id                  uuid PRIMARY KEY,
    workspace_id        uuid NOT NULL REFERENCES workspace (id),
    run_id              uuid NOT NULL,
    node_id             text NOT NULL,
    subscription_id     uuid NOT NULL UNIQUE REFERENCES message_subscription (id),
    link_valid_for      interval NOT NULL CHECK (link_valid_for BETWEEN interval '1 hour' AND interval '30 days'),
    send_state          text NOT NULL CHECK (send_state IN ('manual', 'pending', 'sent', 'refused', 'failed')),
    send_attempts       integer NOT NULL DEFAULT 0 CHECK (send_attempts BETWEEN 0 AND 10),
    sent_domain         text CHECK (sent_domain IS NULL OR length(sent_domain) <= 253),
    token_sha256        char(64) UNIQUE,
    token_issued_at     timestamptz,
    token_expires_at    timestamptz,
    token_issued_by     uuid REFERENCES subject (id),
    tokens_issued       integer NOT NULL DEFAULT 0 CHECK (tokens_issued >= 0),
    revoked_at          timestamptz,
    first_viewed_at     timestamptz,
    expiry_audited      boolean NOT NULL DEFAULT false,
    submitted_at        timestamptz,
    submit_idem_key     text CHECK (submit_idem_key IS NULL OR length(submit_idem_key) BETWEEN 1 AND 200),
    submit_request_hash char(64),
    submit_result       jsonb,
    version             integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at          timestamptz NOT NULL,
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    UNIQUE (run_id, node_id),
    CONSTRAINT guest_response_token_complete CHECK (
        (token_sha256 IS NULL) OR (token_issued_at IS NOT NULL AND token_expires_at IS NOT NULL)),
    CONSTRAINT guest_response_submit_complete CHECK (
        (submitted_at IS NULL) = (submit_result IS NULL) AND (submitted_at IS NULL) = (submit_request_hash IS NULL))
);
CREATE INDEX guest_response_by_workspace ON guest_response (workspace_id, created_at DESC);
CREATE INDEX guest_response_by_run ON guest_response (workspace_id, run_id);
-- The sender picks links waiting to be emailed; the sweeper audits expiry once.
CREATE INDEX guest_response_pending ON guest_response (created_at) WHERE send_state = 'pending';
CREATE INDEX guest_response_expiring ON guest_response (token_expires_at)
    WHERE token_sha256 IS NOT NULL AND submitted_at IS NULL AND NOT expiry_audited;

-- Files a guest uploaded through a link and has not sent yet. Sending the form moves them to run_attachment (uploaded
-- by the guest subject below) and deletes these rows; files of a link that closed are deleted by the sweeper.
CREATE TABLE guest_response_file (
    id                uuid PRIMARY KEY,
    guest_response_id uuid NOT NULL REFERENCES guest_response (id),
    token_sha256      char(64) NOT NULL,
    file_name         text NOT NULL CHECK (length(file_name) BETWEEN 1 AND 120),
    content_type      text NOT NULL CHECK (length(content_type) BETWEEN 3 AND 127),
    size_bytes        bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256            text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    scan_status       text NOT NULL CHECK (scan_status IN ('notScanned', 'clean', 'infected', 'error')),
    content           bytea NOT NULL,
    created_at        timestamptz NOT NULL
);
CREATE INDEX guest_response_file_by_link ON guest_response_file (guest_response_id, created_at);

-- The uploader of guest files on the request (run_attachment.uploaded_by needs a subject). It never signs in: the
-- issuer is not an identity provider, and it holds no role or grant.
INSERT INTO subject (id, issuer, external_subject, display_name, email, first_seen_at)
VALUES ('00000000-0000-4000-8000-00000000f140', 'orqix:guest', 'guest-response', 'Guest (response link)', NULL, now())
ON CONFLICT DO NOTHING;

GRANT SELECT, INSERT, UPDATE, DELETE ON guest_domain TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON guest_response TO ${app_role};
GRANT SELECT, INSERT, DELETE ON guest_response_file TO ${app_role};
