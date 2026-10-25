-- IN04 hardening (docs/build/S85): retention of agent_write_call records, following the DOC08 attachment retention
-- pattern (V56). A per-workspace number of days after a request ended; the hourly sweep deletes the write records
-- (argument digest and size, approver, requester, reconciliation note) of ended requests older than that. It never
-- deletes a record of a running request, and never any record of a request that still has a write whose outcome is
-- unknown or not final. The effect journal entries themselves are not touched.
CREATE TABLE workspace_agent_write_policy (
    workspace_id   uuid PRIMARY KEY REFERENCES workspace (id),
    retention_days integer CHECK (retention_days IS NULL OR retention_days BETWEEN 1 AND 3650),
    version        integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by     uuid NOT NULL REFERENCES subject (id),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE ON workspace_agent_write_policy TO ${app_role};
GRANT DELETE ON agent_write_call TO ${app_role};
