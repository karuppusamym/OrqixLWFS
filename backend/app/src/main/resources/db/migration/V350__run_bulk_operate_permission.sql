-- CG-O01 (docs/build/S142): run.bulk-operate is grant-only and workspace-wide, like the other approval-style
-- permissions. Recreates both grantable-permission checks with every grantable permission on this base plus
-- run.bulk-operate (merge note in S142: a later migration that rebuilds these checks must keep it).
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read',
                          'workflow.approve-promotion','agent.approve-write-tool','connector.manage','connector.approve',
                          'ai.approve-skill','ai.evaluate','run.bulk-operate'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read',
                              'workflow.approve-promotion','agent.approve-write-tool','connector.manage',
                              'connector.approve','ai.approve-skill','ai.evaluate','run.bulk-operate')
           OR workflow_id IS NULL);
