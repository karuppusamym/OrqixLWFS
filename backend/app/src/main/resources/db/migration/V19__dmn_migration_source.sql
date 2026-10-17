-- Allow Camunda DMN decision files as a migration source (docs/build/S11-camunda-import.md "DMN import").
-- Expand-only: existing rows are unaffected; source bytes stay immutable like every other job.
ALTER TABLE migration_job DROP CONSTRAINT migration_job_source_format_check;
ALTER TABLE migration_job ADD CONSTRAINT migration_job_source_format_check
    CHECK (source_format IN ('camunda7', 'camunda8', 'bpmn', 'pegaArchive', 'pegaMapping', 'dmn'));
