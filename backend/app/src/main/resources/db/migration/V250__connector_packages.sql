-- S133 (CG-C03, CG-C04, CG-C05, CG-C10): connector packages, their review and approval, installs and package triggers.
--
-- A package version is an organization-wide asset: imported through one workspace by a person holding connector.manage
-- there, approved (exact SHA-256 of the archive) by a different person holding the grant-only connector.approve, then
-- installable in any workspace of the organization. The archive bytes are immutable; approval, rejection and
-- retirement only change the status. Installing registers the package connections as ordinary connection versions of
-- the workspace (never bindings or credentials) and records which connection version came from which package version.

ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read',
                          'workflow.approve-promotion','agent.approve-write-tool','connector.manage','connector.approve'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read',
                              'workflow.approve-promotion','agent.approve-write-tool','connector.manage',
                              'connector.approve') OR workflow_id IS NULL);

CREATE TABLE connector_package_version (
    id                    uuid PRIMARY KEY,
    organization_id       uuid NOT NULL REFERENCES organization (id),
    package_id            text NOT NULL CHECK (package_id ~ '^[a-z][a-z0-9-]{0,40}(\.[a-z][a-z0-9-]{0,40}){1,5}$'
                                               AND length(package_id) <= 120),
    version               text NOT NULL CHECK (version ~ '^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})$'),
    name                  text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    publisher_name        text NOT NULL CHECK (length(publisher_name) BETWEEN 1 AND 80),
    publisher_key_id      text NOT NULL CHECK (length(publisher_key_id) BETWEEN 1 AND 64),
    sha256                char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    archive               bytea NOT NULL CHECK (octet_length(archive) BETWEEN 1 AND 5242880),
    manifest              jsonb NOT NULL,
    review                jsonb NOT NULL,
    signature_status      text NOT NULL CHECK (signature_status IN ('verified', 'unsigned')),
    unsigned_reason       text CHECK (unsigned_reason IS NULL OR length(unsigned_reason) BETWEEN 1 AND 500),
    status                text NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'retired')),
    imported_workspace_id uuid NOT NULL REFERENCES workspace (id),
    imported_by           uuid NOT NULL REFERENCES subject (id),
    imported_at           timestamptz NOT NULL DEFAULT now(),
    decided_by            uuid REFERENCES subject (id),
    decided_at            timestamptz,
    decision_note         text CHECK (decision_note IS NULL OR length(decision_note) BETWEEN 1 AND 500),
    retired_by            uuid REFERENCES subject (id),
    retired_at            timestamptz,
    retire_reason         text CHECK (retire_reason IS NULL OR length(retire_reason) BETWEEN 1 AND 500),
    revision              integer NOT NULL DEFAULT 1 CHECK (revision >= 1),
    UNIQUE (organization_id, package_id, version),
    CONSTRAINT connector_package_unsigned_reason CHECK ((signature_status = 'unsigned') = (unsigned_reason IS NOT NULL)),
    CONSTRAINT connector_package_decided CHECK (status = 'pending' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT connector_package_retired CHECK ((status = 'retired') = (retired_by IS NOT NULL AND retired_at IS NOT NULL))
);
CREATE INDEX connector_package_version_org ON connector_package_version (organization_id, package_id);
GRANT SELECT, INSERT, UPDATE ON connector_package_version TO ${app_role};

-- One row per install of a package version in a workspace. At most one active version of a package per workspace;
-- an upgrade marks the earlier install superseded in the same transaction.
CREATE TABLE connector_package_install (
    workspace_id       uuid NOT NULL REFERENCES workspace (id),
    package_version_id uuid NOT NULL REFERENCES connector_package_version (id),
    package_id         text NOT NULL,
    version            text NOT NULL,
    status             text NOT NULL CHECK (status IN ('active', 'superseded', 'retired')),
    installed_by       uuid NOT NULL REFERENCES subject (id),
    installed_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, package_version_id)
);
CREATE UNIQUE INDEX connector_package_install_one_active ON connector_package_install (workspace_id, package_id)
    WHERE status = 'active';
GRANT SELECT, INSERT, UPDATE ON connector_package_install TO ${app_role};

-- Which connection version of the workspace a package version's connection file became (where-used and outdated pins).
CREATE TABLE connector_package_connection (
    workspace_id       uuid NOT NULL REFERENCES workspace (id),
    package_version_id uuid NOT NULL REFERENCES connector_package_version (id),
    connection_key     text NOT NULL,
    connection_version integer NOT NULL,
    PRIMARY KEY (workspace_id, package_version_id, connection_key),
    FOREIGN KEY (workspace_id, connection_key, connection_version)
        REFERENCES connection_definition (workspace_id, key, version)
);
CREATE INDEX connector_package_connection_key ON connector_package_connection (workspace_id, connection_key, connection_version);
GRANT SELECT, INSERT ON connector_package_connection TO ${app_role};

-- Package triggers (CG-C05): a webhook or polling trigger declared by an installed package, enabled in a workspace by a
-- person holding connector.manage, who is also the run-as person for starts and messages (as for Kafka triggers, S99 K8).
-- definition is the trigger file of the installed package version; content of events never changes routing.
CREATE TABLE package_trigger (
    id                 uuid PRIMARY KEY,
    workspace_id       uuid NOT NULL REFERENCES workspace (id),
    package_id         text NOT NULL,
    trigger_key        text NOT NULL CHECK (trigger_key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    package_version_id uuid NOT NULL REFERENCES connector_package_version (id),
    kind               text NOT NULL CHECK (kind IN ('webhook', 'poll')),
    definition         jsonb NOT NULL,
    action             text NOT NULL CHECK (action IN ('start', 'message')),
    workflow_id        uuid REFERENCES workflow (id),
    message_name       text,
    secret_ref         text CHECK (secret_ref IS NULL OR secret_ref ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    jwt_issuer         text CHECK (jwt_issuer IS NULL OR length(jwt_issuer) BETWEEN 1 AND 200),
    jwt_audience       text CHECK (jwt_audience IS NULL OR length(jwt_audience) BETWEEN 1 AND 200),
    connection_key     text,
    run_as             uuid NOT NULL REFERENCES subject (id),
    enabled            boolean NOT NULL,
    version            integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    -- polling state
    watermark          text CHECK (watermark IS NULL OR length(watermark) <= 64),
    next_run_at        timestamptz,
    lease_owner        text,
    lease_until        timestamptz,
    fence              bigint NOT NULL DEFAULT 0,
    last_run_at        timestamptz,
    last_success_at    timestamptz,
    last_error_code    text CHECK (last_error_code IS NULL OR length(last_error_code) <= 64),
    last_lag_seconds   bigint,
    polls              bigint NOT NULL DEFAULT 0,
    records_handled    bigint NOT NULL DEFAULT 0,
    duplicates         bigint NOT NULL DEFAULT 0,
    dead_records       bigint NOT NULL DEFAULT 0,
    created_by         uuid NOT NULL REFERENCES subject (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, package_id, trigger_key),
    CONSTRAINT package_trigger_action CHECK (
        (action = 'start' AND workflow_id IS NOT NULL AND message_name IS NULL)
     OR (action = 'message' AND workflow_id IS NULL AND message_name IS NOT NULL)),
    CONSTRAINT package_trigger_kind CHECK (
        (kind = 'webhook' AND secret_ref IS NOT NULL AND connection_key IS NULL)
     OR (kind = 'poll' AND secret_ref IS NULL AND connection_key IS NOT NULL))
);
CREATE INDEX package_trigger_due ON package_trigger (next_run_at) WHERE kind = 'poll' AND enabled;
GRANT SELECT, INSERT, UPDATE ON package_trigger TO ${app_role};

-- Every handled event once per (trigger, dedupe key); never the payload, only its digest and size.
CREATE TABLE package_trigger_record (
    id           uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    trigger_id   uuid NOT NULL REFERENCES package_trigger (id),
    dedupe_key   text NOT NULL CHECK (length(dedupe_key) BETWEEN 1 AND 300),
    status       text NOT NULL CHECK (status IN ('started', 'resumed', 'buffered', 'dead')),
    error_code   text CHECK (error_code IS NULL OR error_code ~ '^[a-z_]{1,40}$'),
    run_id       uuid,
    message_id   uuid,
    value_sha256 char(64) NOT NULL,
    received_at  timestamptz NOT NULL,
    UNIQUE (trigger_id, dedupe_key)
);
CREATE INDEX package_trigger_record_received ON package_trigger_record (received_at);
CREATE INDEX package_trigger_record_dead ON package_trigger_record (trigger_id, received_at DESC) WHERE status = 'dead';
GRANT SELECT, INSERT, UPDATE, DELETE ON package_trigger_record TO ${app_role};
