-- S10 AI assistance (docs/build/S10-ai-assistance.md section 3 step 6): one row per AI generation (draft or
-- explanation) that reached the provider. Expand-only. Holds metering facts only: no description, prompt, model output
-- or workflow content is ever stored. The monthly budget is the sum of provider-reported cost_usd in the current UTC
-- calendar month. Append-only for the runtime role.
-- Flyway placeholder: ${app_role} = runtime DML role (e.g. orqix_app).

CREATE TABLE ai_generation (
    id               uuid PRIMARY KEY,
    workspace_id     uuid NOT NULL REFERENCES workspace (id),
    subject_id       uuid NOT NULL REFERENCES subject (id),
    kind             text NOT NULL CHECK (kind IN ('draft', 'explain')),
    profile          text NOT NULL CHECK (length(profile) BETWEEN 1 AND 32),
    -- Model that produced the last answer (routers report the routed model); requested_model is the profile model.
    model            text NOT NULL CHECK (length(model) BETWEEN 1 AND 200),
    requested_model  text NOT NULL CHECK (length(requested_model) BETWEEN 1 AND 200),
    prompt_version   text NOT NULL CHECK (length(prompt_version) BETWEEN 1 AND 40),
    attempts         integer NOT NULL CHECK (attempts BETWEEN 0 AND 20),
    escalated        boolean NOT NULL DEFAULT false,
    input_tokens     bigint CHECK (input_tokens >= 0),
    output_tokens    bigint CHECK (output_tokens >= 0),
    -- Provider-reported usage.cost (USD credits); null when the provider reported none (never stored as zero).
    cost_usd         numeric(18, 10) CHECK (cost_usd >= 0),
    duration_ms      bigint NOT NULL CHECK (duration_ms >= 0),
    outcome          text NOT NULL CHECK (outcome IN ('success', 'invalidOutput', 'notAWorkflow', 'providerError',
                                                      'credentialsRejected', 'modelNotAllowed')),
    workflow_id      uuid,
    -- Explanations: the draft revision or the release that was explained.
    revision         integer CHECK (revision >= 1),
    release_number   integer CHECK (release_number >= 1),
    created_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, workflow_id) REFERENCES workflow (workspace_id, id)
);
CREATE INDEX ai_generation_by_time ON ai_generation (created_at);
CREATE INDEX ai_generation_by_workspace ON ai_generation (workspace_id, created_at DESC);

GRANT SELECT, INSERT ON ai_generation TO ${app_role};
