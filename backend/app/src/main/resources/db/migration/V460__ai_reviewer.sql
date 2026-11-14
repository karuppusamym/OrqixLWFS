-- AI reviewer on approval steps (docs/build/S152). An approval step may name an AI skill that reviews each of its
-- tasks: the review runs as an AI task job tied to the task, and a confident answer within the step's autonomy decides
-- the task as the AI reviewer; otherwise the task stays with people and shows the suggestion.

-- 1. Review jobs share the AI task queue (lease, fence, sealed input, recovery). A review job names the task it
--    reviews and the task version it saw when it was queued: a person who acted on the task since (claim, correction,
--    reassignment) keeps the decision.
ALTER TABLE ai_task_job ADD COLUMN purpose text NOT NULL DEFAULT 'aiTask';
ALTER TABLE ai_task_job ADD CONSTRAINT ai_task_job_purpose CHECK (purpose IN ('aiTask', 'review'));
ALTER TABLE ai_task_job ADD COLUMN task_id text CHECK (task_id IS NULL OR length(task_id) <= 64);
ALTER TABLE ai_task_job ADD COLUMN task_version integer CHECK (task_version IS NULL OR task_version >= 0);
ALTER TABLE ai_task_job ADD CONSTRAINT ai_task_job_review_has_task
    CHECK ((purpose = 'review') = (task_id IS NOT NULL AND task_version IS NOT NULL));
CREATE INDEX ai_task_job_by_task ON ai_task_job (workspace_id, task_id, created_at DESC) WHERE task_id IS NOT NULL;

-- 2. The decision record of a review names its task (no foreign key: run purges delete in table order).
ALTER TABLE ai_decision_record ADD COLUMN task_id text CHECK (task_id IS NULL OR length(task_id) <= 64);
CREATE INDEX ai_decision_by_task ON ai_decision_record (workspace_id, task_id) WHERE task_id IS NOT NULL;

-- 3. A task decision is made by a person (actor_subject_id) or by the AI reviewer (no subject; the skill and the AI
--    decision record say which). A person's decision always names the person.
ALTER TABLE task_decision ALTER COLUMN actor_subject_id DROP NOT NULL;
ALTER TABLE task_decision ADD COLUMN actor_kind text NOT NULL DEFAULT 'person';
ALTER TABLE task_decision ADD CONSTRAINT task_decision_actor_kind CHECK (actor_kind IN ('person', 'aiReviewer'));
ALTER TABLE task_decision ADD COLUMN ai_decision_id uuid;
ALTER TABLE task_decision ADD COLUMN ai_skill_key text CHECK (ai_skill_key IS NULL OR length(ai_skill_key) <= 64);
ALTER TABLE task_decision ADD COLUMN ai_skill_version integer CHECK (ai_skill_version IS NULL OR ai_skill_version >= 1);
ALTER TABLE task_decision ADD CONSTRAINT task_decision_actor_shape CHECK (
    (actor_kind = 'person' AND actor_subject_id IS NOT NULL AND ai_decision_id IS NULL)
    OR (actor_kind = 'aiReviewer' AND actor_subject_id IS NULL AND ai_decision_id IS NOT NULL
        AND ai_skill_key IS NOT NULL AND ai_skill_version IS NOT NULL));

-- 4. Audit: the AI reviewer is an actor of its own kind, without a subject. The hash chain (V453) covers actor_type
--    as text, so it needs no change.
ALTER TABLE audit_event DROP CONSTRAINT audit_event_actor_type_check;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_actor_type_check
    CHECK (actor_type IN ('user', 'service', 'system', 'agent'));
