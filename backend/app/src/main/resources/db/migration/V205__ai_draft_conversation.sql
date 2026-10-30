-- S124: the Ask AI conversation of a workflow draft, saved with the draft so it survives a reload and is shared by the
-- people who edit the draft. Text only; never copied to audit. Anyone who can edit the draft can clear it.
CREATE TABLE ai_draft_conversation (
    workflow_id  uuid PRIMARY KEY REFERENCES workflow (id) ON DELETE CASCADE,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    turns        jsonb NOT NULL CHECK (jsonb_typeof(turns) = 'array'),
    updated_by   uuid NOT NULL REFERENCES subject (id),
    updated_at   timestamptz NOT NULL,
    version      integer NOT NULL DEFAULT 1 CHECK (version >= 1)
);
CREATE INDEX ai_draft_conversation_by_workspace ON ai_draft_conversation (workspace_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_draft_conversation TO ${app_role};
