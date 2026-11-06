-- CG-M07 (docs/build/S135): agent test suites of an agent connection (Test tab): cases with an input, mock MCP results
-- from fixtures, tool-sequence assertions and LLM-as-judge assertions with a pinned judge model. Suites hold authored
-- test data only, never run data. The last result keeps verdicts, tool sequences and judge scores per case.

CREATE TABLE agent_test_suite (
    workspace_id    uuid NOT NULL REFERENCES workspace (id),
    connection_key  text NOT NULL CHECK (connection_key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    suite           jsonb NOT NULL CHECK (jsonb_typeof(suite) = 'object'),
    last_result     jsonb,
    last_run_at     timestamptz,
    changed_by      uuid NOT NULL REFERENCES subject (id),
    changed_at      timestamptz NOT NULL DEFAULT now(),
    version         integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    PRIMARY KEY (workspace_id, connection_key)
);
GRANT SELECT, INSERT, UPDATE ON agent_test_suite TO ${app_role};
