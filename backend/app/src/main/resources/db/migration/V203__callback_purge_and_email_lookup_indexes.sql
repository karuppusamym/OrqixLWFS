-- Review fixes for S104 (docs/build/S104).
-- The closed-callback purge only looks at waits created before the retention cut-off.
CREATE INDEX callback_wait_by_created ON callback_wait (created_at);
-- The proposed approver of a list item is found by email, case-insensitively.
CREATE INDEX subject_by_lower_email ON subject (lower(email)) WHERE disabled_at IS NULL;
