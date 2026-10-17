-- Allow bounded Pega archive inventory and explicit manual mapping packages; source bytes remain immutable.
ALTER TABLE migration_job DROP CONSTRAINT migration_job_source_format_check;
ALTER TABLE migration_job ADD CONSTRAINT migration_job_source_format_check
    CHECK (source_format IN ('camunda7', 'camunda8', 'bpmn', 'pegaArchive', 'pegaMapping'));
