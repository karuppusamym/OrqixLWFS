-- CG-W04 and CG-C09 (docs/build/S131): per-binding circuit breaker, request rate limit, call health and the step
-- retry policy of external worker jobs.

-- One row per binding that has seen a failure. state: closed, open or halfOpen. Open calls are deferred to open_until
-- without using an attempt; in halfOpen exactly one trial call (trial_until bounds it) decides.
CREATE TABLE connection_circuit (
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    connection_key text NOT NULL,
    state text NOT NULL DEFAULT 'closed' CHECK (state IN ('closed', 'open', 'halfOpen')),
    consecutive_failures integer NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    first_failure_at timestamptz,
    opened_at timestamptz,
    open_until timestamptz,
    cooldown_seconds integer NOT NULL DEFAULT 60 CHECK (cooldown_seconds BETWEEN 1 AND 86400),
    reason text CHECK (reason IS NULL OR length(reason) <= 120),
    trial_until timestamptz,
    times_opened integer NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_key)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON connection_circuit TO ${app_role};

-- maxRequestsPerMinute of a binding, enforced per pod as the limit divided by the live pods.
CREATE TABLE connection_rate_limit (
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    connection_key text NOT NULL,
    max_requests_per_minute integer CHECK (max_requests_per_minute IS NULL
                                           OR max_requests_per_minute BETWEEN 1 AND 100000),
    revision integer NOT NULL DEFAULT 1,
    updated_by uuid REFERENCES subject(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_key)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON connection_rate_limit TO ${app_role};

-- One row per call attempt (and per deferred or credential failure), kept 24 hours, for success rate and latency.
-- outcome: succeeded, retryable, permanent, unknown, deferred or tokenFailure. Never bodies, headers or secrets.
CREATE TABLE connection_call (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(id),
    connection_key text NOT NULL,
    operation text,
    outcome text NOT NULL CHECK (outcome IN ('succeeded', 'retryable', 'permanent', 'unknown', 'deferred',
                                             'tokenFailure')),
    http_status integer,
    error_code text CHECK (error_code IS NULL OR length(error_code) <= 64),
    duration_ms integer,
    at timestamptz NOT NULL
);
CREATE INDEX connection_call_binding ON connection_call (workspace_id, connection_key, at);
CREATE INDEX connection_call_at ON connection_call (at);
GRANT SELECT, INSERT, DELETE ON connection_call TO ${app_role};

-- Live application pods, for the per-pod share of a binding's request rate.
CREATE TABLE integration_pod (
    pod_id text PRIMARY KEY CHECK (length(pod_id) <= 200),
    seen_at timestamptz NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON integration_pod TO ${app_role};

-- Step retry policy of external worker jobs (null multiplier: the workflow-wide fixed delay).
ALTER TABLE external_job
    ADD COLUMN retry_multiplier numeric(4, 2) CHECK (retry_multiplier IS NULL OR retry_multiplier BETWEEN 1 AND 4),
    ADD COLUMN retry_max_delay_seconds integer CHECK (retry_max_delay_seconds IS NULL
                                                      OR retry_max_delay_seconds BETWEEN 5 AND 21600),
    ADD COLUMN honor_retry_after boolean NOT NULL DEFAULT true;
