import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, workspaceId } from '../support/app'
import { apiSend, createWorkflow, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * IN02 (docs/build/S47): a connection manager defines an adapter and sends a test message that only reaches the local
 * stub, and defines an inbound trigger. Fields are validated before saving; a requester sees no adapter controls.
 */
test('connection manager adds a Kafka adapter, sends a test message to the stub and defines a trigger', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  await createWorkflow(designer, ws, uniq('Trigger target'))
  // connection.manage is grant-only (docs/build/S6). On the real stack the administrator grants it to the designer, who
  // can also read workflows to choose the trigger's target; the mock persona wade.admin already holds it.
  let admin = designer
  if (!MOCK) {
    const me = await apiGet<{ subject: { id: string } }>(designer, '/me')
    const administrator = await sessions.as('wade.admin')
    sessions.allowHttpErrors() // the grant already exists on a rerun (409)
    const granted = await apiSend(administrator, 'POST', `/workspaces/${ws}/access/grants`, { permission: 'connection.manage', principal: { type: 'subject', value: me.subject.id } })
    expect([201, 409], `grant: ${JSON.stringify(granted.body)}`).toContain(granted.status)
    admin = designer
  } else {
    admin = await sessions.as('wade.admin')
  }
  await admin.goto(`/w/${ws}/settings`)
  await admin.getByRole('tab', { name: /Connections/ }).click()
  const panel = admin.getByRole('region', { name: 'Adapters and inbound triggers' })
  await expect(panel).toBeVisible()
  await expect(panel.getByText(/delivers only to a local test stub/)).toBeVisible()

  const key = uniq('ev').toLowerCase().replace(/[^a-z0-9-]/g, '')
  await panel.getByLabel('Adapter type').selectOption('kafka')
  await expect(panel.getByRole('button', { name: 'Save adapter' })).toBeDisabled()
  await panel.getByLabel('Adapter key').fill(key)
  await panel.getByLabel('Adapter name').fill('Order events')
  await panel.getByLabel('Secret reference name', { exact: true }).fill('kafka-orders')
  await panel.getByLabel('Approved topics to send to').fill('orders.created, orders.updated')
  await expectNoSeriousViolations(admin, 'Adapters panel, add form')
  await panel.getByRole('button', { name: 'Save adapter' }).click()
  await expect(panel.getByText(`Adapter ${key} saved`)).toBeVisible()
  await expect(panel.getByText(`(${key}, `)).toBeVisible()

  await panel.getByRole('button', { name: `Prepare test message for ${key}` }).click()
  const message = panel.getByLabel(`Test message for ${key}`)
  await message.fill('{ not json')
  await panel.getByRole('button', { name: 'Send to local stub' }).click()
  await expect(panel.getByRole('alert')).toBeVisible()
  await message.fill(JSON.stringify({ topic: 'orders.created', key: 'o-1', value: { id: 1 } }))
  await panel.getByRole('button', { name: 'Send to local stub' }).click()
  await expect(panel.getByText(/accepted by the local stub \(stubbed\)/)).toBeVisible()
  await expectNoSeriousViolations(admin, 'Adapters panel after test message')

  // Inbound trigger: needs workflow.read to choose the target, which the mock integration administrator lacks.
  if (!MOCK) {
    const workflow = panel.getByLabel('Workflow to start')
    await expect(workflow.locator('option')).not.toHaveCount(1)
    const tkey = uniq('tr').toLowerCase().replace(/[^a-z0-9-]/g, '')
    await panel.getByLabel('Trigger key').fill(tkey)
    await panel.getByLabel('Trigger name').fill('Order created')
    await workflow.selectOption({ index: 1 })
    await panel.getByLabel('Signing secret reference name').fill('kafka-signing')
    await panel.getByLabel('Event source').selectOption('kafka')
    await panel.getByLabel('Approved topics to receive from').fill('orders.created')
    await panel.getByLabel(/Start form fields/).fill('title=/title')
    await panel.getByRole('button', { name: 'Save trigger' }).click()
    await expect(panel.getByText(`Trigger ${tkey} saved`)).toBeVisible()
    await expect(panel.getByText(/Senders post signed events to/).first()).toBeVisible()
    await expectNoSeriousViolations(admin, 'Adapters panel with trigger')

  }

  // A requester has no settings sections at all, so no adapter controls.
  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/settings`)
  await expect(requester.getByRole('button', { name: 'Save adapter' })).toHaveCount(0)
})
