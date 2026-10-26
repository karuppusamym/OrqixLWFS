-- S91 Pega Blueprint PDF import (docs/build/S91-pega-blueprint-pdf.md). Expand-only.
-- The original PDF bytes and their SHA-256 stay on migration_job (immutable, like every source). Page texts are kept
-- with their own hashes so reviewers see each transcription element next to its source page. Every saved
-- transcription is a new immutable revision; conversion binds the reviewed revision's hash into the stored mappings,
-- so the independent migration review covers it.
-- Flyway placeholder: ${app_role} = runtime DML role (e.g. orqix_app).

ALTER TABLE migration_job DROP CONSTRAINT migration_job_source_format_check;
ALTER TABLE migration_job ADD CONSTRAINT migration_job_source_format_check
    CHECK (source_format IN ('camunda7', 'camunda8', 'bpmn', 'pegaArchive', 'pegaMapping', 'dmn', 'camundaForm', 'pegaXpdl', 'cmmn', 'pegaBlueprint'));
-- Blueprint PDFs may be up to 25 MiB; every other source keeps its 1 MiB limit.
ALTER TABLE migration_job DROP CONSTRAINT migration_job_source_check;
ALTER TABLE migration_job ADD CONSTRAINT migration_job_source_check
    CHECK (octet_length(source) BETWEEN 1 AND 1048576
        OR (source_format = 'pegaBlueprint' AND octet_length(source) BETWEEN 1 AND 26214400));

CREATE TABLE migration_blueprint_document (
    workspace_id      uuid NOT NULL,
    migration_id      uuid PRIMARY KEY,
    page_count        integer NOT NULL CHECK (page_count BETWEEN 1 AND 60),
    javascript        boolean NOT NULL,
    embedded_files    boolean NOT NULL,
    layout_id         text NOT NULL CHECK (length(layout_id) BETWEEN 1 AND 64),
    layout_revision   text NOT NULL CHECK (length(layout_revision) BETWEEN 1 AND 32),
    FOREIGN KEY (workspace_id, migration_id) REFERENCES migration_job (workspace_id, id)
);

CREATE TABLE migration_blueprint_page (
    workspace_id        uuid NOT NULL,
    migration_id        uuid NOT NULL REFERENCES migration_blueprint_document (migration_id),
    page_number         integer NOT NULL CHECK (page_number BETWEEN 1 AND 60),
    text                text NOT NULL CHECK (octet_length(text) <= 1048576),
    text_sha256         char(64) NOT NULL,
    unclassified_lines  integer NOT NULL CHECK (unclassified_lines >= 0),
    PRIMARY KEY (migration_id, page_number),
    FOREIGN KEY (workspace_id, migration_id) REFERENCES migration_job (workspace_id, id)
);

CREATE TABLE migration_blueprint_transcription (
    workspace_id          uuid NOT NULL,
    migration_id          uuid NOT NULL REFERENCES migration_blueprint_document (migration_id),
    revision              integer NOT NULL CHECK (revision >= 1),
    transcription         jsonb NOT NULL,
    transcription_sha256  char(64) NOT NULL,
    origin                text NOT NULL CHECK (origin IN ('extracted', 'edited')),
    saved_by              uuid NOT NULL REFERENCES subject (id),
    saved_at              timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (migration_id, revision),
    FOREIGN KEY (workspace_id, migration_id) REFERENCES migration_job (workspace_id, id)
);

GRANT SELECT, INSERT ON migration_blueprint_document TO ${app_role};
GRANT SELECT, INSERT ON migration_blueprint_page TO ${app_role};
GRANT SELECT, INSERT ON migration_blueprint_transcription TO ${app_role};
