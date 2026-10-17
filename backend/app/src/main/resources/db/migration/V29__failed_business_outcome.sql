-- Keep persistence aligned with the canonical/API OutcomeKind enumeration.
ALTER TABLE workflow_run DROP CONSTRAINT workflow_run_outcome_kind_check;
ALTER TABLE workflow_run ADD CONSTRAINT workflow_run_outcome_kind_check
    CHECK (outcome_kind IN ('success', 'rejected', 'failed'));
