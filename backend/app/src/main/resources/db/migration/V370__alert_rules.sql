-- CG-O06 (docs/build/S144): workspace alert rules. A rule watches one operational signal (with an optional workflow or
-- connection scope), fires when the value reaches its threshold and resolves when it drops below. One open incident per
-- rule at a time (deduplication); a quiet period and an optional silence suppress repeat notifications. Notifications
-- go to destinations through the existing email/Teams adapters, the outbound webhooks (S99) or the Slack/PagerDuty
-- port (local stub only in this build). Messages carry the rule, signal, value and threshold, never request values.
-- Merge note: recreates both permission_grant checks with every grantable permission known on this branch plus
-- 'alert.manage' (as V290 did). A sibling migration adding grantable permissions must list all of them.
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read',
                          'workflow.approve-promotion','agent.approve-write-tool','connector.manage','connector.approve',
                          'ai.approve-skill','ai.evaluate','alert.manage'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read',
                              'workflow.approve-promotion','agent.approve-write-tool','connector.manage',
                              'connector.approve','ai.approve-skill','ai.evaluate','alert.manage') OR workflow_id IS NULL);

CREATE TABLE alert_destination (
    id                      uuid PRIMARY KEY,
    workspace_id            uuid NOT NULL REFERENCES workspace (id),
    name                    text NOT NULL CHECK (length(name) BETWEEN 1 AND 100 AND name = btrim(name)),
    kind                    text NOT NULL CHECK (kind IN ('email', 'teams', 'slack', 'pagerDuty', 'webhook')),
    adapter_key             text CHECK (adapter_key IS NULL OR adapter_key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    recipients              text[] CHECK (recipients IS NULL OR cardinality(recipients) BETWEEN 1 AND 10),
    secret_ref              text CHECK (secret_ref IS NULL OR secret_ref ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    webhook_subscription_id uuid REFERENCES webhook_subscription (id),
    enabled                 boolean NOT NULL DEFAULT true,
    version                 integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_by              uuid NOT NULL REFERENCES subject (id),
    created_at              timestamptz NOT NULL,
    updated_by              uuid REFERENCES subject (id),
    updated_at              timestamptz,
    UNIQUE (workspace_id, id),
    CONSTRAINT alert_destination_target CHECK (
        (kind = 'email' AND adapter_key IS NOT NULL AND recipients IS NOT NULL AND secret_ref IS NULL
            AND webhook_subscription_id IS NULL)
     OR (kind = 'teams' AND adapter_key IS NOT NULL AND recipients IS NULL AND secret_ref IS NULL
            AND webhook_subscription_id IS NULL)
     OR (kind IN ('slack', 'pagerDuty') AND adapter_key IS NULL AND recipients IS NULL AND secret_ref IS NOT NULL
            AND webhook_subscription_id IS NULL)
     OR (kind = 'webhook' AND adapter_key IS NULL AND recipients IS NULL AND secret_ref IS NULL
            AND webhook_subscription_id IS NOT NULL))
);
CREATE INDEX alert_destination_by_workspace ON alert_destination (workspace_id, name);

CREATE TABLE alert_rule (
    id                uuid PRIMARY KEY,
    workspace_id      uuid NOT NULL REFERENCES workspace (id),
    name              text NOT NULL CHECK (length(name) BETWEEN 1 AND 100 AND name = btrim(name)),
    signal            text NOT NULL CHECK (signal IN ('needsAttention', 'slaBreaches', 'circuitOpen',
                                                      'tokenRefreshFailing', 'deadLetters', 'pollChecksFailing',
                                                      'aiDriftFallback')),
    threshold         integer NOT NULL CHECK (threshold BETWEEN 1 AND 100000),
    window_minutes    integer NOT NULL CHECK (window_minutes BETWEEN 5 AND 1440),
    workflow_id       uuid,
    connection_key    text CHECK (connection_key IS NULL OR length(connection_key) BETWEEN 1 AND 64),
    severity          text NOT NULL CHECK (severity IN ('info', 'warning', 'critical')),
    quiet_minutes     integer NOT NULL CHECK (quiet_minutes BETWEEN 0 AND 1440),
    notify_resolve    boolean NOT NULL DEFAULT true,
    enabled           boolean NOT NULL DEFAULT true,
    silenced_until    timestamptz,
    state             text NOT NULL DEFAULT 'ok' CHECK (state IN ('ok', 'firing')),
    last_value        integer,
    last_evaluated_at timestamptz,
    next_evaluation_at timestamptz NOT NULL,
    last_notified_at  timestamptz,
    version           integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_by        uuid NOT NULL REFERENCES subject (id),
    created_at        timestamptz NOT NULL,
    updated_by        uuid REFERENCES subject (id),
    updated_at        timestamptz,
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    CONSTRAINT alert_rule_scope CHECK (NOT (workflow_id IS NOT NULL AND connection_key IS NOT NULL))
);
CREATE INDEX alert_rule_due ON alert_rule (next_evaluation_at) WHERE enabled;
CREATE INDEX alert_rule_by_workspace ON alert_rule (workspace_id, name);

CREATE TABLE alert_rule_destination (
    workspace_id   uuid NOT NULL,
    rule_id        uuid NOT NULL,
    destination_id uuid NOT NULL,
    PRIMARY KEY (rule_id, destination_id),
    FOREIGN KEY (workspace_id, rule_id) REFERENCES alert_rule (workspace_id, id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, destination_id) REFERENCES alert_destination (workspace_id, id)
);
CREATE INDEX alert_rule_destination_by_destination ON alert_rule_destination (destination_id);

-- One firing episode of a rule. A partial unique index keeps at most one open incident per rule.
CREATE TABLE alert_incident (
    id            uuid PRIMARY KEY,
    workspace_id  uuid NOT NULL,
    rule_id       uuid NOT NULL,
    status        text NOT NULL CHECK (status IN ('firing', 'resolved')),
    opened_at     timestamptz NOT NULL,
    resolved_at   timestamptz,
    opening_value integer NOT NULL,
    peak_value    integer NOT NULL,
    last_value    integer NOT NULL,
    threshold     integer NOT NULL,
    suppressed    text CHECK (suppressed IS NULL OR suppressed IN ('quietPeriod', 'silenced')),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, rule_id) REFERENCES alert_rule (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT alert_incident_resolved CHECK ((status = 'resolved') = (resolved_at IS NOT NULL))
);
CREATE UNIQUE INDEX alert_incident_one_open ON alert_incident (rule_id) WHERE status = 'firing';
CREATE INDEX alert_incident_by_workspace ON alert_incident (workspace_id, opened_at DESC, id DESC);

-- Outbox of notifications: one row per incident, destination and kind (fired or resolved), so a retried evaluation
-- never notifies twice. Test sends have no incident.
CREATE TABLE alert_delivery (
    id              uuid PRIMARY KEY,
    workspace_id    uuid NOT NULL,
    incident_id     uuid,
    destination_id  uuid NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('fired', 'resolved', 'test')),
    status          text NOT NULL CHECK (status IN ('queued', 'sent', 'failed', 'skipped')),
    attempts        integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    next_attempt_at timestamptz NOT NULL,
    outcome         text CHECK (outcome IS NULL OR outcome ~ '^[a-zA-Z_]{1,40}$'),
    error_code      text CHECK (error_code IS NULL OR error_code ~ '^[a-z_]{1,40}$'),
    created_at      timestamptz NOT NULL,
    settled_at      timestamptz,
    FOREIGN KEY (workspace_id, incident_id) REFERENCES alert_incident (workspace_id, id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, destination_id) REFERENCES alert_destination (workspace_id, id),
    CONSTRAINT alert_delivery_incident CHECK ((kind = 'test') = (incident_id IS NULL)),
    CONSTRAINT alert_delivery_settled CHECK ((status = 'queued') = (settled_at IS NULL))
);
CREATE UNIQUE INDEX alert_delivery_once ON alert_delivery (incident_id, destination_id, kind) WHERE incident_id IS NOT NULL;
CREATE INDEX alert_delivery_due ON alert_delivery (next_attempt_at) WHERE status = 'queued';
CREATE INDEX alert_delivery_by_incident ON alert_delivery (workspace_id, incident_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON alert_destination TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON alert_rule TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON alert_rule_destination TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON alert_incident TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON alert_delivery TO ${app_role};
