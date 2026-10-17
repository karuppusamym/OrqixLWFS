-- Expand-only: human request references (ORQ-1001, ORQ-1002, ...), unique per workspace.
-- The first 8 hex characters of a time-ordered UUIDv7 are not a usable short id (requests created in the same
-- minute share them), so every run gets a per-workspace sequence number instead. The counter lives on the workspace
-- row and is incremented inside the start transaction (UPDATE ... RETURNING), so concurrent starts never share a
-- number and a rolled-back start does not leave a gap.

ALTER TABLE workspace ADD COLUMN run_seq bigint NOT NULL DEFAULT 1000 CHECK (run_seq >= 1000);
ALTER TABLE workflow_run ADD COLUMN reference text;

-- Backfill existing runs in creation order: ORQ-1001 is the oldest run of each workspace.
WITH numbered AS (
    SELECT id, 1000 + row_number() OVER (PARTITION BY workspace_id ORDER BY created_at, id) AS n
    FROM workflow_run
)
UPDATE workflow_run r SET reference = 'ORQ-' || numbered.n FROM numbered WHERE r.id = numbered.id;

UPDATE workspace w SET run_seq = 1000 + (SELECT count(*) FROM workflow_run r WHERE r.workspace_id = w.id);

ALTER TABLE workflow_run ALTER COLUMN reference SET NOT NULL;
ALTER TABLE workflow_run ADD CONSTRAINT workflow_run_reference_format CHECK (reference ~ '^ORQ-[1-9][0-9]{3,}$');
ALTER TABLE workflow_run ADD CONSTRAINT workflow_run_reference_unique UNIQUE (workspace_id, reference);

-- Safety net for writers that do not allocate a reference themselves (the application always does, see RunStore).
CREATE FUNCTION workflow_run_default_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    next_seq bigint;
BEGIN
    IF NEW.reference IS NULL THEN
        UPDATE workspace SET run_seq = run_seq + 1 WHERE id = NEW.workspace_id RETURNING run_seq INTO next_seq;
        NEW.reference := 'ORQ-' || next_seq;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workflow_run_default_reference BEFORE INSERT ON workflow_run
    FOR EACH ROW EXECUTE FUNCTION workflow_run_default_reference();
