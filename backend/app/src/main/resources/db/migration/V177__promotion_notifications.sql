-- Promotion approval notifications (docs/build/S106, PL02a). In-app only:
--   promotionRequest  - to each person who may approve promotions in the target workspace (never the requester);
--   promotionDecision - to the requester, in the source workspace, when the request is approved or rejected.
-- A decision notification points at the source workflow (workflow_id); a request notification points at nothing.
ALTER TABLE notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE notification ADD CONSTRAINT notification_kind_check
    CHECK (kind IN ('workflowMessage', 'runAttention', 'discussionMention', 'promotionRequest', 'promotionDecision'));
