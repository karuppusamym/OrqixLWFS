-- CG-S01 Git sync (docs/build/S147). One Git connection per workspace: the provider, the repository, the branch that
-- pull requests target, the folder Orqix writes under and a credential *reference* resolved through the S132 secret
-- providers at call time. No credential value, binding or secret is ever stored here or pushed to Git.
CREATE TABLE git_connection (
    workspace_id   uuid PRIMARY KEY REFERENCES workspace (id),
    provider       text NOT NULL CHECK (provider IN ('github', 'gitlab', 'azure-devops', 'bitbucket')),
    api_url        text NOT NULL CHECK (length(api_url) <= 300 AND api_url ~ '^https?://[^/?#@]+(/[^?#@]*)?$'),
    repository     text NOT NULL CHECK (length(repository) BETWEEN 3 AND 200
                                         AND repository ~ '^[A-Za-z0-9][A-Za-z0-9._ -]*(/[A-Za-z0-9][A-Za-z0-9._ -]*){1,3}$'),
    base_branch    text NOT NULL CHECK (length(base_branch) BETWEEN 1 AND 100 AND base_branch ~ '^[A-Za-z0-9][A-Za-z0-9._/-]*$'
                                         AND base_branch !~ '\.\.'),
    base_path      text NOT NULL CHECK (length(base_path) BETWEEN 1 AND 100 AND base_path ~ '^[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*$'),
    credential_ref text NOT NULL CHECK (credential_ref ~
        '^([a-z0-9][a-z0-9-]{0,62}|(vault|aws-sm|azure-kv|gcp-sm):[A-Za-z0-9][A-Za-z0-9/_.+=@:-]{0,255}(#[A-Za-z0-9_.-]{1,128})?)$'),
    enabled        boolean NOT NULL DEFAULT true,
    version        integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by     uuid NOT NULL REFERENCES subject (id),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- Every push, pull (import) and publish from Git, with the commit and the content hash of the workflow's source files.
-- A publish row links the release it created; the release manifest carries the same facts (gitSource).
CREATE TABLE git_sync_event (
    id             uuid PRIMARY KEY,
    workspace_id   uuid NOT NULL REFERENCES workspace (id),
    workflow_id    uuid NOT NULL,
    direction      text NOT NULL CHECK (direction IN ('push', 'pull', 'publish')),
    provider       text NOT NULL CHECK (provider IN ('github', 'gitlab', 'azure-devops', 'bitbucket')),
    repository     text NOT NULL,
    branch         text NOT NULL,
    commit_sha     text NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}([0-9a-f]{24})?$'),
    content_hash   char(64) NOT NULL,
    draft_revision integer CHECK (draft_revision IS NULL OR draft_revision >= 1),
    release_id     uuid,
    pull_request   text CHECK (pull_request IS NULL OR length(pull_request) <= 40),
    pull_request_url text CHECK (pull_request_url IS NULL OR length(pull_request_url) <= 500),
    details        jsonb NOT NULL DEFAULT '{}'::jsonb,
    actor          uuid NOT NULL REFERENCES subject (id),
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    FOREIGN KEY (workspace_id, release_id) REFERENCES workflow_release (workspace_id, id)
);
CREATE INDEX git_sync_event_workflow ON git_sync_event (workspace_id, workflow_id, occurred_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON git_connection TO ${app_role};
GRANT SELECT, INSERT ON git_sync_event TO ${app_role};
