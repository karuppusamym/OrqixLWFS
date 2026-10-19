import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, ensureTemplatePublished, submitPurchase, workspaceId } from '../support/app'
import { apiSend, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * IN04 "Agent writes" page (docs/build/S75). A connection manager declares an email adapter and a write tool; a person
 * holding the grant-only agent.approve-write-tool approves it for a workflow (reason required), then revokes it (reason,
 * version). An operator records the outcome of an unknown write. The database refuses a write whose approver is also its
 * requester (separation of duties). The real stack has no language model, so the run-time refusal of the approver's own
 * request (AgentWriteToolsTest) is not driven from the browser; the unknown write row is created in the verification
 * database (E2E_DB_CONTAINER).
 */
test.skip(MOCK || !process.env.E2E_DB_CONTAINER, 'Needs the real stack and its verification database.')

const DB = process.env.E2E_DB_CONTAINER as string
const psql = (sql: string): string => execFileSync('docker', ['exec', DB, 'psql', '-U', 'postgres', '-d', 'orqix', '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-c', sql], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()

test('approve, revoke and record the outcome of an agent write; separation of duties is enforced', async ({ sessions }) => {
  const admin = await sessions.as('wade.admin')
  const ws = await workspaceId(admin)
  const suffix = uniq('w').toLowerCase().replace(/[^a-z0-9]/g, '')
  const mail = `agent-mail-${suffix}`
  const connection = `vendor-writer-${suffix}`
  sessions.allowHttpErrors()

  const adapter = await apiSend(admin, 'POST', `/workspaces/${ws}/adapters`, { key: mail, kind: 'email', name: `Procurement mail ${suffix}`, secretRef: 'agent-mail',
    config: { from: 'noreply@example.com', allowedRecipientDomains: ['example.com'], maxRecipients: 2 } })
  expect(adapter.status, JSON.stringify(adapter.body)).toBe(201)
  const definition = JSON.parse(readFileSync('../../examples/connections/vendor-risk-assistant.connection.json', 'utf8'))
  definition.key = connection
  definition.name = `Vendor writer ${suffix}`
  definition.operations.assessVendor.agent.maxToolCalls = 5
  definition.operations.assessVendor.agent.writeTools = [{ name: 'notify_procurement', label: 'Tell procurement', description: 'Emails the procurement team a short note about a high-risk vendor.', adapter: mail, kind: 'email', maxCalls: 1, to: ['procurement@example.com'] }]
  const registered = await apiSend(admin, 'POST', `/workspaces/${ws}/connections/${connection}/versions`, { definition })
  expect(registered.status, JSON.stringify(registered.body)).toBe(201)

  const approver = await sessions.as('mani.manager')
  const approverId = (await apiGet<{ subject: { id: string } }>(approver, '/me')).subject.id
  const grant = await apiSend(admin, 'POST', `/workspaces/${ws}/access/grants`, { permission: 'agent.approve-write-tool', principal: { type: 'subject', value: approverId } })
  expect([201, 409]).toContain(grant.status)

  await approver.goto(`/w/${ws}/agent-writes`)
  await expect(approver.getByRole('heading', { level: 1, name: 'Agent writes' })).toBeVisible()
  const row = approver.getByRole('row').filter({ hasText: `Vendor writer ${suffix}` })
  await expect(row).toContainText('Tell procurement')
  await expect(row).toContainText('procurement@example.com')
  await expect(row).toContainText('Not approved')
  await expectNoSeriousViolations(approver, 'Agent writes, tool not approved')

  await row.getByRole('button', { name: 'Approve Tell procurement for a workflow' }).click()
  const dialog = approver.getByRole('dialog')
  await dialog.getByLabel('Workflow key').fill('purchase-approval')
  await dialog.getByRole('button', { name: 'Approve', exact: true }).click()
  await expect(dialog.getByText('Say why this agent may send these messages.')).toBeVisible()
  await dialog.getByLabel('Reason').fill('Procurement asked for this note')
  await expectNoSeriousViolations(approver, 'Agent writes, approve dialog')
  await dialog.getByRole('button', { name: 'Approve', exact: true }).click()
  await expect(approver.getByRole('row').filter({ hasText: `Vendor writer ${suffix}` })).toContainText('purchase-approval')
  const overview = await apiGet<{ approvals: Array<{ id: string; connectionKey: string; status: string }> }>(approver, `/workspaces/${ws}/agent-write-tools`)
  const approval = overview.approvals.find((a) => a.connectionKey === connection && a.status === 'active')
  expect(approval).toBeTruthy()

  // An unknown write, made in the verification database for a real request by someone other than the approver.
  const publisher = await sessions.as('paul.publisher')
  const templateId = await ensureTemplatePublished(publisher, ws)
  const requester = await sessions.as('rhea.requester')
  const { runUrl } = await submitPurchase(requester, ws, templateId, { title: uniq('Agent write'), purpose: 'Unknown write', costCenter: 'OPS', amount: '300' })
  const runId = /\/runs\/([^/?#]+)/.exec(runUrl)?.[1] as string
  const requesterId = (await apiGet<{ subject: { id: string } }>(requester, '/me')).subject.id
  const agentEffect = randomUUID()
  const writeEffect = randomUUID()
  const hash = '0'.repeat(64)
  psql(`INSERT INTO orqix.effect_journal (id, workspace_id, run_id, node_id, occurrence, effect_key, connection_key, connection_version, operation, effect_class, request_hash, state, result)
        VALUES ('${agentEffect}', '${ws}', '${runId}', 'assess', 1, '${agentEffect}', '${connection}', 1, 'assessVendor', 'readOnly', '${hash}', 'succeeded', '{}'::jsonb),
               ('${writeEffect}', '${ws}', '${runId}', 'assess:write:notify_procurement:1', 1, '${writeEffect}', '${mail}', 1, 'agentWrite.email', 'nonIdempotent', '${hash}', 'unknown', NULL)`)
  const insertCall = (requestedBy: string): string => `INSERT INTO orqix.agent_write_call (id, workspace_id, run_id, agent_effect_id, node_id, effect_key, connection_key, connection_version, operation, tool_name, call_index, adapter_key, kind, approval_id, approved_by, requested_by, argument_sha256, argument_chars)
        VALUES ('${randomUUID()}', '${ws}', '${runId}', '${agentEffect}', 'assess:write:notify_procurement:1', '${writeEffect}', '${connection}', 1, 'assessVendor', 'notify_procurement', 1, '${mail}', 'email', '${approval!.id}', '${approverId}', '${requestedBy}', '${hash}', 42)`
  // Separation of duties in the database: approver == requester is refused.
  expect(() => psql(insertCall(approverId))).toThrow(/agent_write_call_separation/)
  psql(insertCall(requesterId))

  const operator = await sessions.as('olga.operator')
  await operator.goto(`/w/${ws}/agent-writes`)
  const write = operator.getByRole('row').filter({ hasText: 'notify_procurement (use 1)' }).filter({ has: operator.locator(`a[href$="/runs/${runId}"]`) })
  await expect(write).toBeVisible()
  await expectNoSeriousViolations(operator, 'Agent writes, unknown write listed')
  await write.getByRole('button', { name: /Record the outcome of notify_procurement use 1/ }).click()
  const record = operator.getByRole('dialog')
  await record.getByRole('button', { name: 'Record outcome' }).click()
  await expect(record.getByText('Choose what you found at the target.')).toBeVisible()
  await record.getByLabel(/It was not delivered/).check()
  await record.getByLabel('What you checked').fill('Looked in the procurement mailbox, nothing arrived')
  await expectNoSeriousViolations(operator, 'Agent writes, record outcome dialog')
  await record.getByRole('button', { name: 'Record outcome' }).click()
  await operator.getByLabel('Only writes whose outcome is unknown').uncheck()
  await expect(operator.getByRole('row').filter({ hasText: 'notify_procurement (use 1)' }).filter({ has: operator.locator(`a[href$="/runs/${runId}"]`) })).toContainText('Looked in the procurement mailbox')

  // Revoke (reason required).
  await approver.reload()
  await approver.getByRole('row').filter({ hasText: `Vendor writer ${suffix}` }).getByRole('button', { name: 'Revoke Tell procurement for purchase-approval' }).click()
  const revoke = approver.getByRole('dialog')
  await revoke.getByRole('button', { name: 'Revoke', exact: true }).click()
  await revoke.getByLabel('Reason').fill('Programme paused')
  await revoke.getByRole('button', { name: 'Revoke', exact: true }).click()
  await expect(approver.getByRole('row').filter({ hasText: `Vendor writer ${suffix}` })).toContainText('Not approved')
  await expectNoSeriousViolations(approver, 'Agent writes, after revoke')
})
