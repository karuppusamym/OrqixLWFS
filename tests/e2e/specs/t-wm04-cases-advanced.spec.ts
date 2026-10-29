import { readFile } from 'node:fs/promises'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, ensureTemplatePublished, workspaceId } from '../support/app'
import { apiSend, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

const fixture = (name: string) => readFile(new URL(`../../../backend/app/src/test/resources/cases/${name}.cmmn`, import.meta.url), 'utf8')
const key = (prefix: string) => uniq(prefix).toLowerCase().replace(/[^a-z0-9-]/g, '').slice(0, 40)

test('canvas authoring with a discretionary item that a manager plans into a running case', async ({ sessions }) => {
  test.skip(MOCK, 'Verifies the generated planning table against the real case engine.')
  test.setTimeout(240_000)
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page), caseKey = key('planned')
  await page.goto(`/w/${ws}/cases`)
  await page.getByLabel('Key', { exact: true }).fill(caseKey)
  await page.getByLabel('Name', { exact: true }).fill(caseKey)
  await page.getByRole('button', { name: 'Build a case model', exact: true }).click()
  await expect(page.getByTestId('case-canvas')).toBeVisible()
  await expect(page.getByTestId('canvas-task1')).toBeVisible()
  await page.getByRole('button', { name: 'Add human task', exact: true }).click()
  await expect(page.getByTestId('canvas-item1')).toBeVisible()
  await expectNoSeriousViolations(page, 'Case canvas')
  await page.getByRole('button', { name: 'Form view', exact: true }).click()
  await page.getByLabel('Eligible group for task1').selectOption('managers')
  await page.getByLabel('Name for item1', { exact: true }).fill('Expert opinion')
  await page.getByLabel('Eligible group for item1').selectOption('finance-approvers')
  await page.getByLabel('Manual activation item1').uncheck()
  await page.getByLabel(/Discretionary item1/).check()
  await page.getByRole('group', { name: /Who may plan/ }).getByLabel('Managers').check()
  await page.getByRole('button', { name: 'Use generated CMMN', exact: true }).click()
  await expect(page.getByLabel('Generated CMMN preview')).toHaveValue(/<planningTable/)
  await page.getByRole('button', { name: 'Create definition', exact: true }).click()
  await expect(page.getByRole('cell', { name: caseKey, exact: true }).first()).toBeVisible()

  page = await sessions.as('rhea.requester')
  await page.goto(`/w/${ws}/cases`)
  await page.getByRole('row').filter({ has: page.getByRole('cell', { name: caseKey, exact: true }) }).getByRole('button', { name: 'Start case', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/w/${ws}/cases/[^/]+$`))
  const url = page.url()

  page = await sessions.as('mani.manager'); await page.goto(url)
  await page.getByRole('button', { name: 'Show case plan', exact: true }).click()
  await expect(page.getByText('Discretionary item').first()).toBeVisible()
  await page.getByRole('button', { name: 'Plan into case', exact: true }).click()
  await page.getByRole('dialog').getByLabel('Reason', { exact: true }).fill('Specialist view needed')
  await page.getByRole('button', { name: 'Confirm plan change', exact: true }).click()
  await expect(page.getByText('Planned', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('Discretionary item planned').first()).toBeVisible()

  page = await sessions.as('fin.finance'); await page.goto(url)
  await expect(page.getByRole('button', { name: 'Complete task', exact: true })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await expectNoSeriousViolations(page, 'Planned discretionary work on mobile')
})

test('a case task starts its child case, whose completion finishes the parent task; data changes trigger work', async ({ sessions }) => {
  test.skip(MOCK, 'Requires the real case engine and reconciler.')
  test.setTimeout(240_000)
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page), childKey = key('child'), parentKey = key('parent'), triggerKey = key('trigger')
  const child = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/cases/definitions`, { key: childKey, name: 'Supplier review', sourceXml: (await fixture('child-review')).replace('childCase', childKey),
    dataFields: [{ key: 'amount', label: 'Amount', type: 'number', required: true }] })
  expect(child.status, JSON.stringify(child.body)).toBe(201)
  const parent = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/cases/definitions`, { key: parentKey, name: 'Purchase with supplier review',
    sourceXml: (await fixture('parent-case')).replace('parentCase', parentKey).replace('CHILDKEY', childKey),
    dataFields: [{ key: 'total', label: 'Total', type: 'number' }, { key: 'verdict', label: 'Verdict', type: 'text' }],
    caseTasks: [{ taskId: 'supplierTask', inputMapping: { amount: 'total' }, resultMapping: { verdict: 'outcome' } }] })
  expect(parent.status, JSON.stringify(parent.body)).toBe(201)
  const trigger = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/cases/definitions`, { key: triggerKey, name: 'Timed and data-driven review',
    sourceXml: (await fixture('timer-and-data')).replace('triggerCase', triggerKey),
    dataFields: [{ key: 'amount', label: 'Amount', type: 'number' }, { key: 'region', label: 'Region', type: 'text' }] })
  expect(trigger.status, JSON.stringify(trigger.body)).toBe(201)

  page = await sessions.as('rhea.requester')
  const started = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/cases/definitions/${parent.body.id}/instances`, { data: { total: 1200 } })
  expect(started.status).toBe(201)
  const parentUrl = `/w/${ws}/cases/${started.body.id}`

  page = await sessions.as('olga.operator'); await page.goto(parentUrl)
  await page.getByRole('button', { name: 'Show case plan', exact: true }).click()
  await expect(page.getByText('Case task').first()).toBeVisible()
  await page.getByRole('link', { name: 'Open child case', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/w/${ws}/cases/[^/]+$`))
  const childUrl = page.url()

  page = await sessions.as('mani.manager'); await page.goto(childUrl)
  await page.getByRole('button', { name: 'Complete task', exact: true }).click()
  await expect(page.getByText('completed', { exact: true }).first()).toBeVisible()

  page = await sessions.as('olga.operator')
  await expect.poll(async () => (await apiGet<{ events: { action: string }[] }>(page, `/workspaces/${ws}/cases/instances/${started.body.id}/plan`)).events.map(e => e.action),
    { timeout: 30_000 }).toContain('childCaseCompleted')
  await page.goto(parentUrl)
  await page.getByRole('button', { name: 'Show case plan', exact: true }).click()
  await expect(page.getByText('Child case completed').first()).toBeVisible()
  const data = await apiGet<{ values: { verdict?: string } }>(page, `/workspaces/${ws}/cases/instances/${started.body.id}/data`)
  expect(data.values.verdict).toBe('Completed')
  await expectNoSeriousViolations(page, 'Parent case with a completed case task')

  page = await sessions.as('rhea.requester')
  const triggered = await apiSend<{ id: string; version: number }>(page, 'POST', `/workspaces/${ws}/cases/definitions/${trigger.body.id}/instances`, { data: { amount: 100, region: 'EU' } })
  page = await sessions.as('mani.manager')
  const changed = await apiSend(page, 'POST', `/workspaces/${ws}/cases/instances/${triggered.body.id}/data`, { expectedVersion: triggered.body.version, reason: 'Quote arrived', values: { amount: 9000, region: 'EU' } })
  expect(changed.status, JSON.stringify(changed.body)).toBe(200)
  page = await sessions.as('olga.operator')
  const plan = await apiGet<{ items: { elementId: string; state: string }[] }>(page, `/workspaces/${ws}/cases/instances/${triggered.body.id}/plan`)
  expect(plan.items.find(item => item.elementId === 'escalate')?.state).toMatch(/active|enabled/)
})

test('a workflow task starts its pinned workflow and cancelling the task cancels the run', async ({ sessions }) => {
  test.skip(MOCK, 'Requires the real runtime and case engine.')
  test.setTimeout(240_000)
  const publisher = await sessions.as('paul.publisher')
  const ws = await workspaceId(publisher)
  await ensureTemplatePublished(publisher, ws)
  const page = await sessions.as('dana.designer'), caseKey = key('wfcase')
  const created = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/cases/definitions`, { key: caseKey, name: 'Purchase case',
    sourceXml: (await fixture('workflow-task')).replace('workflowCase', caseKey).replace('WORKFLOWKEY', 'purchase-approval'),
    dataFields: [{ key: 'title', label: 'Title', type: 'text' }, { key: 'purpose', label: 'Purpose', type: 'text' }, { key: 'amount', label: 'Amount', type: 'number' },
      { key: 'costCenter', label: 'Cost center', type: 'text' }, { key: 'decision', label: 'Decision', type: 'text' }],
    workflowTasks: [{ taskId: 'purchaseTask', inputMapping: { title: 'title', purpose: 'purpose', amount: 'amount', costCenter: 'costCenter' }, resultMapping: { decision: 'outcome' } }] })
  expect(created.status, JSON.stringify(created.body)).toBe(201)
  const requester = await sessions.as('rhea.requester')
  const started = await apiSend<{ id: string }>(requester, 'POST', `/workspaces/${ws}/cases/definitions/${created.body.id}/instances`,
    { data: { title: 'Replacement laptop', purpose: 'Device failed', amount: 900, costCenter: 'OPS' } })
  expect(started.status, JSON.stringify(started.body)).toBe(201)
  const operator = await sessions.as('olga.operator')
  await operator.goto(`/w/${ws}/cases/${started.body.id}`)
  await operator.getByRole('button', { name: 'Show case plan', exact: true }).click()
  await expect(operator.getByTestId(/^workflow-/).first()).toContainText(/Workflow running: ORQ-\d+/)
  const runLink = operator.getByTestId(/^workflow-/).first().getByRole('link')
  const runHref = await runLink.getAttribute('href')
  await operator.getByRole('listitem').filter({ hasText: 'Run purchase request' }).getByRole('button', { name: 'Cancel plan item', exact: true }).click()
  await operator.getByRole('dialog').getByLabel('Reason', { exact: true }).fill('Not needed')
  await operator.getByRole('button', { name: 'Confirm plan change', exact: true }).click()
  await expect(operator.getByText(/Workflow cancelled/).first()).toBeVisible()
  const run = await apiGet<{ lifecycle: string }>(operator, `/workspaces/${ws}/runs/${runHref!.split('/').pop()}`)
  expect(run.lifecycle).toBe('cancelled')
})
