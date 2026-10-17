-- Acyclic workflow nodes execute once per run; retries retain the same generated artifact, including after removal.
ALTER TABLE run_attachment ADD COLUMN generated_node_id text
    CHECK (generated_node_id IS NULL OR (run_id IS NOT NULL AND length(generated_node_id) BETWEEN 1 AND 64));
CREATE UNIQUE INDEX run_attachment_generated_node ON run_attachment(run_id, generated_node_id)
    WHERE generated_node_id IS NOT NULL;
