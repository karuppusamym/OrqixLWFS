import { expect, test } from '../support/fixtures'
import { apiGet, decideOpenTask, workspaceId } from '../support/app'
import { apiSend, openTaskOfRun } from '../support/api'
import { MOCK } from '../support/env'
import { expectNoSeriousViolations } from '../support/axe'
import { analyseNativeArchive, convertNative, nativeKey, publishOnPage, startRun, waitForLifecycle } from '../support/pega-native'

/**
 * Native Pega conversion, round 3 (docs/build/S117), on the purchase-connected archive:
 * - the Order flow's Connect-REST connector becomes a call step on the procurement connection, chosen explicitly;
 *   the request runs against the procurement stub after manager approval;
 * - the case type's other process flows are converted and published;
 * - the case type becomes a CMMN case definition (preview, then create) whose first workflow task runs the Order workflow.
 * Real backend only.
 */
test.skip(MOCK, 'The mock backend rejects Pega conversion by design (docs/build/S112).')
test.setTimeout(480_000)

type Runs = { items: Array<{ id: string; lifecycle: string }> }

test('purchase-connected archive: connector call step runs, case type becomes a case definition that runs', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  const paul = await sessions.as('paul.publisher')
  const rhea = await sessions.as('rhea.requester')
  const mani = await sessions.as('mani.manager')
  // Listing a workflow's runs needs run.read on the workspace: the requester only sees their own runs.
  const olga = await sessions.as('olga.operator')

  // 1. The Order flow: the connector is shown with its source endpoint; the connection and operation are explicit.
  await analyseNativeArchive(dana, ws, 'purchase-connected.zip')
  await dana.getByLabel('Starting point to convert').selectOption('flows/Order.xml')
  await expect(dana.getByText(/connector CreateOrder, POST https:\/\/procurement.example.invalid\/orders/)).toBeVisible()
  await dana.getByLabel('Connection for Place the order').selectOption('procurement')
  await dana.getByLabel('Operation for Place the order').selectOption('createOrder')
  await expectNoSeriousViolations(dana, 'native Pega call step mapping')
  const orderKey = nativeKey('pega-order')
  const orderId = await convertNative(dana, {
    key: orderKey,
    approve: { 'Review the order (Order, work queue Managers)': 'orderApprove' },
    groups: { Managers: 'Managers' },
  })
  const draft = await apiGet<{ definition: { nodes: Array<{ id: string; type: string; config: Record<string, unknown> }> } }>(dana, `/workspaces/${ws}/workflows/${orderId}/draft`)
  expect(draft.definition.nodes.find(n => n.id === 'PlaceOrder')).toMatchObject({ type: 'http', config: { connectionRef: 'procurement', operation: 'createOrder' } })
  await publishOnPage(paul, ws, orderId)
  const run = await startRun(rhea, ws, orderId, { title: 'Laptops', amount: 1200, costCenter: 'IT' })
  await openTaskOfRun(mani, ws, run)
  await decideOpenTask(mani, 'approve')
  expect(await waitForLifecycle(rhea, ws, run, ['completed', 'needsAttention'], 120_000)).toBe('completed')

  // 2. The other process flows of the case type, each converted from the archive and published.
  const processes: Record<string, string> = { Order: orderKey }
  const others: Array<[string, string, Parameters<typeof convertNative>[1]]> = [
    ['Escalate', 'flows/Escalate.xml', { key: nativeKey('pega-escalate'), approve: { 'Finance check (Escalate, work queue Finance)': 'financeOk' }, groups: { Finance: 'Finance approvers' } }],
    ['Close', 'flows/Close.xml', { key: nativeKey('pega-close'), approve: { 'Wrap up (Close, work queue Managers)': 'wrapOk' }, groups: { Managers: 'Managers' } }],
    ['Withdraw', 'flows/Withdraw.xml', { key: nativeKey('pega-withdraw') }],
  ]
  for (const [flow, rule, options] of others) {
    await analyseNativeArchive(dana, ws, 'purchase-connected.zip')
    await dana.getByLabel('Starting point to convert').selectOption(rule)
    await publishOnPage(paul, ws, await convertNative(dana, options))
    processes[flow] = options.key
  }

  // 3. The case type: preview, then create the case definition through the case definition API.
  const migration = await analyseNativeArchive(dana, ws, 'purchase-connected.zip')
  await dana.getByLabel('Starting point to convert').selectOption('casetypes/ConnectedPurchase.xml')
  const panel = dana.getByRole('region', { name: 'Convert to a case definition' })
  await expect(panel.getByText('Intake: Escalate (optional)')).toBeVisible()
  const caseKey = nativeKey('connected')
  await panel.getByLabel('Case key').fill(caseKey)
  for (const [flow, key] of Object.entries(processes)) await panel.getByLabel(`Published workflow for ${flow}`).fill(key)
  await panel.getByLabel('Group that plans Escalate').selectOption({ label: 'Finance approvers' })
  await panel.getByLabel('Group that moves the case to Withdrawn').selectOption({ label: 'Managers' })
  await panel.getByRole('checkbox', { name: /I reviewed the case definition findings/ }).check()
  await panel.getByRole('button', { name: 'Preview case definition' }).click()
  await expect(panel.getByText(/passed the case designer's checks/)).toBeVisible({ timeout: 30_000 })
  await expect(panel.getByText(/Workflow tasks: t_intake_Order/)).toBeVisible()
  await expectNoSeriousViolations(dana, 'native Pega case definition preview')
  await panel.getByRole('button', { name: 'Create case definition' }).click()
  await expect(panel.getByText('Case definition created')).toBeVisible({ timeout: 30_000 })
  const archive = await apiGet<{ caseConversion: { caseDefinitionId: string; caseKey: string } | null }>(dana, `/workspaces/${ws}/migrations/${migration}/pega-rules`)
  expect(archive.caseConversion?.caseKey).toBe(caseKey)
  const definitionId = archive.caseConversion!.caseDefinitionId

  // 4. A case starts; its first workflow task runs the Order workflow, which the manager approves.
  const started = await apiSend<{ id: string }>(rhea, 'POST', `/workspaces/${ws}/cases/definitions/${definitionId}/instances`,
    { data: { title: 'Monitors', amount: 300, costCenter: 'OPS' } })
  expect(started.status).toBe(201)
  let caseRun = ''
  await expect.poll(async () => {
    const runs = await apiGet<Runs>(olga, `/workspaces/${ws}/runs?workflowId=${orderId}`)
    caseRun = runs.items.find(r => r.id !== run)?.id ?? ''
    return caseRun
  }, { timeout: 60_000, intervals: [1_000] }).not.toBe('')
  await openTaskOfRun(mani, ws, caseRun)
  await decideOpenTask(mani, 'approve')
  expect(await waitForLifecycle(olga, ws, caseRun, ['completed', 'needsAttention'], 120_000)).toBe('completed')
  await expect.poll(async () => {
    const plan = await apiGet<{ items: Array<{ elementId: string; state: string }> }>(rhea, `/workspaces/${ws}/cases/instances/${started.body.id}/plan`)
    // Plan items carry the CMMN plan item id; t_intake_Order is the task definition it refers to.
    return plan.items.find(i => i.elementId === 't_intake_Order_item')?.state
  }, { timeout: 90_000, intervals: [2_000] }).toBe('completed')
})
