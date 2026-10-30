import { createHmac, randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { request } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { BASE_URL, MOCK } from '../support/env'
import { decideOpenTask, ensureTemplatePublished, selectStep, submitPurchase, workspaceId } from '../support/app'
import { apiGet } from '../support/app'
import { apiSend, apiRead, createWorkflow, editDraft, openTaskOfRun, publish, revokeGrant, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * IN02 follow-ups (docs/build/S73) and signed inbound delivery (S47, listed as not run in S62):
 *  - a "Send through an adapter" step (stub only) is edited in the studio, passes the check, is published and a request
 *    runs through it;
 *  - a signed event is delivered with the trigger's HMAC (bad signature, stale timestamp and repeat are handled), appears
 *    under "Received events" on My requests, and a person starts a request from it;
 *  - the adapter and the trigger are edited and disabled in Settings (a disabled trigger refuses senders), and old events
 *    are purged.
 * Real stack only; the signing secret is a test file in the private stack's own secrets directory (E2E_INBOUND_SECRETS_DIR).
 */
test.skip(MOCK || !process.env.E2E_INBOUND_SECRETS_DIR || !process.env.E2E_DB_CONTAINER, 'Needs the real stack, its signing secret file and its database.')

/**
 * The designer's connection.manage grant is taken back afterwards, even when the test fails: later specs and
 * tools/api-e2e/e2e_authoring.py check that design permission alone cannot manage connections.
 */
test.afterEach(async ({ sessions }) => {
  if (MOCK) return
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const subject = (await apiGet<{ subject: { id: string } }>(designer, '/me')).subject.id
  await revokeGrant(await sessions.as('wade.admin'), ws, 'connection.manage', subject)
})

const SECRETS = process.env.E2E_INBOUND_SECRETS_DIR as string
const DB = process.env.E2E_DB_CONTAINER as string
const psql = (sql: string): string => execFileSync('docker', ['exec', DB, 'psql', '-U', 'postgres', '-d', 'orqix', '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-c', sql], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()

async function deliver(ws: string, triggerId: string, body: object, opts: { eventId?: string; secret?: string; timestamp?: number } = {}): Promise<{ status: number; eventId: string }> {
  const secret = opts.secret ?? readFileSync(path.join(SECRETS, 'orders-signing'), 'utf8').trim()
  const eventId = opts.eventId ?? randomUUID()
  const timestamp = String(opts.timestamp ?? Math.floor(Date.now() / 1000))
  const raw = JSON.stringify(body)
  const signature = 'sha256=' + createHmac('sha256', secret).update(`${timestamp}.${raw}`).digest('hex')
  const context = await request.newContext({ baseURL: BASE_URL })
  const response = await context.post(`/api/v1/workspaces/${ws}/inbound/${triggerId}`, {
    data: raw, headers: { 'Content-Type': 'application/json', 'X-Orqix-Event-Id': eventId, 'X-Orqix-Timestamp': timestamp, 'X-Orqix-Signature': signature },
  })
  const status = response.status()
  await context.dispose()
  return { status, eventId }
}

test('adapter step, signed events, starting a request from an event, editing, disabling and purging', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const administrator = await sessions.as('wade.admin')
  sessions.allowHttpErrors()
  const me = await apiGet<{ subject: { id: string } }>(designer, '/me')
  const granted = await apiSend(administrator, 'POST', `/workspaces/${ws}/access/grants`, { permission: 'connection.manage', principal: { type: 'subject', value: me.subject.id } })
  expect([201, 409]).toContain(granted.status)

  const mail = uniq('stepmail').toLowerCase().replace(/[^a-z0-9-]/g, '')
  const created = await apiSend(designer, 'POST', `/workspaces/${ws}/adapters`, { key: mail, kind: 'email', name: 'Order mail', secretRef: 'kafka-signing',
    config: { from: 'noreply@example.com', allowedRecipientDomains: ['example.com'], maxRecipients: 2 } })
  expect(created.status, JSON.stringify(created.body)).toBe(201)

  // ---- "Send through an adapter" step
  const wf = await createWorkflow(designer, ws, 'Purchase approval')
  await editDraft(designer, ws, wf.id, (d) => {
    d.nodes.push({ id: 'mailOut', type: 'sendAdapter', label: 'Email the buyer', config: { adapter: mail, kind: 'email', to: ['buyer@example.com'], subject: 'Order {{title}}', body: 'Your order {{title}} was placed.', inputMapping: { title: '/input/title' } } })
    for (const t of d.transitions) if (t.id === 't10') t.to = 'mailOut'
    d.transitions.push({ id: 'm1', from: 'mailOut', to: 'fulfilled', on: 'next' })
    delete d.uiLayout
  })
  await designer.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await designer.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(designer, 'mailOut')
  const inspector = designer.getByTestId('inspector')
  await expect(inspector.getByLabel('Recipients')).toHaveValue('buyer@example.com')
  await expect(inspector.getByLabel('Subject')).toHaveValue('Order {{title}}')
  await expectNoSeriousViolations(designer, 'Studio, Send through an adapter inspector')
  await designer.getByRole('button', { name: 'Check workflow' }).click()
  await expect(designer.getByText(/no problems|ready to publish|Everything looks right|passes/i).first()).toBeVisible({ timeout: 20_000 })
  const publisher = await sessions.as('paul.publisher')
  await publish(publisher, ws, wf.id)
  const requester = await sessions.as('rhea.requester')
  const title = uniq('Adapter step')
  const { runUrl } = await submitPurchase(requester, ws, wf.id, { title, purpose: 'Send through the adapter', costCenter: 'OPS', amount: '150' })
  const manager = await sessions.as('mani.manager')
  await openTaskOfRun(manager, ws, /\/runs\/([^/?#]+)/.exec(runUrl)![1])
  await decideOpenTask(manager, 'approve', 'Fine')
  await requester.goto(runUrl)
  await expect(requester.getByRole('main')).toContainText(/Fulfilled|Approved|Completed/i, { timeout: 60_000 })
  await expect(requester.getByTestId('timeline')).toContainText(/Email the buyer/i)
  await expectNoSeriousViolations(requester, 'Completed request with an adapter step')

  // ---- Trigger for signed events (the shared template workflow is the target)
  const targetId = await ensureTemplatePublished(publisher, ws)
  const tkey = uniq('trg').toLowerCase().replace(/[^a-z0-9-]/g, '')
  const trigger = await apiSend<{ id: string }>(designer, 'POST', `/workspaces/${ws}/inbound-triggers`, { key: tkey, source: 'kafka', name: 'Orders topic', workflowId: targetId, secretRef: 'orders-signing',
    filter: { allowedTopics: ['orders.created'] }, mapping: { title: '/value/title', purpose: '/value/purpose', costCenter: '/value/costCenter', amount: '/value/amount' } })
  expect(trigger.status, JSON.stringify(trigger.body)).toBe(201)
  const triggerId = trigger.body.id
  const eventTitle = uniq('Event purchase')
  const payload = (title: string) => ({ topic: 'orders.created', key: 'o-1', value: { title, purpose: 'Arrived by signed event', costCenter: 'OPS', amount: 250 } })
  const body = payload(eventTitle)
  expect((await deliver(ws, triggerId, body, { secret: 'not-the-secret' })).status, 'wrong signature').toBe(401)
  expect((await deliver(ws, triggerId, body, { timestamp: Math.floor(Date.now() / 1000) - 3600 })).status, 'stale timestamp').toBe(401)
  const first = await deliver(ws, triggerId, body)
  expect(first.status, 'signed event').toBe(202)
  expect((await deliver(ws, triggerId, body, { eventId: first.eventId })).status, 'repeat is acknowledged').toBe(202)

  // Requester sees it and starts a request from it; the requester is the initiator.
  await requester.goto(`/w/${ws}/requests`)
  const panel = requester.getByRole('region', { name: 'Received events' })
  await expect(panel).toBeVisible()
  const eventRow = panel.getByTestId(`inbound-event-${first.eventId}`)
  await expect(eventRow).toContainText(eventTitle)
  await expectNoSeriousViolations(requester, 'My requests, Received events')
  await eventRow.getByRole('button', { name: `Start a request from event ${first.eventId}` }).click()
  await expect(panel.getByText('The request was started.')).toBeVisible()
  await expect(eventRow.getByRole('button', { name: /Start a request/ })).toHaveCount(0)
  await panel.getByRole('link', { name: 'Open the request' }).click()
  await expect(requester.getByRole('main')).toContainText(eventTitle)

  // ---- Edit and disable in Settings
  await designer.goto(`/w/${ws}/settings`)
  await designer.getByRole('tab', { name: /Connections/ }).click()
  const adapters = designer.getByRole('region', { name: 'Adapters and inbound triggers' })
  await adapters.getByRole('button', { name: `Edit ${mail}`, exact: true }).click()
  const editA = adapters.getByRole('group', { name: `Edit adapter ${mail}` })
  await editA.getByLabel(`Name of ${mail}`, { exact: true }).fill('Order mail (renamed)')
  await expectNoSeriousViolations(designer, 'Adapter edit form')
  await editA.getByRole('button', { name: `Save changes to ${mail}` }).click()
  await expect(adapters.getByText(new RegExp(`Adapter ${mail} saved \\(version 2`))).toBeVisible()

  await adapters.getByRole('button', { name: `Edit ${tkey}`, exact: true }).click()
  const editT = adapters.getByRole('group', { name: `Edit trigger ${tkey}` })
  await editT.getByLabel(`${tkey} accepts events`).uncheck()
  await expectNoSeriousViolations(designer, 'Trigger edit form')
  await editT.getByRole('button', { name: `Save changes to ${tkey}` }).click()
  await expect(adapters.getByText(`Trigger ${tkey} saved (version 2, disabled: senders are refused).`)).toBeVisible()
  expect((await deliver(ws, triggerId, payload(uniq('Refused')))).status, 'disabled trigger').toBe(404)
  await adapters.getByRole('button', { name: `Edit ${tkey}`, exact: true }).click()
  await adapters.getByRole('group', { name: `Edit trigger ${tkey}` }).getByLabel(`${tkey} accepts events`).check()
  await adapters.getByRole('button', { name: `Save changes to ${tkey}` }).click()
  await expect(adapters.getByText(`Trigger ${tkey} saved (version 3).`)).toBeVisible()
  const again = await deliver(ws, triggerId, payload(uniq('Second event')))
  expect(again.status, 'enabled again').toBe(202)

  // ---- Purge: age the unstarted event, then delete it from the screen.
  psql(`UPDATE orqix.inbound_event SET received_at = now() - interval '100 days' WHERE trigger_id = '${triggerId}' AND event_id = '${again.eventId}'`)
  await adapters.getByLabel('Delete events older than (days)').fill('30')
  await adapters.getByLabel('Also delete events nobody started').check()
  await adapters.getByRole('button', { name: 'Delete old events' }).click()
  await expect(adapters.getByText('1 received event older than 30 days deleted.')).toBeVisible()
  expect(psql(`SELECT count(*) FROM orqix.inbound_event WHERE trigger_id = '${triggerId}'`)).toBe('1')
  const listed = await apiRead<{ items: Array<{ eventId: string }> }>(requester, `/workspaces/${ws}/inbound-events`)
  expect(listed.status).toBe(200)
  await expectNoSeriousViolations(designer, 'Adapters panel after purge')
})
