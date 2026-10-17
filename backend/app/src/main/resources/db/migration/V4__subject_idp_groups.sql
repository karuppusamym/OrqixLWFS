-- Expand-only: remember the IdP group values of each subject's most recent login.
-- Needed to evaluate eligibility of a subject who is not the caller (operator reassignment to an eligible member of a
-- candidate group, S5 section 4), because IdP group membership is otherwise only known inside that subject's session.
-- Bounded like the session snapshot (200 values of at most 256 characters). Not business data; never exported.
ALTER TABLE subject ADD COLUMN idp_groups jsonb NOT NULL DEFAULT '[]'::jsonb;
