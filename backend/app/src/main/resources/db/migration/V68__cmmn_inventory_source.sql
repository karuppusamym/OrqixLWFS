ALTER TABLE orqix.migration_job DROP CONSTRAINT migration_job_source_format_check;
ALTER TABLE orqix.migration_job ADD CONSTRAINT migration_job_source_format_check
    CHECK (source_format IN ('camunda7', 'camunda8', 'bpmn', 'pegaArchive', 'pegaMapping', 'dmn', 'camundaForm', 'pegaXpdl', 'cmmn'));
