-- IN02: outbound adapter definitions (email, Teams, JDBC read, Kafka produce) and signed inbound triggers (docs/build/S47).
-- No secret value is stored: only a secretRef that names a file in the secrets directory. Raw inbound payloads are not stored.
CREATE TABLE adapter_config (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    key text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    kind text NOT NULL CHECK (kind IN ('email', 'teams', 'jdbc', 'kafka')),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    secret_ref text CHECK (secret_ref IS NULL OR secret_ref ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    config jsonb NOT NULL CHECK (jsonb_typeof(config) = 'object' AND length(config::text) <= 8000),
    enabled boolean NOT NULL DEFAULT true,
    created_by uuid NOT NULL REFERENCES subject(id),
    created_at timestamptz NOT NULL,
    UNIQUE (workspace_id, key)
);

CREATE TABLE inbound_trigger (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    key text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    source text NOT NULL CHECK (source IN ('email', 'teams', 'jdbc', 'kafka')),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    workflow_id uuid NOT NULL REFERENCES workflow(id),
    secret_ref text NOT NULL CHECK (secret_ref ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    filter jsonb NOT NULL CHECK (jsonb_typeof(filter) = 'object'),
    mapping jsonb NOT NULL CHECK (jsonb_typeof(mapping) = 'object'),
    enabled boolean NOT NULL DEFAULT true,
    created_by uuid NOT NULL REFERENCES subject(id),
    created_at timestamptz NOT NULL,
    UNIQUE (workspace_id, key)
);

CREATE TABLE inbound_event (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    trigger_id uuid NOT NULL REFERENCES inbound_trigger(id),
    event_id text NOT NULL CHECK (length(event_id) BETWEEN 1 AND 100),
    payload_sha256 text NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload_bytes integer NOT NULL CHECK (payload_bytes >= 0),
    mapped_input jsonb NOT NULL CHECK (jsonb_typeof(mapped_input) = 'object'),
    status text NOT NULL CHECK (status IN ('received', 'started')),
    run_id uuid REFERENCES workflow_run(id),
    received_at timestamptz NOT NULL,
    started_by uuid REFERENCES subject(id),
    started_at timestamptz,
    UNIQUE (trigger_id, event_id)
);
CREATE INDEX inbound_event_by_workspace ON inbound_event (workspace_id, received_at DESC);

GRANT SELECT, INSERT ON adapter_config TO ${app_role};
GRANT SELECT, INSERT ON inbound_trigger TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON inbound_event TO ${app_role};
