-- Pega Blueprint export layout (docs/build/S91). Expand-only.
-- A Pega Blueprint export can define several case types; one upload transcribes one of them. The names of the case
-- types the document defines but that were not transcribed are kept with the document so the findings can name them
-- on every later report (upload them again choosing that case type to transcribe one of them).

ALTER TABLE migration_blueprint_document
    ADD COLUMN other_case_types jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(other_case_types) = 'array' AND jsonb_array_length(other_case_types) <= 200);
