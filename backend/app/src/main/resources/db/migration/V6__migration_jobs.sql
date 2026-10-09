-- S11 Camunda BPMN import (docs/build/S11-camunda-import.md section 3): one row per uploaded source file.
-- Expand-only. The original bytes, their SHA-256 and the adapter version are preserved with the job (doc 05); the
-- runtime role can insert and read jobs but may update only the columns that change when a job is converted, so the
-- uploaded source can never be rewritten.
-- Flyway placeholder: ${app_role} = runtime DML role (e.g. orqix_app).

CREATE TABLE migration_job (
    id                    uuid PRIMARY KEY,
    workspace_id          uuid NOT NULL REFERENCES workspace (id),
    created_by            uuid NOT NULL REFERENCES subject (id),
    file_name             text NOT NULL CHECK (length(file_name) BETWEEN 1 AND 200),
    source_format         text NOT NULL CHECK (source_format IN ('camunda7', 'camunda8', 'bpmn')),
    source_sha256         char(64) NOT NULL,
    source                bytea NOT NULL CHECK (octet_length(source) BETWEEN 1 AND 1048576),
    adapter_version       text NOT NULL,
    -- CompatibilityReport (openapi.yaml) as last computed: analysis at upload, resolution statuses after conversion.
    report                jsonb NOT NULL,
    -- Source element id -> canonical node/transition ids, kept because workflow-v0 metadata cannot carry it.
    source_map            jsonb,
    -- The designer mappings (groups, approval routes, service tasks) that produced the converted draft.
    mappings              jsonb,
    status                text NOT NULL CHECK (status IN ('analyzed', 'converted', 'failed')),
    converted_workflow_id uuid,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, converted_workflow_id) REFERENCES workflow (workspace_id, id),
    CONSTRAINT migration_job_converted_has_workflow CHECK ((status = 'converted') = (converted_workflow_id IS NOT NULL))
);
CREATE INDEX migration_job_by_workspace ON migration_job (workspace_id, created_at DESC, id DESC);

GRANT SELECT, INSERT ON migration_job TO ${app_role};
GRANT UPDATE (report, source_map, mappings, status, converted_workflow_id, updated_at) ON migration_job TO ${app_role};
