ALTER TABLE task_context ADD COLUMN priority integer NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 100);
CREATE INDEX task_assigned_priority_idx ON task_context(workspace_id, assignee_subject_id, priority DESC, created_at, engine_task_id) WHERE status='open';
CREATE INDEX task_claimable_priority_idx ON task_context(workspace_id, candidate_group_key, priority DESC, created_at, engine_task_id) WHERE status='open' AND assignee_subject_id IS NULL;
