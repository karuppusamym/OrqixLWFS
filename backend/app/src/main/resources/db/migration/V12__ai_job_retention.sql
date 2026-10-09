-- Only terminal AI transport jobs expire. Drafts, audit, usage and effect records remain intact.
-- Runtime cleanup honors the configured idempotency retention and any still-live replay record.
GRANT DELETE ON ai_generation_job TO ${app_role};
