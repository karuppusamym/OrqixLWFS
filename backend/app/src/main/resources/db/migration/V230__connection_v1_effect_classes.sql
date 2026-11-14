-- CG-C01 / CG-C02 (docs/build/S130-connection-definition-v1.md): connection definitions v1 add two effect classes.
-- idempotentByLookup: an uncertain outcome is settled by a read-only lookup of the key Orqix wrote into the request;
-- the decision is kept in effect_journal.resolution (kind lookup). idempotentNatural: repeating the same values gives
-- the same result. Expand-only: existing rows keep their classes.
ALTER TABLE effect_journal DROP CONSTRAINT IF EXISTS effect_journal_effect_class_check;
ALTER TABLE effect_journal ADD CONSTRAINT effect_journal_effect_class_check CHECK (effect_class IN (
    'readOnly', 'idempotentWithKey', 'idempotentByLookup', 'idempotentNatural', 'nonIdempotent'));
