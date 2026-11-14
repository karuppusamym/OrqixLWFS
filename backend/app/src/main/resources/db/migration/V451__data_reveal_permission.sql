-- CG-I05 (docs/build/S151): data.reveal is a grant-only, workspace-wide permission to reveal masked personal and
-- sensitive request values (each reveal is audited). Recreates both permission_grant checks with every grantable
-- permission of V400 plus 'data.reveal'. Merge note: a sibling migration adding grantable permissions must list all.
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_permission_check;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_permission_check
    CHECK (permission IN ('workflow.start','connection.manage','message.send','document.browse','job.work','audit.read',
                          'workflow.approve-promotion','agent.approve-write-tool','connector.manage','connector.approve',
                          'ai.approve-skill','ai.evaluate','run.bulk-operate','alert.manage','data.reveal'));
ALTER TABLE permission_grant DROP CONSTRAINT permission_grant_connection_is_workspace_wide;
ALTER TABLE permission_grant ADD CONSTRAINT permission_grant_connection_is_workspace_wide
    CHECK (permission NOT IN ('connection.manage','message.send','document.browse','job.work','audit.read',
                              'workflow.approve-promotion','agent.approve-write-tool','connector.manage',
                              'connector.approve','ai.approve-skill','ai.evaluate','run.bulk-operate','alert.manage',
                              'data.reveal')
           OR workflow_id IS NULL);
