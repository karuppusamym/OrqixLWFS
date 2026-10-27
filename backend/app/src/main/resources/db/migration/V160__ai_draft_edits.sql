-- STU02 conversational authoring (docs/build/S95-conversational-authoring.md). Expand-only.
-- Draft-edit proposals are metered in ai_generation like every other AI use, so the monthly budget stays one number:
-- kind 'draft-edit' is added to the CHECK. Existing outcome values are reused. Rows hold metering facts only: never
-- the instruction, the conversation, the draft summary, a prompt or the proposed operations.

ALTER TABLE ai_generation DROP CONSTRAINT IF EXISTS ai_generation_kind_check;
ALTER TABLE ai_generation ADD CONSTRAINT ai_generation_kind_check
    CHECK (kind IN ('draft', 'explain', 'agent', 'form', 'draft-edit'));
