-- Merge of Wave A completion and Wave B (6 November 2026): V250 (connector packages) and V260 (AI skills) each
-- recreated the grantable-permission checks with their own list, so after V260 the connector permissions could no
-- longer be granted. Recreate both checks with every grantable permission.
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read',
                          'workflow.approve-promotion','agent.approve-write-tool','connector.manage','connector.approve',
                          'ai.approve-skill','ai.evaluate'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read',
                              'workflow.approve-promotion','agent.approve-write-tool','connector.manage',
                              'connector.approve','ai.approve-skill','ai.evaluate') OR workflow_id IS NULL);
