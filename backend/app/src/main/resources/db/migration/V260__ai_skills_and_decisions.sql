-- S134 (doc 29 section 7, CG-A01/A02/A03/A05): AI skills as shared, versioned assets, evaluation suites and runs,
-- skill promotions, the workspace AI policy, AI task jobs with leases, short-lived encrypted turn state and the AI
-- decision record. No prompt or model output is stored except the validated answer in the decision record; request
-- values are stored only when the workspace retention policy says so (default: field names and a digest).
-- Flyway placeholder: ${app_role} = runtime DML role.

CREATE TABLE ai_skill (
    id            uuid PRIMARY KEY,
    workspace_id  uuid NOT NULL REFERENCES workspace (id),
    key           text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    owner_id      uuid NOT NULL REFERENCES subject (id),
    created_at    timestamptz NOT NULL,
    UNIQUE (workspace_id, key)
);

CREATE TABLE ai_skill_version (
    id                uuid PRIMARY KEY,
    workspace_id      uuid NOT NULL REFERENCES workspace (id),
    skill_id          uuid NOT NULL REFERENCES ai_skill (id),
    version           integer NOT NULL CHECK (version >= 1),
    status            text NOT NULL CHECK (status IN ('draft', 'submitted', 'approved', 'retired')),
    -- contracts/ai-skill-v1.schema.json (kind skill); content_hash is the JCS SHA-256 of the document.
    document          jsonb NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    content_hash      text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    mode              text NOT NULL CHECK (mode IN ('classify', 'extract', 'score', 'recommend', 'summarize')),
    model_profile     text NOT NULL CHECK (length(model_profile) BETWEEN 1 AND 40),
    author_id         uuid NOT NULL REFERENCES subject (id),
    submitted_at      timestamptz,
    approver_id       uuid REFERENCES subject (id),
    approved_at       timestamptz,
    approval_reason   text CHECK (approval_reason IS NULL OR length(approval_reason) <= 1000),
    -- Highest autonomy this version may run with; raised only by an approved promotion.
    autonomy_ceiling  text NOT NULL DEFAULT 'suggest' CHECK (autonomy_ceiling IN ('suggest', 'autoWithReview', 'auto')),
    -- Drift (section 7.7): set by the weekly drift check; AI tasks then run as suggest until a person clears it.
    drift_fallback    boolean NOT NULL DEFAULT false,
    drift_reason      text CHECK (drift_reason IS NULL OR length(drift_reason) <= 500),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    UNIQUE (skill_id, version),
    -- Maker-checker: the author never approves their own version.
    CONSTRAINT ai_skill_version_four_eyes CHECK (approver_id IS NULL OR approver_id <> author_id),
    CONSTRAINT ai_skill_version_approved CHECK (status <> 'approved' OR (approved_at IS NOT NULL AND approver_id IS NOT NULL))
);
CREATE INDEX ai_skill_version_by_workspace ON ai_skill_version (workspace_id, skill_id, version DESC);

CREATE TABLE ai_skill_promotion (
    id                  uuid PRIMARY KEY,
    workspace_id        uuid NOT NULL REFERENCES workspace (id),
    skill_version_id    uuid NOT NULL REFERENCES ai_skill_version (id),
    from_autonomy       text NOT NULL CHECK (from_autonomy IN ('suggest', 'autoWithReview', 'auto')),
    to_autonomy         text NOT NULL CHECK (to_autonomy IN ('autoWithReview', 'auto')),
    -- Shadow evidence at the time of the request: cases, agreement, per-label recall.
    evidence            jsonb NOT NULL CHECK (jsonb_typeof(evidence) = 'object'),
    status              text NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'withdrawn')),
    requested_by        uuid NOT NULL REFERENCES subject (id),
    requested_at        timestamptz NOT NULL,
    reason              text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    decided_by          uuid REFERENCES subject (id),
    decided_at          timestamptz,
    decision_reason     text CHECK (decision_reason IS NULL OR length(decision_reason) <= 1000),
    CONSTRAINT ai_skill_promotion_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by)
);
CREATE UNIQUE INDEX ai_skill_promotion_one_pending ON ai_skill_promotion (skill_version_id) WHERE status = 'pending';

CREATE TABLE ai_eval_suite (
    id            uuid PRIMARY KEY,
    workspace_id  uuid NOT NULL REFERENCES workspace (id),
    key           text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    skill_key     text NOT NULL CHECK (skill_key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    -- contracts/ai-skill-v1.schema.json (kind evaluationSuite): synthetic or deliberately redacted cases only.
    document      jsonb NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    content_hash  text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    case_count    integer NOT NULL CHECK (case_count BETWEEN 1 AND 1000),
    revision      integer NOT NULL DEFAULT 1 CHECK (revision >= 1),
    updated_by    uuid NOT NULL REFERENCES subject (id),
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    UNIQUE (workspace_id, key)
);

CREATE TABLE ai_eval_run (
    id                uuid PRIMARY KEY,
    workspace_id      uuid NOT NULL REFERENCES workspace (id),
    skill_version_id  uuid NOT NULL REFERENCES ai_skill_version (id),
    suite_id          uuid NOT NULL REFERENCES ai_eval_suite (id),
    suite_hash        text NOT NULL CHECK (suite_hash ~ '^[0-9a-f]{64}$'),
    model_profile     text NOT NULL CHECK (length(model_profile) BETWEEN 1 AND 40),
    status            text NOT NULL CHECK (status IN ('running', 'passed', 'failed', 'error')),
    case_count        integer NOT NULL CHECK (case_count >= 0),
    -- accuracy, per-label precision/recall, confusion matrix, cost, p95 latency, threshold trade-off.
    metrics           jsonb CHECK (metrics IS NULL OR jsonb_typeof(metrics) = 'object'),
    -- Score buckets and the observed accuracy of each (section 7.5).
    calibration       jsonb CHECK (calibration IS NULL OR jsonb_typeof(calibration) = 'array'),
    failure           text CHECK (failure IS NULL OR length(failure) <= 500),
    started_by        uuid NOT NULL REFERENCES subject (id),
    started_at        timestamptz NOT NULL,
    finished_at       timestamptz,
    CONSTRAINT ai_eval_run_finished CHECK ((status = 'running') = (finished_at IS NULL))
);
CREATE INDEX ai_eval_run_gate ON ai_eval_run (skill_version_id, model_profile, finished_at DESC) WHERE status = 'passed';

CREATE TABLE ai_workspace_policy (
    workspace_id            uuid PRIMARY KEY REFERENCES workspace (id),
    -- A regulated workspace: adverse outcomes always need a person (D-CG3).
    regulated               boolean NOT NULL DEFAULT false,
    -- Residency tags; an endpoint must carry one of them. Empty admits every endpoint.
    residency               text[] NOT NULL DEFAULT '{}',
    -- D-CG9: what the decision record keeps of the inputs.
    input_retention         text NOT NULL DEFAULT 'digest' CHECK (input_retention IN ('digest', 'redactedValues', 'fullValues')),
    retention_days          integer NOT NULL DEFAULT 365 CHECK (retention_days BETWEEN 1 AND 3650),
    -- "skillKey:label" entries the policy marks low-risk; only these may be decided with autonomy auto.
    low_risk_labels         text[] NOT NULL DEFAULT '{}',
    allow_auto_with_review  boolean NOT NULL DEFAULT true,
    -- Field classification to action (send, mask, hash, drop); defaults in AiPolicy.
    classification_actions  jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(classification_actions) = 'object'),
    version                 integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by              uuid REFERENCES subject (id),
    updated_at              timestamptz NOT NULL
);

-- CG-A05: one job per AI task occurrence. The minimized input is held encrypted and cleared when the job ends.
CREATE TABLE ai_task_job (
    id                 uuid PRIMARY KEY,
    workspace_id       uuid NOT NULL REFERENCES workspace (id),
    run_id             uuid NOT NULL REFERENCES workflow_run (id),
    node_id            text NOT NULL CHECK (length(node_id) BETWEEN 1 AND 64),
    occurrence         integer NOT NULL DEFAULT 1 CHECK (occurrence >= 1),
    skill_key          text NOT NULL,
    skill_version      integer NOT NULL CHECK (skill_version >= 0),
    -- Kept in clear (no request data) so a job whose sealed input cannot be opened still ends on aiUnavailable.
    mode               text NOT NULL CHECK (mode IN ('classify', 'extract', 'score', 'recommend', 'summarize')),
    autonomy           text NOT NULL CHECK (autonomy IN ('shadow', 'suggest', 'autoWithReview', 'auto')),
    status             text NOT NULL CHECK (status IN ('available', 'running', 'completed', 'failed', 'cancelled')),
    attempt            integer NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 10),
    fence              integer NOT NULL DEFAULT 0 CHECK (fence >= 0),
    lease_owner        text CHECK (lease_owner IS NULL OR length(lease_owner) <= 100),
    lease_expires_at   timestamptz,
    deadline_at        timestamptz NOT NULL,
    input_key_id       text,
    input_cipher       text CHECK (input_cipher IS NULL OR length(input_cipher) <= 400000),
    last_error         text CHECK (last_error IS NULL OR length(last_error) <= 500),
    available_at       timestamptz NOT NULL,
    created_at         timestamptz NOT NULL,
    ended_at           timestamptz,
    CONSTRAINT ai_task_job_running_has_lease CHECK (status <> 'running' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT ai_task_job_ended CHECK ((status IN ('completed', 'failed', 'cancelled')) = (ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX ai_task_job_one_open_per_step ON ai_task_job (run_id, node_id) WHERE status IN ('available', 'running');
CREATE INDEX ai_task_job_queue ON ai_task_job (available_at) WHERE status = 'available';
CREATE INDEX ai_task_job_leases ON ai_task_job (lease_expires_at) WHERE status = 'running';

-- CG-A05: completed turns (samples, the repair turn) of a running AI task, AES-GCM sealed, deleted when the task ends
-- and at most 24 hours after it was written.
CREATE TABLE ai_turn_state (
    job_id       uuid PRIMARY KEY REFERENCES ai_task_job (id),
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    key_id       text NOT NULL CHECK (length(key_id) BETWEEN 1 AND 64),
    cipher       text NOT NULL CHECK (length(cipher) <= 400000),
    turns        integer NOT NULL CHECK (turns >= 0),
    expires_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);
CREATE INDEX ai_turn_state_expiry ON ai_turn_state (expires_at);

-- CG-A03: one record per AI decision, written in the same transaction as the step completion (section 7.8).
CREATE TABLE ai_decision_record (
    id                   uuid PRIMARY KEY,
    workspace_id         uuid NOT NULL REFERENCES workspace (id),
    run_id               uuid NOT NULL REFERENCES workflow_run (id),
    node_id              text NOT NULL,
    occurrence           integer NOT NULL CHECK (occurrence >= 1),
    skill_key            text NOT NULL,
    skill_version        integer NOT NULL CHECK (skill_version >= 0),
    skill_hash           text,
    mode                 text NOT NULL CHECK (mode IN ('classify', 'extract', 'score', 'recommend', 'summarize')),
    autonomy             text NOT NULL CHECK (autonomy IN ('shadow', 'suggest', 'autoWithReview', 'auto')),
    effective_autonomy   text NOT NULL CHECK (effective_autonomy IN ('shadow', 'suggest', 'autoWithReview', 'auto')),
    profile              text,
    provider             text,
    model_used           text,
    prompt_hash          text CHECK (prompt_hash IS NULL OR prompt_hash ~ '^[0-9a-f]{64}$'),
    input_fields         text[] NOT NULL,
    input_digest         text NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    -- Only with retention redactedValues or fullValues; null by default (D-CG9).
    input_values         jsonb,
    input_retention      text NOT NULL CHECK (input_retention IN ('digest', 'redactedValues', 'fullValues')),
    status               text NOT NULL CHECK (status IN ('decided', 'lowConfidence', 'needsReview', 'unavailable', 'shadow')),
    -- The validated answer: label, fields, score, recommendation or summary, reasons, evidence spans.
    output               jsonb,
    raw_confidence       numeric(6, 5),
    confidence           numeric(6, 5),
    threshold            numeric(6, 5),
    route                text NOT NULL CHECK (length(route) BETWEEN 1 AND 60),
    -- repairs, samples, agreement, downgrade reasons (policy, drift, regulated, review sample).
    guardrails           jsonb NOT NULL CHECK (jsonb_typeof(guardrails) = 'object'),
    unavailable_reason   text CHECK (unavailable_reason IS NULL OR length(unavailable_reason) <= 300),
    review_sampled       boolean NOT NULL DEFAULT false,
    -- Human outcome (section 7.7 feedback): who accepted or overrode, the final outcome and the reason.
    human_outcome        text CHECK (human_outcome IS NULL OR human_outcome IN ('accepted', 'overridden')),
    human_value          text CHECK (human_value IS NULL OR length(human_value) <= 200),
    human_reason         text CHECK (human_reason IS NULL OR length(human_reason) <= 1000),
    human_by             uuid REFERENCES subject (id),
    human_at             timestamptz,
    input_tokens         bigint CHECK (input_tokens >= 0),
    output_tokens        bigint CHECK (output_tokens >= 0),
    cost_usd             numeric(18, 10) CHECK (cost_usd >= 0),
    latency_ms           bigint NOT NULL CHECK (latency_ms >= 0),
    created_at           timestamptz NOT NULL,
    retain_until         timestamptz NOT NULL,
    UNIQUE (run_id, node_id, occurrence)
);
CREATE INDEX ai_decision_by_skill ON ai_decision_record (workspace_id, skill_key, skill_version, created_at DESC);
CREATE INDEX ai_decision_review_queue ON ai_decision_record (workspace_id, created_at DESC) WHERE review_sampled AND human_outcome IS NULL;

-- Section 7.7: every human override becomes a candidate evaluation case, reviewed and redacted before it is added.
CREATE TABLE ai_eval_candidate (
    id             uuid PRIMARY KEY,
    workspace_id   uuid NOT NULL REFERENCES workspace (id),
    skill_key      text NOT NULL,
    decision_id    uuid NOT NULL REFERENCES ai_decision_record (id),
    -- Present only when the decision kept values (retention redactedValues or fullValues); otherwise the reviewer
    -- writes a synthetic case.
    input          jsonb,
    expected       jsonb NOT NULL CHECK (jsonb_typeof(expected) = 'object'),
    status         text NOT NULL CHECK (status IN ('pending', 'accepted', 'discarded')),
    created_at     timestamptz NOT NULL,
    reviewed_by    uuid REFERENCES subject (id),
    reviewed_at    timestamptz,
    UNIQUE (decision_id)
);

ALTER TABLE ai_generation DROP CONSTRAINT IF EXISTS ai_generation_kind_check;
ALTER TABLE ai_generation ADD CONSTRAINT ai_generation_kind_check
    CHECK (kind IN ('draft', 'explain', 'agent', 'form', 'draft-edit', 'aiTask', 'aiEval'));

GRANT SELECT, INSERT, UPDATE ON ai_skill, ai_skill_version, ai_skill_promotion, ai_eval_suite, ai_eval_run,
    ai_workspace_policy, ai_task_job, ai_decision_record, ai_eval_candidate TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_turn_state TO ${app_role};
-- Retention of decision records past retain_until runs in the purge job (section 7.8), like other retention.
GRANT DELETE ON ai_eval_candidate TO ${app_role};

-- Grant-only permissions: ai.approve-skill (approve skill versions and promotions; never one's own) and
-- ai.evaluate (run evaluation suites, read shadow dashboards and review queues).
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read',
                          'workflow.approve-promotion','agent.approve-write-tool','ai.approve-skill','ai.evaluate'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read',
                              'workflow.approve-promotion','agent.approve-write-tool','ai.approve-skill','ai.evaluate')
           OR workflow_id IS NULL);
