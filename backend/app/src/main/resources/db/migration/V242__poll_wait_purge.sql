-- S129: ended "Keep checking until..." and safety-net rows are purged after a retention (orqix.polling.retention,
-- default 30 days), with one audit entry per workspace and batch. Only ended rows are deleted.
-- Renumbered from V212 at the Wave A merge so it sorts after V220-V241; safe to rerun on a database that ran V212.
GRANT DELETE ON poll_wait TO ${app_role};
CREATE INDEX IF NOT EXISTS poll_wait_ended ON poll_wait (ended_at) WHERE ended_at IS NOT NULL;
