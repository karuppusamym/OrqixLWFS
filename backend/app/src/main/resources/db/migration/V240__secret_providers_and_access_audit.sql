-- S132 (CG-C08 / CG-I04): connection bindings may name a secret in an external store by scheme, and every
-- resolution is counted per workspace, reference, provider and day. No secret value is ever stored here.

ALTER TABLE connection_binding DROP CONSTRAINT connection_binding_secret_ref_check;
ALTER TABLE connection_binding ADD CONSTRAINT connection_binding_secret_ref_check CHECK (secret_ref ~
    '^([a-z0-9][a-z0-9-]{0,62}|(vault|aws-sm|azure-kv|gcp-sm):[A-Za-z0-9][A-Za-z0-9/_.+=@:-]{0,255}(#[A-Za-z0-9_.-]{1,128})?)$');

CREATE TABLE secret_access_count (
    -- 00000000-0000-0000-0000-000000000000 for platform resolutions that no workspace asked for
    workspace_id     uuid NOT NULL,
    secret_ref       text NOT NULL CHECK (length(secret_ref) <= 400),
    provider         text NOT NULL CHECK (provider IN ('file', 'vault', 'aws-sm', 'azure-kv', 'gcp-sm')),
    day              date NOT NULL,
    resolutions      bigint NOT NULL DEFAULT 0 CHECK (resolutions >= 0),
    cache_hits       bigint NOT NULL DEFAULT 0 CHECK (cache_hits >= 0),
    not_found        bigint NOT NULL DEFAULT 0 CHECK (not_found >= 0),
    failures         bigint NOT NULL DEFAULT 0 CHECK (failures >= 0),
    last_resolved_at timestamptz,
    last_failed_at   timestamptz,
    PRIMARY KEY (workspace_id, secret_ref, provider, day)
);

CREATE INDEX secret_access_count_day ON secret_access_count (workspace_id, day DESC);

GRANT SELECT, INSERT, UPDATE ON secret_access_count TO ${app_role};
