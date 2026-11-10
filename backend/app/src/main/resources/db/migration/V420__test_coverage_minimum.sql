-- CG-Q03 (docs/build/S148): coverage of shared workflow tests.
-- A suite version may carry a coverage minimum (whole percent per kind); a recorded run keeps the counts it covered.
-- Counts only, never sample values. No run_id: nothing here belongs to a request (orqix_purge_run is unchanged).

ALTER TABLE workflow_test_suite ADD COLUMN coverage_minimum jsonb;
ALTER TABLE workflow_test_suite ADD CONSTRAINT workflow_test_suite_coverage_minimum_object
    CHECK (coverage_minimum IS NULL OR jsonb_typeof(coverage_minimum) = 'object');

ALTER TABLE workflow_test_result ADD COLUMN coverage jsonb;
ALTER TABLE workflow_test_result ADD CONSTRAINT workflow_test_result_coverage_object
    CHECK (coverage IS NULL OR jsonb_typeof(coverage) = 'object');
