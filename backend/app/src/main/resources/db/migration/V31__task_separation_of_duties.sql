-- RL03: approval steps may be kept apart from earlier approvals of the same request (docs/build/S41). The step ids are
-- copied from the pinned release when the task is created, like required_skills.
ALTER TABLE task_context ADD COLUMN separate_from jsonb NOT NULL DEFAULT '[]'::jsonb;
