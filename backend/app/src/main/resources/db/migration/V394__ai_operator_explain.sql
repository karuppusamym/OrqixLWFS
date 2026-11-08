-- S146 part C, CG-A07 "Explain this request" for operators (docs/build/S146-parts/C-operator-explain.md). Expand-only.
-- An AI explanation of a stuck request is metered in ai_generation like every other AI use, so the monthly budget stays
-- one number: kind 'aiExplain' is added to the CHECK. The list is the shared S146 superset ('aiExtract' and 'aiOcr'
-- belong to part B, document extraction), so whichever migration runs last leaves the same constraint. Existing
-- outcome values are reused. Rows hold metering facts only: never the facts, the prompt or the explanation.

ALTER TABLE ai_generation DROP CONSTRAINT IF EXISTS ai_generation_kind_check;
ALTER TABLE ai_generation ADD CONSTRAINT ai_generation_kind_check
    CHECK (kind IN ('draft', 'explain', 'agent', 'form', 'draft-edit', 'aiTask', 'aiEval', 'aiExtract', 'aiOcr',
                    'aiExplain'));
