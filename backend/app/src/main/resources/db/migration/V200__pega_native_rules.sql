-- S112 native Pega rule archive conversion, MIG11 (docs/build/S112-pega-native-conversion.md). Expand-only.
-- The archive bytes and their SHA-256 stay on migration_job (immutable, like every source). Each archive entry is
-- inventoried once at upload with its own SHA-256, decoded identity and refusal reason. When a starting point is
-- converted, the per-rule result (conversion outcome, target kind and ids, target fragment hash) is recorded next to the
-- inventory, so reviewers can trace every rule to what it became, or why it did not.
-- Flyway placeholder: ${app_role} = runtime DML role (e.g. orqix_app).

ALTER TABLE migration_job DROP CONSTRAINT migration_job_source_format_check;
ALTER TABLE migration_job ADD CONSTRAINT migration_job_source_format_check
    CHECK (source_format IN ('camunda7', 'camunda8', 'bpmn', 'pegaArchive', 'pegaMapping', 'dmn', 'camundaForm', 'pegaXpdl', 'cmmn',
                             'pegaBlueprint', 'pegaRules'));

CREATE TABLE migration_pega_rule (
    workspace_id     uuid NOT NULL,
    migration_id     uuid NOT NULL,
    entry_path       text NOT NULL CHECK (length(entry_path) BETWEEN 1 AND 500),
    entry_sha256     char(64) NOT NULL,
    status           text NOT NULL CHECK (status IN ('manifest', 'rule', 'data', 'refused')),
    reason           text CHECK (reason IS NULL OR length(reason) <= 80),
    rule_type        text CHECK (rule_type IS NULL OR length(rule_type) <= 200),
    class_name       text CHECK (class_name IS NULL OR length(class_name) <= 200),
    rule_name        text CHECK (rule_name IS NULL OR length(rule_name) <= 200),
    ruleset          text CHECK (ruleset IS NULL OR length(ruleset) <= 200),
    ruleset_version  text CHECK (ruleset_version IS NULL OR ruleset_version ~ '^[0-9]{2}-[0-9]{2}-[0-9]{2}$'),
    availability     text CHECK (availability IS NULL OR availability IN ('Yes', 'No', 'Blocked', 'Withdrawn', 'Final')),
    circumstanced    boolean NOT NULL DEFAULT false,
    PRIMARY KEY (migration_id, entry_path),
    FOREIGN KEY (workspace_id, migration_id) REFERENCES migration_job (workspace_id, id)
);

CREATE TABLE migration_pega_rule_conversion (
    workspace_id     uuid NOT NULL,
    migration_id     uuid NOT NULL,
    entry_path       text NOT NULL,
    start_rule       text NOT NULL CHECK (length(start_rule) BETWEEN 1 AND 500),
    resolution       text NOT NULL CHECK (length(resolution) <= 40),
    conversion       text NOT NULL CHECK (conversion IN ('converted', 'review', 'blocked', 'notUsed', 'shadowed', 'supported')),
    target_kind      text CHECK (target_kind IS NULL OR length(target_kind) <= 40),
    targets          jsonb NOT NULL DEFAULT '[]'::jsonb,
    target_sha256    char(64),
    reasons          jsonb NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (migration_id, entry_path),
    FOREIGN KEY (migration_id, entry_path) REFERENCES migration_pega_rule (migration_id, entry_path),
    FOREIGN KEY (workspace_id, migration_id) REFERENCES migration_job (workspace_id, id)
);

GRANT SELECT, INSERT ON migration_pega_rule TO ${app_role};
GRANT SELECT, INSERT ON migration_pega_rule_conversion TO ${app_role};
