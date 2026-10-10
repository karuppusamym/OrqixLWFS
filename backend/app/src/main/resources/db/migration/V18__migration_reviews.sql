ALTER TABLE migration_job ADD COLUMN converted_by_subject_id uuid REFERENCES subject(id);
GRANT UPDATE(converted_by_subject_id) ON migration_job TO ${app_role};

CREATE TABLE migration_policy (
    workspace_id uuid PRIMARY KEY REFERENCES workspace(id),
    required boolean NOT NULL,
    version integer NOT NULL CHECK(version > 0),
    reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
    updated_by uuid NOT NULL REFERENCES subject(id),
    updated_at timestamptz NOT NULL
);
CREATE TABLE migration_review (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    draft_revision integer NOT NULL,
    version integer NOT NULL CHECK(version > 0),
    definition_hash char(64) NOT NULL,
    source_hash char(64) NOT NULL,
    mappings_hash char(64) NOT NULL,
    mapper_subject_id uuid NOT NULL REFERENCES subject(id),
    reviewed_by uuid NOT NULL REFERENCES subject(id),
    reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
    reviewed_at timestamptz NOT NULL,
    FOREIGN KEY(workspace_id,workflow_id) REFERENCES workflow(workspace_id,id),
    UNIQUE(workspace_id,workflow_id,draft_revision,version),
    CHECK(mapper_subject_id <> reviewed_by)
);
GRANT SELECT,INSERT,UPDATE ON migration_policy TO ${app_role};
GRANT SELECT,INSERT ON migration_review TO ${app_role};
