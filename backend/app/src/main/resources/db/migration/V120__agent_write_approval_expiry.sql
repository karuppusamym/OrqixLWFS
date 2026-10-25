-- IN04 hardening (docs/build/S79): optional expiry of an agent write-tool approval.
-- expires_at is null for approvals without expiry (every approval made before this migration keeps behaving exactly as
-- before). An approval past expires_at authorizes nothing: the runtime and the compiler treat it like a missing or
-- revoked approval. 'expired' is only written when someone approves the same tool again (the one-active index would
-- otherwise refuse the new approval); until then an active row past its expiry is reported as expired by the API.
ALTER TABLE agent_write_tool_approval ADD COLUMN expires_at timestamptz;
ALTER TABLE agent_write_tool_approval ADD CONSTRAINT agent_write_tool_approval_expiry
    CHECK (expires_at IS NULL OR (expires_at > approved_at AND expires_at <= approved_at + interval '90 days'));
ALTER TABLE agent_write_tool_approval DROP CONSTRAINT agent_write_tool_approval_status_check;
ALTER TABLE agent_write_tool_approval ADD CONSTRAINT agent_write_tool_approval_status_check
    CHECK (status IN ('active', 'revoked', 'expired'));
ALTER TABLE agent_write_tool_approval ADD CONSTRAINT agent_write_tool_approval_expired
    CHECK (status <> 'expired' OR expires_at IS NOT NULL);
