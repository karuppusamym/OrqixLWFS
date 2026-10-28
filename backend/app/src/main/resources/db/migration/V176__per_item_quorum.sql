-- Per-item approvals with a quorum or one item at a time (docs/build/S104). item_count is the number of items of the
-- visit, known when the first item task opens; one-at-a-time steps have not opened every task yet, so the decision
-- cannot count the rows instead. Null for every other task and for S88 tasks created before this migration (all of
-- their items were opened at once, so the rows are the count).
ALTER TABLE task_context ADD COLUMN item_count integer;
ALTER TABLE task_context ADD CONSTRAINT task_context_item_count_bounds
    CHECK (item_count IS NULL OR (item_index IS NOT NULL AND item_count BETWEEN 1 AND 20 AND item_index < item_count));
