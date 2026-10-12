-- Skills are workspace group keys, intersected with candidate-group membership and task.act.
ALTER TABLE task_context ADD COLUMN required_skills jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(required_skills)='array');
ALTER TABLE task_reminder ADD COLUMN notice_count integer NOT NULL DEFAULT 0 CHECK (notice_count BETWEEN 0 AND 20);
ALTER TABLE task_reminder ADD COLUMN max_notices integer NOT NULL DEFAULT 0 CHECK (max_notices BETWEEN 0 AND 20);
ALTER TABLE task_reminder ADD COLUMN interval_hours integer CHECK (interval_hours BETWEEN 1 AND 720);
