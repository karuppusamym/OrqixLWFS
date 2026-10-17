-- Conversation drafts are workspace- and author-scoped. No model transcript is persisted.
CREATE TABLE conversation (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    subject_id uuid NOT NULL REFERENCES subject(id),
    state jsonb NOT NULL,
    version integer NOT NULL DEFAULT 1,
    expires_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(workspace_id, id)
);
CREATE INDEX conversation_by_author ON conversation(workspace_id, subject_id, updated_at DESC);
CREATE TABLE conversation_turn (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    response jsonb NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON conversation, conversation_turn TO ${app_role};
