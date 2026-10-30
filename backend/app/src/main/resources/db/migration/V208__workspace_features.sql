-- Workspace feature switches (docs/build/S127). One row per module a workspace administrator has switched at least once;
-- no row means the module is on, so every existing workspace keeps everything it has today. The server reads this on
-- each gated request (no cache), and the Kafka consumer runner skips triggers of workspaces where kafka is off.
CREATE TABLE workspace_feature (
    workspace_id uuid        NOT NULL REFERENCES workspace (id),
    feature      text        NOT NULL CHECK (feature IN ('ai', 'cases', 'migration', 'agents', 'apps', 'kafka')),
    enabled      boolean     NOT NULL,
    updated_by   uuid        NOT NULL REFERENCES subject (id),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, feature)
);
CREATE INDEX workspace_feature_off ON workspace_feature (feature) WHERE NOT enabled;
GRANT SELECT, INSERT, UPDATE ON workspace_feature TO ${app_role};
