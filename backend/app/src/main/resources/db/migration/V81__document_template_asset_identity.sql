-- PL01 shared assets (docs/build/S57): document templates become shared assets with a stable key (used by versioned
-- workflow references, so a promoted workflow resolves by key, never by another workspace's id), an owner (a label that
-- grants nothing) and a revision for compare-and-set owner/archive changes. Existing templates get a generated key and
-- their creator as owner.
ALTER TABLE document_template ADD COLUMN key text;
ALTER TABLE document_template ADD COLUMN owner_subject_id uuid REFERENCES subject(id);
ALTER TABLE document_template ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision >= 1);
UPDATE document_template SET key = 'template-' || substr(replace(id::text, '-', ''), 1, 12), owner_subject_id = created_by;
ALTER TABLE document_template ALTER COLUMN key SET NOT NULL;
ALTER TABLE document_template ALTER COLUMN owner_subject_id SET NOT NULL;
ALTER TABLE document_template ADD CONSTRAINT document_template_key_shape CHECK (key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$');
CREATE UNIQUE INDEX document_template_key_unique ON document_template (workspace_id, key);
