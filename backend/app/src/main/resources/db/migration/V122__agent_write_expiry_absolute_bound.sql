-- IN04 hardening (docs/build/S85): the 90-day upper bound of an approval expiry, as an absolute duration.
-- V120 wrote the bound as approved_at + interval '90 days'. For timestamptz, adding a day interval follows the session
-- time zone: in a zone with daylight saving time (the JDBC driver sends the JVM's zone, e.g. America/New_York) a 90-day
-- window that crosses the spring change is 90 * 24 - 1 hours long, so the application's approved_at + 90 * 24 hours
-- (Duration.ofDays(90)) violated the constraint and approving for 90 days failed with an internal error. The bound is
-- now 2160 hours, which does not depend on the session time zone and equals what the application computes.
ALTER TABLE agent_write_tool_approval DROP CONSTRAINT agent_write_tool_approval_expiry;
ALTER TABLE agent_write_tool_approval ADD CONSTRAINT agent_write_tool_approval_expiry
    CHECK (expires_at IS NULL OR (expires_at > approved_at AND expires_at <= approved_at + interval '2160 hours'));
