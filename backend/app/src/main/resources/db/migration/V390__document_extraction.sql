-- CG-A06 document extraction (doc 29 section 7.11, docs/build/S146-parts/B-document-extraction.md).
-- Flyway placeholder: ${app_role} = runtime DML role.

-- (1) The S52 OCR result of a file also holds the layout an AI task's OCR read (pages, lines with boxes and
-- confidences, the service's key-value pairs). It stays under the S52 rules: shown only to people who may see the file
-- and deleted on quarantine, removal and the retention purge. Results written by an AI task have no requesting person.
ALTER TABLE attachment_ocr_result ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE attachment_ocr_result ADD COLUMN source text NOT NULL DEFAULT 'manual'
    CHECK (source IN ('manual', 'aiTask'));
ALTER TABLE attachment_ocr_result ADD COLUMN layout jsonb
    CHECK (layout IS NULL OR (jsonb_typeof(layout) = 'object' AND octet_length(layout::text) <= 4000000));
ALTER TABLE attachment_ocr_result ADD CONSTRAINT attachment_ocr_requested
    CHECK (source = 'aiTask' OR requested_by IS NOT NULL);

-- (2) The workspace's OCR provider (no row: the deployment default, local unless configured otherwise).
CREATE TABLE ai_ocr_setting (
    workspace_id uuid PRIMARY KEY REFERENCES workspace (id),
    provider     text NOT NULL CHECK (provider ~ '^[a-z][a-z0-9-]{0,39}$'),
    version      integer NOT NULL CHECK (version >= 1),
    updated_by   uuid NOT NULL REFERENCES subject (id),
    updated_at   timestamptz NOT NULL
);

-- (3) Verification tasks: an extraction below the threshold waits for a person who sees the document beside the
-- fields and confirms or corrects each one. The fields hold values, confidences, problems and source locations (page,
-- box, character span), never recognized text; the AI outputs are the step outputs the person starts from.
CREATE TABLE ai_document_verification (
    id             uuid PRIMARY KEY,
    workspace_id   uuid NOT NULL REFERENCES workspace (id),
    run_id         uuid NOT NULL,
    node_id        text NOT NULL CHECK (length(node_id) BETWEEN 1 AND 200),
    occurrence     integer NOT NULL CHECK (occurrence >= 1),
    job_id         uuid NOT NULL REFERENCES ai_task_job (id),
    decision_id    uuid NOT NULL REFERENCES ai_decision_record (id),
    attachment_id  uuid NOT NULL REFERENCES run_attachment (id),
    skill_key      text NOT NULL,
    skill_version  integer NOT NULL,
    document_type  text NOT NULL CHECK (document_type IN ('invoice', 'idDocument', 'bankStatement', 'custom')),
    provider       text NOT NULL CHECK (length(provider) BETWEEN 1 AND 64),
    threshold      numeric(6, 5) NOT NULL,
    payload        jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 1000000),
    ai_outputs     jsonb NOT NULL CHECK (jsonb_typeof(ai_outputs) = 'object'),
    status         text NOT NULL CHECK (status IN ('open', 'verified', 'escalated', 'cancelled')),
    version        integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    corrected      text[],
    decided_by     uuid REFERENCES subject (id),
    decided_at     timestamptz,
    created_at     timestamptz NOT NULL,
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    UNIQUE (decision_id),
    CONSTRAINT ai_document_verification_decided CHECK ((status IN ('verified', 'escalated')) = (decided_by IS NOT NULL))
);
CREATE UNIQUE INDEX ai_document_verification_one_open ON ai_document_verification (run_id, node_id) WHERE status = 'open';
CREATE INDEX ai_document_verification_queue ON ai_document_verification (workspace_id, created_at) WHERE status = 'open';

-- (4) Metering: document extraction calls (aiExtract) and cloud OCR analyses (aiOcr). Rebuilt with the full current
-- list; other S146 parts may rebuild it with a superset.
ALTER TABLE ai_generation DROP CONSTRAINT IF EXISTS ai_generation_kind_check;
ALTER TABLE ai_generation ADD CONSTRAINT ai_generation_kind_check
    CHECK (kind IN ('draft', 'explain', 'agent', 'form', 'draft-edit', 'aiTask', 'aiEval', 'aiExtract', 'aiOcr'));

GRANT SELECT, INSERT, UPDATE ON ai_ocr_setting, ai_document_verification TO ${app_role};
