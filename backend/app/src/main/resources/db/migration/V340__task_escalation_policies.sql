-- CG-T04 (docs/build/S141): escalation policies on approval steps. One row per level of a task's pinned policy,
-- planned when the task is created (working hours of the step's calendar, or elapsed hours). The worker dispatches a
-- due level once (status pending -> done/skipped under row lock), rechecking that the task is still open and who is
-- eligible. The SLA (due date) is never changed by escalation.
-- Merge note: recreates the notification kind CHECK with every kind known on this branch plus 'taskEscalated'
-- (as V177 and V275 did). A sibling migration adding kinds must list all of them.
ALTER TABLE notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE notification ADD CONSTRAINT notification_kind_check
    CHECK (kind IN ('workflowMessage', 'runAttention', 'discussionMention', 'promotionRequest', 'promotionDecision',
                    'agentToolChanged', 'taskEscalated'));

CREATE TABLE task_escalation (
    workspace_id    uuid NOT NULL,
    task_id         text NOT NULL,
    level           integer NOT NULL CHECK (level BETWEEN 1 AND 5),
    action          text NOT NULL CHECK (action IN ('notify', 'reassign', 'alertOperators')),
    group_key       text CHECK (group_key IS NULL OR char_length(group_key) <= 64),
    after_hours     integer NOT NULL CHECK (after_hours BETWEEN 1 AND 2160),
    working_time    boolean NOT NULL,
    due_at          timestamptz NOT NULL,
    status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'done', 'skipped')),
    outcome         text CHECK (outcome IS NULL OR char_length(outcome) <= 64),
    recipients      integer CHECK (recipients IS NULL OR recipients >= 0),
    attempts        integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    done_at         timestamptz,
    PRIMARY KEY (workspace_id, task_id, level),
    FOREIGN KEY (workspace_id, task_id) REFERENCES task_context (workspace_id, engine_task_id),
    CONSTRAINT task_escalation_group CHECK ((action = 'alertOperators') = (group_key IS NULL)),
    CONSTRAINT task_escalation_done CHECK ((status = 'pending') = (done_at IS NULL))
);
CREATE INDEX task_escalation_due ON task_escalation (next_attempt_at) WHERE status = 'pending';
GRANT SELECT, INSERT, UPDATE ON task_escalation TO ${app_role};
