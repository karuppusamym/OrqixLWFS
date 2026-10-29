import { expect, test } from '../support/fixtures'
import { apiGet, decideOpenTask, workspaceId } from '../support/app'
import { openTaskOfRun } from '../support/api'
import { MOCK } from '../support/env'
import { expectNoSeriousViolations } from '../support/axe'
import { analyseNativeArchive, convertNative, nativeKey, publishOnPage, startRun, waitForLifecycle } from '../support/pega-native'

/**
 * Native Pega conversion, round 2 (docs/build/S115): a starting flow of the archive is converted and published first to
 * serve as the child workflow; then the case type converts with a circumstance-qualified decision table (Services uses a
 * 2,000 threshold instead of 5,000), a split-join over two subflows and a subflow call, all mapped to that child, a
 * business-calendar due time and overdue notices. It is published separately and run through two routes.
 * Real backend only.
 */
test.skip(MOCK, 'The mock backend rejects Pega conversion by design (docs/build/S112).')
test.setTimeout(360_000)

type Run = { lifecycle: string; outcome: string | null; children: Array<{ id: string }>; currentSteps: Array<{ nodeId?: string }> }

test('purchase-advanced archive: circumstance variant, split-join and subflows convert, publish and run', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  const paul = await sessions.as('paul.publisher')

  // 1. The child workflow: a starting flow of the same archive, converted and published on its own.
  await analyseNativeArchive(dana, ws, 'purchase-advanced.zip')
  await dana.getByLabel('Starting point to convert').selectOption('flows/RecordOrder.xml')
  const childKey = nativeKey('pega-child')
  const childId = await convertNative(dana, { key: childKey })
  await publishOnPage(paul, ws, childId)

  // 2. The case type: variants and child calls are visible before conversion.
  const migration = await analyseNativeArchive(dana, ws, 'purchase-advanced.zip')
  await dana.getByLabel('Starting point to convert').selectOption('casetypes/PurchaseOrder.xml')
  const review = dana.getByRole('region', { name: 'Native Pega rule archive' })
  await expect(review.getByText('Variant: .Category = Services')).toBeVisible()
  await expect(review.getByText(/Delegated to Purchasing:Managers/)).toBeVisible()
  const children = dana.getByRole('region', { name: 'Child workflows to map' })
  await expect(children).toContainText('Checks_1: subflow BudgetCheck in Review')
  await expect(children).toContainText('Checks_2: subflow VendorScreen in Review')
  await expect(children).toContainText('Record: subflow RecordOrder in Fulfil')
  await review.getByRole('tab', { name: 'Effective rules' }).click()
  await expect(review.getByText(/Variants: .Category = Services \(decisions\/ApprovalLevel-Services.xml\)/).first()).toBeVisible()
  await expect(review.getByText(/Variants: from 20200101T000000.000 GMT until 20201231T000000.000 GMT/).first()).toBeVisible()
  await review.getByRole('tab', { name: 'Archive inventory' }).click()
  await expect(review.getByText('Rule delegation (provenance only)')).toBeVisible()
  await expectNoSeriousViolations(dana, 'native Pega advanced review')

  const key = nativeKey('pega-order')
  const workflowId = await convertNative(dana, {
    key,
    approve: {
      'Manager review (Review, work queue Managers)': 'managerApprove',
      'Finance review (Review, work queue Finance)': 'financeApprove',
      'Confirm order (Fulfil, work queue Procurement)': 'confirm',
    },
    groups: { Managers: 'Managers', Finance: 'Finance approvers', Procurement: 'Managers' },
    children: { Checks_1: { workflowRef: childKey }, Checks_2: { workflowRef: childKey }, Record: { workflowRef: childKey } },
  })
  const job = await apiGet<{ status: string; convertedWorkflowId: string }>(dana, `/workspaces/${ws}/migrations/${migration}`)
  expect(job.convertedWorkflowId).toBe(workflowId)
  const draft = await apiGet<{ definition: { nodes: Array<{ id: string; type: string; config: Record<string, unknown> }>; decisions: Array<{ hitPolicy: string; rows: unknown[] }> } }>(
    dana, `/workspaces/${ws}/workflows/${workflowId}/draft`)
  const types = Object.fromEntries(draft.definition.nodes.map(n => [n.id, n.type]))
  expect(types).toMatchObject({ Checks: 'parallelSplit', Checks_join: 'parallelJoin', Checks_1: 'startWorkflow', Checks_2: 'startWorkflow', Record: 'startWorkflow', VendorCheck_v1: 'branch' })
  const manager = draft.definition.nodes.find(n => n.id === 'ManagerReview')!.config
  expect(manager).toMatchObject({ dueAfterHours: 16, calendar: { zone: 'America/New_York' }, escalation: { intervalHours: 4, maxNotices: 4 } })
  expect(JSON.stringify(draft.definition.decisions)).toContain('Services')

  await publishOnPage(paul, ws, workflowId)
  const rhea = await sessions.as('rhea.requester')
  const base = { description: 'Consulting', vendorName: 'Acme', vendorPreferred: true }

  // Services at 3,000: the Services variant (2,000) sends it through the parallel child checks to finance, then order
  // confirmation and the order-recording child.
  const services = await startRun(rhea, ws, workflowId, { ...base, category: 'Services', amount: 3000 })
  const fin = await sessions.as('fin.finance')
  await openTaskOfRun(fin, ws, services)
  await expect(fin.getByText('Finance review').first()).toBeVisible()
  await decideOpenTask(fin, 'approve')
  const mani = await sessions.as('mani.manager')
  await openTaskOfRun(mani, ws, services)
  await expect(mani.getByText('Confirm order').first()).toBeVisible()
  await decideOpenTask(mani, 'approve')
  expect(await waitForLifecycle(rhea, ws, services, ['completed', 'needsAttention'], 120_000)).toBe('completed')
  const servicesRun = await apiGet<Run>(rhea, `/workspaces/${ws}/runs/${services}`)
  expect(servicesRun.children.length).toBeGreaterThanOrEqual(3)
  await rhea.goto(`/w/${ws}/runs/${services}`)
  await expect(rhea.getByTestId('run-status')).toContainText(/Completed/, { timeout: 60_000 })
  await expectNoSeriousViolations(rhea, 'native Pega advanced run')

  // Hardware at 50: the base threshold sends it to manager review; the decision tree's current rule makes it Express,
  // so it skips order confirmation and only records the order.
  const express = await startRun(rhea, ws, workflowId, { ...base, category: 'Hardware', amount: 50 })
  await openTaskOfRun(mani, ws, express)
  await expect(mani.getByText('Manager review').first()).toBeVisible()
  await decideOpenTask(mani, 'approve')
  expect(await waitForLifecycle(rhea, ws, express, ['completed', 'needsAttention'], 120_000)).toBe('completed')
  expect((await apiGet<Run>(rhea, `/workspaces/${ws}/runs/${express}`)).children.length).toBe(1)
})
