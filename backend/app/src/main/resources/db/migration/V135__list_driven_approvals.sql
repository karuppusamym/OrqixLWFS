-- List-driven approvals (docs/build/S88): a task of such a step decides one item of a request list. item_index is the
-- 0-based list position, item_label what the task shows for the item and item_scope the engine execution that holds
-- all item tasks of one visit of the step (a step on a loop can be visited again). All three are null for every other
-- task.
ALTER TABLE task_context ADD COLUMN item_index integer;
ALTER TABLE task_context ADD COLUMN item_label text;
ALTER TABLE task_context ADD COLUMN item_scope text;
ALTER TABLE task_context ADD CONSTRAINT task_context_item_index_bounds CHECK (item_index IS NULL OR item_index BETWEEN 0 AND 19);
ALTER TABLE task_context ADD CONSTRAINT task_context_item_label_length CHECK (item_label IS NULL OR length(item_label) <= 200);
ALTER TABLE task_context ADD CONSTRAINT task_context_item_complete
    CHECK ((item_index IS NULL) = (item_scope IS NULL));
-- One task per item and visit.
CREATE UNIQUE INDEX task_context_one_per_item ON task_context (workspace_id, item_scope, item_index) WHERE item_scope IS NOT NULL;
