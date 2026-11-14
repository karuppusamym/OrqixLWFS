-- Merge of Wave D (8 November 2026): V350 (bulk operations) and V370 (alert rules) each rebuilt the grantable-
-- permission checks with their own list, so after V370 run.bulk-operate could no longer be granted. Recreate both
-- checks with every grantable permission (as V290 did for Waves A and B). Keep this migration after every migration
-- that adds a grantable permission.
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read',
                          'workflow.approve-promotion','agent.approve-write-tool','connector.manage','connector.approve',
                          'ai.approve-skill','ai.evaluate','run.bulk-operate','alert.manage'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read',
                              'workflow.approve-promotion','agent.approve-write-tool','connector.manage',
                              'connector.approve','ai.approve-skill','ai.evaluate','run.bulk-operate','alert.manage')
           OR workflow_id IS NULL);
