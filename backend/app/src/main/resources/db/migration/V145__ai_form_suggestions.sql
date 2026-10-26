-- S90 AI form assistant (docs/build/S90-form-pattern-and-ai-assistant.md). Expand-only.
-- Request-form suggestions are metered in ai_generation like every other AI use, so the monthly budget stays one
-- number: kind 'form' is added to the CHECK. Existing outcome values are reused. As before, rows hold metering facts
-- only: never the description, the form, a prompt or the suggested fields.

ALTER TABLE ai_generation DROP CONSTRAINT IF EXISTS ai_generation_kind_check;
ALTER TABLE ai_generation ADD CONSTRAINT ai_generation_kind_check
    CHECK (kind IN ('draft', 'explain', 'agent', 'form'));
