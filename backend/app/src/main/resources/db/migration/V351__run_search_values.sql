-- CG-O04 (docs/build/S142): indexed projection of the request fields a workflow declares searchable
-- (inputSchema properties with searchable: true, at most 10 per workflow). One row per run and field, written in the
-- transaction that stores or corrects the request data, so a filter never sees other values than the run holds.
-- value_text: normalised text (lower case for text, YYYY-MM-DD for dates, true or false for yes/no);
-- value_number: numbers. Text longer than 200 characters is not indexed.
CREATE TABLE run_search_value (
    workspace_id uuid NOT NULL,
    run_id       uuid NOT NULL,
    workflow_id  uuid NOT NULL,
    field_key    text NOT NULL CHECK (char_length(field_key) BETWEEN 1 AND 64),
    value_text   text CHECK (value_text IS NULL OR char_length(value_text) <= 200),
    value_number numeric,
    PRIMARY KEY (run_id, field_key),
    FOREIGN KEY (workspace_id, run_id) REFERENCES workflow_run (workspace_id, id),
    CONSTRAINT run_search_value_one_value CHECK ((value_text IS NULL) <> (value_number IS NULL))
);
CREATE INDEX run_search_value_text ON run_search_value (workspace_id, workflow_id, field_key, value_text text_pattern_ops)
    WHERE value_text IS NOT NULL;
CREATE INDEX run_search_value_number ON run_search_value (workspace_id, workflow_id, field_key, value_number)
    WHERE value_number IS NOT NULL;
GRANT SELECT, INSERT, UPDATE, DELETE ON run_search_value TO ${app_role};

-- Error-code filter of the run list: the error code of the step that needs attention.
CREATE INDEX workflow_run_attention_code ON workflow_run (workspace_id, (attention ->> 'errorCode'))
    WHERE lifecycle = 'needsAttention';
