-- S10 async draft generation (docs/build/S10-ai-assistance.md section 3): one row per accepted generateAiDraft request so
-- that polling works from any pod. Expand-only.
--
-- The row holds state and review notes only: never the description, the prompt or the raw model output. The review
-- notes (assumptions and missing configuration, each at most 15 items of 300 characters) are the AI's remarks about the
-- draft it created and are returned to the caller when the job succeeds; a failed job keeps its Problem body. A job
-- whose pod died is marked failed by the reaper after deadline_at.
-- Flyway placeholder: ${app_role} = runtime DML role (e.g. orqix_app).

CREATE TABLE ai_generation_job (
    id             uuid PRIMARY KEY,
    workspace_id   uuid NOT NULL REFERENCES workspace (id),
    subject_id     uuid NOT NULL REFERENCES subject (id),
    status         text NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    profile        text NOT NULL CHECK (length(profile) BETWEEN 1 AND 32),
    correlation_id text NOT NULL,
    -- created_at is when the request was accepted (API field startedAt); started_at is when it left the queue.
    created_at     timestamptz NOT NULL DEFAULT now(),
    started_at     timestamptz,
    finished_at    timestamptz,
    -- After this instant an unfinished job is considered orphaned (queue allowance, then deadline + margin once running).
    deadline_at    timestamptz NOT NULL,
    workflow_id    uuid,
    review         jsonb,
    problem        jsonb,
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id),
    CONSTRAINT ai_job_succeeded_has_workflow CHECK ((status = 'succeeded') = (workflow_id IS NOT NULL)),
    CONSTRAINT ai_job_failed_has_problem CHECK ((status = 'failed') = (problem IS NOT NULL))
);
CREATE INDEX ai_generation_job_active ON ai_generation_job (deadline_at) WHERE status IN ('queued', 'running');
CREATE INDEX ai_generation_job_by_subject ON ai_generation_job (workspace_id, subject_id, created_at DESC);

GRANT SELECT, INSERT, UPDATE ON ai_generation_job TO ${app_role};

-- A router (profile with a variable price) chose a model the policy excludes or prices above the caps (S10 section 1):
-- recorded on the generation; the profile is then paused for the rest of the UTC day (circuit breaker, derived from
-- these rows so every pod agrees).
ALTER TABLE ai_generation ADD COLUMN policy_violation text CHECK (length(policy_violation) <= 300);
CREATE INDEX ai_generation_violations ON ai_generation (profile, created_at) WHERE policy_violation IS NOT NULL;
