-- Who archived a workflow, so the archived list can say it before someone restores the workflow (docs/build/S104).
-- Cleared again on restore. Workflows archived before this migration show no name.
ALTER TABLE workflow ADD COLUMN archived_by uuid REFERENCES subject (id);
