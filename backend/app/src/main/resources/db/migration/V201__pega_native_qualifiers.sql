-- S115 native Pega conversion completion (docs/build/S115-pega-native-completion.md). Expand-only.
-- Inventory rows may now be rule delegation records (System-User-MyRules, provenance only) and keep each rule's
-- qualifier (property or date circumstance, date range). The per-rule conversion record keeps the qualifier in words
-- and the access groups a rule is delegated to.

ALTER TABLE migration_pega_rule DROP CONSTRAINT migration_pega_rule_status_check;
ALTER TABLE migration_pega_rule ADD CONSTRAINT migration_pega_rule_status_check
    CHECK (status IN ('manifest', 'rule', 'data', 'delegation', 'refused'));
ALTER TABLE migration_pega_rule ADD COLUMN qualifier jsonb;

ALTER TABLE migration_pega_rule_conversion ADD COLUMN circumstance text CHECK (circumstance IS NULL OR length(circumstance) <= 500);
ALTER TABLE migration_pega_rule_conversion ADD COLUMN delegated_to jsonb NOT NULL DEFAULT '[]'::jsonb;
