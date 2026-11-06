-- CG-M01 (docs/build/S135): in-app notification to the administrator who pinned a tool server's catalog when the
-- runtime refuses one of its tools (TOOL_SCHEMA_CHANGED, missing or unpinned). The message names the server and tool,
-- never a schema or a value. Merge note: recreates the kind CHECK with every kind (as V177 did).
ALTER TABLE notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE notification ADD CONSTRAINT notification_kind_check
    CHECK (kind IN ('workflowMessage', 'runAttention', 'discussionMention', 'promotionRequest', 'promotionDecision',
                    'agentToolChanged'));
