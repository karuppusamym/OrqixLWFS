CREATE TABLE case_catalog (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    key text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{1,62}$'),
    current_version integer NOT NULL DEFAULT 0 CHECK (current_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(workspace_id,key),
    UNIQUE(workspace_id,id)
);

CREATE TABLE case_definition (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    source_xml text NOT NULL CHECK (octet_length(source_xml) <= 65536),
    source_sha256 char(64) NOT NULL,
    engine_definition_id text NOT NULL,
    engine_deployment_id text NOT NULL,
    created_by uuid NOT NULL REFERENCES subject(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(workspace_id,id),
    UNIQUE(catalog_id,version),
    FOREIGN KEY(workspace_id,catalog_id) REFERENCES case_catalog(workspace_id,id)
);
CREATE INDEX case_definition_workspace ON case_definition(workspace_id,catalog_id,version DESC);

CREATE TABLE case_instance (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    definition_id uuid NOT NULL,
    engine_instance_id text NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN ('active','completed')),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    started_by uuid NOT NULL REFERENCES subject(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE(workspace_id,id),
    FOREIGN KEY(workspace_id,definition_id) REFERENCES case_definition(workspace_id,id)
);
CREATE INDEX case_instance_workspace ON case_instance(workspace_id,created_at DESC,id DESC);

CREATE TABLE case_command_response (
    workspace_id uuid NOT NULL,
    actor_subject_id uuid NOT NULL REFERENCES subject(id),
    operation text NOT NULL CHECK (operation IN ('startCase','completeCaseTask')),
    idem_key text NOT NULL,
    case_id uuid NOT NULL,
    candidate_groups jsonb NOT NULL DEFAULT '[]',
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(workspace_id,actor_subject_id,operation,idem_key),
    FOREIGN KEY(workspace_id,case_id) REFERENCES case_instance(workspace_id,id)
);

GRANT SELECT, INSERT, UPDATE ON case_catalog TO ${app_role};
GRANT SELECT, INSERT ON case_definition TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON case_instance TO ${app_role};
GRANT SELECT, INSERT ON case_command_response TO ${app_role};
