-- CMP01 (docs/build/S87): @mentions in the step and connection discussion, with one in-app notification per mentioned
-- person. A mention row records who was mentioned in which comment (ids only). The mention is resolved and checked on
-- the server when the comment is posted; the comment text itself is never copied into a notification.
CREATE TABLE workflow_comment_mention (
    workspace_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    comment_id uuid NOT NULL,
    subject_id uuid NOT NULL REFERENCES subject (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, subject_id),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    FOREIGN KEY (workflow_id, comment_id) REFERENCES workflow_element_comment (workflow_id, id)
);
CREATE INDEX workflow_comment_mention_workflow ON workflow_comment_mention (workflow_id, comment_id);
GRANT SELECT, INSERT ON workflow_comment_mention TO ${app_role};

-- A mention notification points at the workflow, the element and the comment instead of a run.
ALTER TABLE notification
    ADD COLUMN workflow_id uuid,
    ADD COLUMN element_id text CHECK (element_id IS NULL OR length(element_id) BETWEEN 1 AND 128),
    ADD COLUMN comment_id uuid;
ALTER TABLE notification ADD CONSTRAINT notification_workflow_fk
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id);
ALTER TABLE notification ADD CONSTRAINT notification_comment_fk
    FOREIGN KEY (workflow_id, comment_id) REFERENCES workflow_element_comment (workflow_id, id);
ALTER TABLE notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE notification ADD CONSTRAINT notification_kind_check
    CHECK (kind IN ('workflowMessage', 'runAttention', 'discussionMention'));
ALTER TABLE notification ADD CONSTRAINT notification_mention_target
    CHECK (kind <> 'discussionMention'
           OR (run_id IS NULL AND workflow_id IS NOT NULL AND element_id IS NOT NULL AND comment_id IS NOT NULL));
