-- CG-P06 (docs/build/S150): the OpenTelemetry trace id of the work that wrote a timeline event or the latest attempt of
-- an effect, so an operator can go from a request's history or an effect to its trace. 32 lowercase hex characters
-- (W3C trace-context); NULL when tracing is off or the row is older than this migration.
-- Adding a nullable column without a default does not rewrite the table. The checks are NOT VALID: existing rows are all
-- NULL, and validating would scan run_event (the largest table) under an exclusive lock.
ALTER TABLE run_event ADD COLUMN trace_id text;
ALTER TABLE run_event ADD CONSTRAINT run_event_trace_id_shape
    CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$') NOT VALID;

ALTER TABLE effect_journal ADD COLUMN trace_id text;
ALTER TABLE effect_journal ADD CONSTRAINT effect_journal_trace_id_shape
    CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$') NOT VALID;
