-- S99 (IN05b): outbound webhooks. A workspace subscribes an HTTPS endpoint (egress allow-list) to request events; a
-- delivery row is written in the same transaction as the run event (transactional outbox) and sent by a dispatcher
-- that claims rows with FOR UPDATE SKIP LOCKED and a lease. The payload holds ids and states only. The response body
-- of the receiver is never stored, only its status code.
CREATE TABLE webhook_subscription (
    id            uuid PRIMARY KEY,
    workspace_id  uuid NOT NULL REFERENCES workspace (id),
    name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    url           text NOT NULL CHECK (length(url) BETWEEN 9 AND 2000),
    secret_ref    text NOT NULL CHECK (secret_ref ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    events        text[] NOT NULL CHECK (cardinality(events) BETWEEN 1 AND 5
        AND events <@ ARRAY['request.started', 'task.opened', 'request.completed', 'request.needs-attention',
                            'callback.waiting']::text[]),
    enabled       boolean NOT NULL DEFAULT true,
    version       integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_by    uuid NOT NULL REFERENCES subject (id),
    created_at    timestamptz NOT NULL,
    updated_by    uuid REFERENCES subject (id),
    updated_at    timestamptz
);
CREATE INDEX webhook_subscription_by_workspace ON webhook_subscription (workspace_id) WHERE enabled;

CREATE TABLE webhook_delivery (
    id                uuid PRIMARY KEY,
    workspace_id      uuid NOT NULL REFERENCES workspace (id),
    subscription_id   uuid NOT NULL REFERENCES webhook_subscription (id),
    event_key         text NOT NULL CHECK (length(event_key) BETWEEN 1 AND 200),
    event_type        text NOT NULL,
    run_id            uuid,
    payload           jsonb NOT NULL,
    status            text NOT NULL CHECK (status IN ('pending', 'sending', 'delivered', 'dead')),
    attempts          integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at   timestamptz NOT NULL,
    lease_until       timestamptz,
    last_status_code  integer,
    last_error        text CHECK (last_error IS NULL OR last_error ~ '^[a-z_]{1,40}$'),
    created_at        timestamptz NOT NULL,
    delivered_at      timestamptz,
    dead_at           timestamptz,
    replays           integer NOT NULL DEFAULT 0 CHECK (replays >= 0),
    UNIQUE (subscription_id, event_key)
);
CREATE INDEX webhook_delivery_due ON webhook_delivery (next_attempt_at) WHERE status IN ('pending', 'sending');
CREATE INDEX webhook_delivery_dead ON webhook_delivery (workspace_id, dead_at DESC) WHERE status = 'dead';
CREATE INDEX webhook_delivery_pending ON webhook_delivery (subscription_id) WHERE status IN ('pending', 'sending');

GRANT SELECT, INSERT, UPDATE ON webhook_subscription TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON webhook_delivery TO ${app_role};
