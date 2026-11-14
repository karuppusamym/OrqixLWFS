-- S132 (CG-E10): a worker may end a job with a business error code; it is kept with the job for operators.
ALTER TABLE external_job ADD COLUMN error_code text CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{1,39}$');
