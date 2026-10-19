ALTER TABLE connection_binding DROP CONSTRAINT connection_binding_auth_type_check;
ALTER TABLE connection_binding ADD CONSTRAINT connection_binding_auth_type_check
    CHECK (auth_type IN ('none','bearerToken','apiKeyHeader','oauth2ClientCredentials','oauth2AuthorizationCode'));
ALTER TABLE connection_binding DROP CONSTRAINT connection_binding_auth_shape;
ALTER TABLE connection_binding ADD CONSTRAINT connection_binding_auth_shape CHECK (
    (auth_type = 'none' AND secret_ref IS NULL AND auth_header_name IS NULL)
 OR (auth_type IN ('bearerToken','oauth2ClientCredentials','oauth2AuthorizationCode') AND secret_ref IS NOT NULL AND auth_header_name IS NULL)
 OR (auth_type = 'apiKeyHeader' AND secret_ref IS NOT NULL AND auth_header_name IS NOT NULL));

CREATE TABLE oauth_grant (
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    secret_ref text NOT NULL,
    status text NOT NULL CHECK (status IN ('consentRequired','connected','refreshing','disconnected')),
    config_hash char(64),
    key_id text,
    ciphertext text,
    expires_at timestamptz,
    refresh_started_at timestamptz,
    revision integer NOT NULL DEFAULT 0,
    PRIMARY KEY (workspace_id, secret_ref),
    CHECK ((status IN ('connected','refreshing') AND ciphertext IS NOT NULL AND key_id IS NOT NULL AND expires_at IS NOT NULL)
        OR (status IN ('consentRequired','disconnected') AND ciphertext IS NULL AND key_id IS NULL AND expires_at IS NULL))
);
CREATE TABLE oauth_consent (
    state_hash char(64) PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    connection_key text NOT NULL,
    secret_ref text NOT NULL,
    subject_id uuid NOT NULL REFERENCES subject(id),
    config_hash char(64) NOT NULL,
    grant_revision integer NOT NULL,
    binding_revision integer NOT NULL,
    key_id text NOT NULL,
    verifier_ciphertext text NOT NULL,
    expires_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('pending','exchanging','succeeded','failed')),
    code_hash char(64)
);
CREATE INDEX oauth_consent_expiry ON oauth_consent(expires_at);
GRANT SELECT, INSERT, UPDATE ON oauth_grant TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON oauth_consent TO ${app_role};
