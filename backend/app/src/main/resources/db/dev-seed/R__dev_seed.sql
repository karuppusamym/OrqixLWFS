-- Orqix development seed (docs/build/S1-engineering-baseline.md section 6.1, ticket B02.3).
-- Applied by Flyway only when the dev profile adds classpath:db/dev-seed to spring.flyway.locations.
-- Repeatable and idempotent: fixed ids, ON CONFLICT DO NOTHING; re-running never duplicates or overwrites data.
-- The template and connection definition come from /examples through Flyway placeholders (DevSeedPlaceholders);
-- they are never copied into this file. IdP group names match deploy/compose/keycloak/orqix-dev-realm.json.

-- System subject that owns seeded rows (created_by / owner / updated_by columns).
INSERT INTO subject (id, issuer, external_subject, display_name)
VALUES ('00000000-0000-7000-8000-000000000001', 'urn:orqix:system', 'system', 'Orqix system')
ON CONFLICT DO NOTHING;

INSERT INTO organization (id, key, name)
VALUES ('00000000-0000-7000-8000-000000000101', 'demo', 'Demo organization')
ON CONFLICT DO NOTHING;

-- Workspaces; the engine tenant id is the workspace id.
INSERT INTO workspace (id, organization_id, key, name, engine_tenant_id, is_test, allow_self_approval_workflows)
VALUES ('00000000-0000-7000-8000-000000000201', '00000000-0000-7000-8000-000000000101', 'purchasing', 'Purchasing',
        '00000000-0000-7000-8000-000000000201', false, false),
       ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000101', 'purchasing-sandbox',
        'Purchasing sandbox', '00000000-0000-7000-8000-000000000202', true, true),
       ('00000000-0000-7000-8000-000000000203', '00000000-0000-7000-8000-000000000101', 'facilities', 'Facilities',
        '00000000-0000-7000-8000-000000000203', false, false)
ON CONFLICT DO NOTHING;

-- Role bindings to IdP groups (S1 section 6.1). pat.platform gets PLATFORM_ADMIN from
-- orqix.identity.bootstrap-platform-admin-groups at startup (source = bootstrap), not from this seed.
INSERT INTO role_binding (id, scope_type, workspace_id, role, principal_type, principal_value, source, created_by)
VALUES
    -- Purchasing
    ('00000000-0000-7000-8000-000000000301', 'workspace', '00000000-0000-7000-8000-000000000201', 'WORKSPACE_ADMIN', 'idpGroup', 'orqix-purchasing-admins', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000302', 'workspace', '00000000-0000-7000-8000-000000000201', 'WORKFLOW_DESIGNER', 'idpGroup', 'orqix-purchasing-designers', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000303', 'workspace', '00000000-0000-7000-8000-000000000201', 'PUBLISHER', 'idpGroup', 'orqix-purchasing-publishers', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000304', 'workspace', '00000000-0000-7000-8000-000000000201', 'OPERATOR', 'idpGroup', 'orqix-purchasing-operators', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000305', 'workspace', '00000000-0000-7000-8000-000000000201', 'VIEWER', 'idpGroup', 'orqix-purchasing-viewers', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000306', 'workspace', '00000000-0000-7000-8000-000000000201', 'APPROVER', 'idpGroup', 'orqix-purchasing-managers', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000307', 'workspace', '00000000-0000-7000-8000-000000000201', 'APPROVER', 'idpGroup', 'orqix-purchasing-finance', 'seed', '00000000-0000-7000-8000-000000000001'),
    -- Purchasing sandbox (test workspace: designers also publish and act as requesters/approvers there, BD-10)
    ('00000000-0000-7000-8000-000000000311', 'workspace', '00000000-0000-7000-8000-000000000202', 'WORKSPACE_ADMIN', 'idpGroup', 'orqix-purchasing-admins', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000312', 'workspace', '00000000-0000-7000-8000-000000000202', 'WORKFLOW_DESIGNER', 'idpGroup', 'orqix-purchasing-designers', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000313', 'workspace', '00000000-0000-7000-8000-000000000202', 'PUBLISHER', 'idpGroup', 'orqix-purchasing-designers', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000314', 'workspace', '00000000-0000-7000-8000-000000000202', 'PUBLISHER', 'idpGroup', 'orqix-purchasing-publishers', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000315', 'workspace', '00000000-0000-7000-8000-000000000202', 'OPERATOR', 'idpGroup', 'orqix-purchasing-operators', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000316', 'workspace', '00000000-0000-7000-8000-000000000202', 'APPROVER', 'idpGroup', 'orqix-purchasing-managers', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000317', 'workspace', '00000000-0000-7000-8000-000000000202', 'APPROVER', 'idpGroup', 'orqix-purchasing-finance', 'seed', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000318', 'workspace', '00000000-0000-7000-8000-000000000202', 'APPROVER', 'idpGroup', 'orqix-purchasing-designers', 'seed', '00000000-0000-7000-8000-000000000001')
ON CONFLICT DO NOTHING;

-- Permission grants: workflow.start for requesters (workspace-wide), connection.manage for workspace admins.
INSERT INTO permission_grant (id, workspace_id, workflow_id, permission, principal_type, principal_value, created_by)
VALUES
    ('00000000-0000-7000-8000-000000000401', '00000000-0000-7000-8000-000000000201', NULL, 'workflow.start', 'idpGroup', 'orqix-purchasing-requesters', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000402', '00000000-0000-7000-8000-000000000201', NULL, 'connection.manage', 'idpGroup', 'orqix-purchasing-admins', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000411', '00000000-0000-7000-8000-000000000202', NULL, 'workflow.start', 'idpGroup', 'orqix-purchasing-requesters', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000412', '00000000-0000-7000-8000-000000000202', NULL, 'workflow.start', 'idpGroup', 'orqix-purchasing-designers', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000413', '00000000-0000-7000-8000-000000000202', NULL, 'connection.manage', 'idpGroup', 'orqix-purchasing-admins', '00000000-0000-7000-8000-000000000001'),
    ('00000000-0000-7000-8000-000000000421', '00000000-0000-7000-8000-000000000203', NULL, 'workflow.start', 'idpGroup', 'orqix-facilities-requesters', '00000000-0000-7000-8000-000000000001')
ON CONFLICT DO NOTHING;

-- Task-assignment groups used by the purchase template (candidateGroupRef).
INSERT INTO workspace_group (id, workspace_id, key, name, revision)
VALUES ('00000000-0000-7000-8000-000000000501', '00000000-0000-7000-8000-000000000201', 'managers', 'Managers', 1),
       ('00000000-0000-7000-8000-000000000502', '00000000-0000-7000-8000-000000000201', 'finance-approvers', 'Finance approvers', 1),
       ('00000000-0000-7000-8000-000000000511', '00000000-0000-7000-8000-000000000202', 'managers', 'Managers', 1),
       ('00000000-0000-7000-8000-000000000512', '00000000-0000-7000-8000-000000000202', 'finance-approvers', 'Finance approvers', 1)
ON CONFLICT DO NOTHING;

INSERT INTO workspace_group_member (workspace_id, group_id, principal_type, principal_value, created_by)
VALUES ('00000000-0000-7000-8000-000000000201', '00000000-0000-7000-8000-000000000501', 'idpGroup', 'orqix-purchasing-managers', '00000000-0000-7000-8000-000000000001'),
       ('00000000-0000-7000-8000-000000000201', '00000000-0000-7000-8000-000000000502', 'idpGroup', 'orqix-purchasing-finance', '00000000-0000-7000-8000-000000000001'),
       ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000511', 'idpGroup', 'orqix-purchasing-managers', '00000000-0000-7000-8000-000000000001'),
       ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000511', 'idpGroup', 'orqix-purchasing-designers', '00000000-0000-7000-8000-000000000001'),
       ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000512', 'idpGroup', 'orqix-purchasing-finance', '00000000-0000-7000-8000-000000000001')
ON CONFLICT DO NOTHING;

-- Connection procurement v1 (examples/connections/procurement.connection.json) and its binding to the stub.
INSERT INTO connection_definition (id, workspace_id, key, version, definition, definition_hash, created_by)
VALUES ('00000000-0000-7000-8000-000000000601', '00000000-0000-7000-8000-000000000201', 'procurement', 1,
        $orqix_json$ ${dev_procurement_definition} $orqix_json$::jsonb, '${dev_procurement_definition_hash}',
        '00000000-0000-7000-8000-000000000001'),
       ('00000000-0000-7000-8000-000000000611', '00000000-0000-7000-8000-000000000202', 'procurement', 1,
        $orqix_json$ ${dev_procurement_definition} $orqix_json$::jsonb, '${dev_procurement_definition_hash}',
        '00000000-0000-7000-8000-000000000001')
ON CONFLICT DO NOTHING;

INSERT INTO connection_binding (workspace_id, connection_key, base_url, auth_type, enabled, revision, updated_by)
VALUES ('00000000-0000-7000-8000-000000000201', 'procurement', '${dev_stub_base_url}', 'none', true, 1,
        '00000000-0000-7000-8000-000000000001'),
       ('00000000-0000-7000-8000-000000000202', 'procurement', '${dev_stub_base_url}', 'none', true, 1,
        '00000000-0000-7000-8000-000000000001')
ON CONFLICT DO NOTHING;

-- The purchase-approval workflow with draft revision 1 from the template (unpublished: publishing v1 is the demo).
INSERT INTO workflow (id, workspace_id, key, name, description, owner_subject_id, draft_revision, version, created_by)
SELECT '00000000-0000-7000-8000-000000000701', '00000000-0000-7000-8000-000000000201', t.doc ->> 'workflowKey',
       t.doc ->> 'name', t.doc -> 'metadata' ->> 'description', '00000000-0000-7000-8000-000000000001', 1, 1,
       '00000000-0000-7000-8000-000000000001'
FROM (SELECT $orqix_json$ ${dev_purchase_template} $orqix_json$::jsonb AS doc) t
ON CONFLICT DO NOTHING;

INSERT INTO workflow_draft_revision (workspace_id, workflow_id, revision, definition, schema_version, saved_by)
SELECT '00000000-0000-7000-8000-000000000201', '00000000-0000-7000-8000-000000000701', 1, t.doc,
       t.doc ->> 'schemaVersion', '00000000-0000-7000-8000-000000000001'
FROM (SELECT $orqix_json$ ${dev_purchase_template} $orqix_json$::jsonb AS doc) t
WHERE EXISTS (SELECT 1 FROM workflow WHERE id = '00000000-0000-7000-8000-000000000701')
ON CONFLICT DO NOTHING;
