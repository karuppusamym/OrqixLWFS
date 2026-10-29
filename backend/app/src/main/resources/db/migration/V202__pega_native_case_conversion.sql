-- S117 native Pega conversion round 3 (docs/build/S117-pega-native-round-3.md). Expand-only.
-- A case type can now be converted to a CMMN case definition through the case definition API. The created definition,
-- the reviewed request (workflow and role mappings), the report and the SHA-256 of the generated CMMN are recorded per
-- migration job; a job's workflow conversion (migration_job.converted_workflow_id) is unaffected.

CREATE TABLE migration_pega_case_conversion (
    workspace_id        uuid NOT NULL,
    migration_id        uuid PRIMARY KEY,
    start_rule          text NOT NULL CHECK (length(start_rule) BETWEEN 1 AND 500),
    case_definition_id  uuid NOT NULL,
    case_key            text NOT NULL CHECK (case_key ~ '^[a-z][a-z0-9-]{1,62}$'),
    case_version        integer NOT NULL CHECK (case_version >= 1),
    source_sha256       char(64) NOT NULL,
    request             jsonb NOT NULL,
    report              jsonb NOT NULL,
    created_by          uuid NOT NULL REFERENCES subject (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, migration_id) REFERENCES migration_job (workspace_id, id),
    FOREIGN KEY (workspace_id, case_definition_id) REFERENCES case_definition (workspace_id, id)
);

GRANT SELECT, INSERT ON migration_pega_case_conversion TO ${app_role};
