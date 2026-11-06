-- S129: ended "Keep checking until..." and safety-net rows are purged after a retention (orqix.polling.retention,
-- default 30 days), with one audit entry per workspace and batch. Only ended rows are deleted.
GRANT DELETE ON poll_wait TO ${app_role};
CREATE INDEX poll_wait_ended ON poll_wait (ended_at) WHERE ended_at IS NOT NULL;
