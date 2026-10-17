-- EX06 (S51): an approval with several approvers opens one task per approver. A person may decide only one of them;
-- the claim/decide checks enforce this, and this index makes it hold even for two simultaneous decisions.
ALTER TABLE task_decision ADD COLUMN distinct_actor boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX task_decision_one_per_actor ON task_decision (run_id, node_id, actor_subject_id) WHERE distinct_actor;
