-- Immutable designer test suite revisions and count-only attestations. Workflow row locking serializes changes.
CREATE TABLE workflow_test_suite (
 workspace_id uuid NOT NULL REFERENCES workspace(id),
 workflow_id uuid NOT NULL REFERENCES workflow(id),
 version integer NOT NULL CHECK(version > 0),
 cases jsonb NOT NULL,
 created_by uuid NOT NULL REFERENCES subject(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(workspace_id, workflow_id, version)
);
CREATE TABLE workflow_test_result (
 workspace_id uuid NOT NULL REFERENCES workspace(id),
 workflow_id uuid NOT NULL REFERENCES workflow(id),
 suite_version integer NOT NULL,
 draft_revision integer NOT NULL,
 binding_hash text NOT NULL,
 passed boolean NOT NULL,
 results jsonb NOT NULL,
 tested_by uuid NOT NULL REFERENCES subject(id),
 tested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(workspace_id,workflow_id,suite_version) REFERENCES workflow_test_suite(workspace_id,workflow_id,version)
);
CREATE INDEX workflow_test_result_latest ON workflow_test_result(workspace_id,workflow_id,suite_version,draft_revision,tested_at DESC);
GRANT SELECT,INSERT ON workflow_test_suite,workflow_test_result TO ${app_role};
