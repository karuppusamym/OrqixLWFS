-- S99 (IN05c): Kafka consumer triggers. Brokers come only from deployment configuration (a named cluster); a trigger
-- picks the cluster, an allow-listed topic and a consumer group, and either starts a workflow or sends a message to
-- waiting requests as its run-as person. Every handled record is kept once per (trigger, dedupe key) before its offset
-- is committed; the message value itself is never stored (only its SHA-256 and size).
CREATE TABLE kafka_trigger (
    id                  uuid PRIMARY KEY,
    workspace_id        uuid NOT NULL REFERENCES workspace (id),
    key                 text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    name                text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    cluster             text NOT NULL CHECK (cluster ~ '^[a-z][a-z0-9-]{0,39}$'),
    topic               text NOT NULL CHECK (topic ~ '^[A-Za-z0-9._-]{1,249}$'),
    consumer_group      text NOT NULL CHECK (consumer_group ~ '^[A-Za-z0-9._-]{1,200}$'),
    secret_ref          text CHECK (secret_ref IS NULL OR secret_ref ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    action              text NOT NULL CHECK (action IN ('start', 'resume')),
    workflow_id         uuid REFERENCES workflow (id),
    message_name        text CHECK (message_name IS NULL
        OR (message_name ~ '^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$' AND length(message_name) BETWEEN 2 AND 64)),
    correlation_pointer text CHECK (correlation_pointer IS NULL OR length(correlation_pointer) BETWEEN 1 AND 200),
    mapping             jsonb NOT NULL,
    batch_size          integer NOT NULL CHECK (batch_size BETWEEN 1 AND 100),
    max_per_second      integer NOT NULL CHECK (max_per_second BETWEEN 1 AND 200),
    start_from          text NOT NULL CHECK (start_from IN ('earliest', 'latest')),
    run_as              uuid NOT NULL REFERENCES subject (id),
    enabled             boolean NOT NULL DEFAULT true,
    version             integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_by          uuid NOT NULL REFERENCES subject (id),
    created_at          timestamptz NOT NULL,
    updated_by          uuid REFERENCES subject (id),
    updated_at          timestamptz,
    UNIQUE (workspace_id, key),
    CONSTRAINT kafka_trigger_action_shape CHECK (
        (action = 'start' AND workflow_id IS NOT NULL AND message_name IS NULL AND correlation_pointer IS NULL)
        OR (action = 'resume' AND workflow_id IS NULL AND message_name IS NOT NULL AND correlation_pointer IS NOT NULL))
);
-- Two triggers of one cluster must not share a consumer group: they would steal each other's partitions.
CREATE UNIQUE INDEX kafka_trigger_group ON kafka_trigger (cluster, consumer_group);

CREATE TABLE kafka_inbound_record (
    id               uuid PRIMARY KEY,
    workspace_id     uuid NOT NULL REFERENCES workspace (id),
    trigger_id       uuid NOT NULL REFERENCES kafka_trigger (id),
    dedupe_key       text NOT NULL CHECK (length(dedupe_key) BETWEEN 1 AND 300),
    topic_partition  integer NOT NULL CHECK (topic_partition >= 0),
    record_offset    bigint NOT NULL CHECK (record_offset >= 0),
    status           text NOT NULL CHECK (status IN ('started', 'resumed', 'buffered', 'dead')),
    error_code       text CHECK (error_code IS NULL OR error_code ~ '^[a-z_]{1,40}$'),
    run_id           uuid,
    message_id       uuid,
    value_sha256     char(64),
    value_bytes      integer NOT NULL CHECK (value_bytes >= 0),
    attempts         integer NOT NULL DEFAULT 1 CHECK (attempts >= 1),
    instance_id      text CHECK (instance_id IS NULL OR length(instance_id) BETWEEN 1 AND 100),
    received_at      timestamptz NOT NULL,
    UNIQUE (trigger_id, dedupe_key)
);
CREATE INDEX kafka_inbound_record_dead ON kafka_inbound_record (workspace_id, received_at DESC) WHERE status = 'dead';

-- One row per (trigger, replica): what this replica's consumer holds and how far behind it is.
CREATE TABLE kafka_trigger_status (
    trigger_id       uuid NOT NULL REFERENCES kafka_trigger (id),
    instance_id      text NOT NULL CHECK (length(instance_id) BETWEEN 1 AND 100),
    workspace_id     uuid NOT NULL REFERENCES workspace (id),
    partitions       integer[] NOT NULL DEFAULT '{}',
    lag              bigint NOT NULL DEFAULT 0 CHECK (lag >= 0),
    handled          bigint NOT NULL DEFAULT 0 CHECK (handled >= 0),
    duplicates       bigint NOT NULL DEFAULT 0 CHECK (duplicates >= 0),
    dead             bigint NOT NULL DEFAULT 0 CHECK (dead >= 0),
    running          boolean NOT NULL DEFAULT false,
    last_poll_at     timestamptz,
    last_error       text CHECK (last_error IS NULL OR last_error ~ '^[a-z_]{1,40}$'),
    last_error_at    timestamptz,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (trigger_id, instance_id)
);

GRANT SELECT, INSERT, UPDATE ON kafka_trigger TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON kafka_inbound_record TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON kafka_trigger_status TO ${app_role};
