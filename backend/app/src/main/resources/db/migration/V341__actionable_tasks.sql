-- CG-T01 (docs/build/S141): actionable tasks in Teams, Slack and email. Delivery goes only to the local stub
-- transport in this build. Off by default per workspace (no row = off).
CREATE TABLE task_action_setting (
    workspace_id  uuid PRIMARY KEY REFERENCES workspace (id),
    enabled       boolean NOT NULL,
    link_hours    integer NOT NULL CHECK (link_hours BETWEEN 1 AND 168),
    version       integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by    uuid NOT NULL REFERENCES subject (id),
    updated_at    timestamptz NOT NULL
);
GRANT SELECT, INSERT, UPDATE ON task_action_setting TO ${app_role};

-- One row per channel. email: sender and exact recipient domains; teams/slack: the reference of the secret that
-- signs inbound interactions (Teams outgoing-webhook HMAC, Slack signing secret). Never a secret value.
CREATE TABLE task_action_channel (
    workspace_id  uuid NOT NULL REFERENCES workspace (id),
    channel       text NOT NULL CHECK (channel IN ('email', 'teams', 'slack')),
    enabled       boolean NOT NULL,
    config        jsonb NOT NULL,
    secret_ref    text CHECK (secret_ref IS NULL OR secret_ref ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    PRIMARY KEY (workspace_id, channel),
    CONSTRAINT task_action_channel_secret CHECK ((channel = 'email') = (secret_ref IS NULL))
);
GRANT SELECT, INSERT, UPDATE, DELETE ON task_action_channel TO ${app_role};

-- The chat platform's user mapped to the Orqix subject (one person per platform user, one user per person).
CREATE TABLE chat_identity (
    workspace_id      uuid NOT NULL REFERENCES workspace (id),
    platform          text NOT NULL CHECK (platform IN ('teams', 'slack')),
    platform_user_id  text NOT NULL CHECK (char_length(platform_user_id) BETWEEN 1 AND 128),
    subject_id        uuid NOT NULL REFERENCES subject (id),
    linked_by         uuid NOT NULL REFERENCES subject (id),
    linked_at         timestamptz NOT NULL,
    PRIMARY KEY (workspace_id, platform, platform_user_id),
    UNIQUE (workspace_id, platform, subject_id)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_identity TO ${app_role};

-- Outbox: one message per task, person, channel and reason; sent by the worker after rechecking eligibility.
CREATE TABLE task_action_message (
    id            uuid PRIMARY KEY,
    workspace_id  uuid NOT NULL,
    task_id       text NOT NULL,
    subject_id    uuid NOT NULL REFERENCES subject (id),
    channel       text NOT NULL CHECK (channel IN ('email', 'teams', 'slack')),
    reason        text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 40),
    status        text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'sent', 'skipped')),
    skip_code     text CHECK (skip_code IS NULL OR char_length(skip_code) <= 64),
    link_id       uuid,
    created_at    timestamptz NOT NULL,
    sent_at       timestamptz,
    UNIQUE (workspace_id, task_id, subject_id, channel, reason),
    FOREIGN KEY (workspace_id, task_id) REFERENCES task_context (workspace_id, engine_task_id)
);
CREATE INDEX task_action_message_queued ON task_action_message (created_at) WHERE status = 'queued';
GRANT SELECT, INSERT, UPDATE ON task_action_message TO ${app_role};

-- Signed action links: the token carries the id and expiry and is signed with a deployment key; the row binds it to
-- one task and one person and allows revocation. The token itself is never stored.
CREATE TABLE task_action_link (
    id            uuid PRIMARY KEY,
    workspace_id  uuid NOT NULL,
    task_id       text NOT NULL,
    subject_id    uuid NOT NULL REFERENCES subject (id),
    channel       text NOT NULL CHECK (channel IN ('email', 'teams', 'slack')),
    issued_at     timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    used_count    integer NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    last_used_at  timestamptz,
    revoked_at    timestamptz,
    -- The decision taken through this link (approve or reject): a repeated post replays it instead of deciding again.
    decided_action text CHECK (decided_action IS NULL OR decided_action IN ('approve', 'reject')),
    decided_at    timestamptz,
    CHECK (expires_at > issued_at),
    FOREIGN KEY (workspace_id, task_id) REFERENCES task_context (workspace_id, engine_task_id)
);
CREATE INDEX task_action_link_task ON task_action_link (workspace_id, task_id);
GRANT SELECT, INSERT, UPDATE ON task_action_link TO ${app_role};

-- A chat decision waits for the person's confirmation (5 minutes). The reason text is kept only while pending.
CREATE TABLE task_action_confirmation (
    id                uuid PRIMARY KEY,
    workspace_id      uuid NOT NULL,
    task_id           text NOT NULL,
    subject_id        uuid NOT NULL REFERENCES subject (id),
    channel           text NOT NULL CHECK (channel IN ('teams', 'slack')),
    action            text NOT NULL CHECK (action IN ('approve', 'reject')),
    expected_version  integer NOT NULL CHECK (expected_version >= 1),
    reason            text CHECK (reason IS NULL OR char_length(reason) <= 2000),
    status            text NOT NULL CHECK (status IN ('pending', 'confirmed', 'expired', 'failed')),
    error_code        text CHECK (error_code IS NULL OR char_length(error_code) <= 64),
    created_at        timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL,
    decided_at        timestamptz,
    CONSTRAINT task_action_confirmation_reason CHECK (status = 'pending' OR reason IS NULL),
    FOREIGN KEY (workspace_id, task_id) REFERENCES task_context (workspace_id, engine_task_id)
);
GRANT SELECT, INSERT, UPDATE ON task_action_confirmation TO ${app_role};
