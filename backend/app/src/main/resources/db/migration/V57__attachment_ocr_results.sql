-- DOC08: text extraction results from the OCR provider contract (docs/build/S52). The shipped provider is a local stub;
-- no live OCR service is called. The text is request data: it is shown only to people who may see the file, and it is
-- deleted with the file.
CREATE TABLE attachment_ocr_result (
    attachment_id uuid PRIMARY KEY REFERENCES run_attachment(id),
    workspace_id  uuid NOT NULL REFERENCES workspace(id),
    provider      text NOT NULL CHECK (length(provider) BETWEEN 1 AND 64),
    status        text NOT NULL CHECK (status IN ('extracted', 'unsupported')),
    content_text  text CHECK (content_text IS NULL OR length(content_text) <= 100000),
    sha256        text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    requested_by  uuid NOT NULL REFERENCES subject(id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT attachment_ocr_text CHECK ((status = 'extracted') = (content_text IS NOT NULL))
);
GRANT SELECT, INSERT, UPDATE, DELETE ON attachment_ocr_result TO ${app_role};
