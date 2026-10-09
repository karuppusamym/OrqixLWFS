import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { decideOpenTask, openTask, selectStep, submitPurchase, timelineText, createPurchaseWorkflow, workflowIdByKey, workspaceId } from '../support/app'

/**
 * T-UI-02: the demo script of docs/06-delivery-plan.md, every step that does not need OpenShift, through the browser.
 *
 *  1. designer opens Purchase approval and inspects its form and rule
 *  2. validate, publish v1 (publisher), submit USD 1,800 (requester)
 *  3. manager claims and approves; the order is created and the request is Fulfilled
 *  5. USD 5,000 goes to finance; a rejection ends without a procurement call
 *  6. publish v2 while a v1 task waits; the waiting request stays on v1
 *  7. an unauthorized cross-workspace action is denied
 *  8. (mock mode) an unknown external outcome is reconciled by an operator
 *
 * Step 4 ("show the real procurement reference") uses the reference shown in the timeline; whether it comes from the
 * stub or from an approved test endpoint is decided by how the stack under test is configured.
 *
 * Mock mode shares one page between all users, so each user's page is requested right before it is used.
 */
test.describe.configure({ mode: 'serial' })

test('demo path: publish, submit, approve, finance, version pinning, denial', async ({ sessions }) => {
  test.setTimeout(300_000)
  if (MOCK) await sessions.mockCall('reset', { published: false, sampleRuns: false })

  // 1. Designer inspects the form and the rule ----------------------------------------------------------------------
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const workflowId = await createPurchaseWorkflow(designer, ws)
  await designer.goto(`/w/${ws}/workflows/${workflowId}`)
  await expect(designer.getByRole('heading', { level: 1, name: 'Purchase approval' })).toBeVisible()
  await designer.getByRole('link', { name: /Edit draft/ }).first().click()
  await expect(designer.getByTestId('studio')).toBeVisible()
  await designer.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(designer, 'start')
  await expect(designer.getByTestId('field-card-amount')).toBeVisible()
  await selectStep(designer, 'amountPolicy')
  await expect(designer.locator('[data-field="rows.0.when.value"]')).toHaveValue('5000')

  // 2. Validate (no blockers) ---------------------------------------------------------------------------------------
  await designer.getByRole('button', { name: 'Check workflow' }).click()
  await expect(designer.getByTestId('check-summary')).toContainText(/No problems found|0 blockers/)

  // ... and publish v1 as the publisher.
  const publisher = await sessions.as('paul.publisher')
  await publisher.goto(`/w/${ws}/workflows/${workflowId}`)
  await publisher.getByRole('link', { name: /Review and publish/ }).click()
  await expect(publisher.getByTestId('check-clean')).toBeVisible()
  await publisher.getByRole('button', { name: /Publish version \d+/ }).click()
  await expect(publisher.getByTestId('publish-success')).toContainText(/Version \d+ published\. Existing requests continue on their original version\./)

  // 2b. Requester submits USD 1,800 -----------------------------------------------------------------------------------
  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/start/${workflowId}`)
  await requester.getByLabel('What are you buying?').fill('Demo laptop')
  await requester.getByLabel('Business purpose').fill('Replace a failed device')
  await requester.getByLabel('Cost center').selectOption('OPS')
  await requester.getByLabel('Amount (USD)').fill('1800')
  await requester.getByRole('button', { name: 'Review request' }).click()
  await expect(requester.getByRole('heading', { level: 1, name: 'Check your request' })).toBeVisible()
  await expect(requester.getByLabel('Request summary')).toContainText('$1,800.00')
  await requester.getByRole('button', { name: 'Submit request' }).click()
  await expect(requester.getByRole('heading', { level: 1, name: 'Request submitted' })).toBeVisible()
  await expect(requester.getByText(/Waiting for Managers/)).toBeVisible()
  const standardRun = (await requester.getByRole('link', { name: 'View your request' }).getAttribute('href')) ?? ''

  // 3. Manager claims and approves; the request completes Fulfilled with a procurement reference ----------------------
  const manager = await sessions.as('mani.manager')
  await openTask(manager, ws, 'Demo laptop')
  await decideOpenTask(manager, 'approve', 'Approved for the demo')

  const requesterAgain = await sessions.as('rhea.requester')
  await requesterAgain.goto(standardRun)
  await expect(requesterAgain.getByTestId('run-status')).toContainText('Completed · Fulfilled', { timeout: 90_000 })
  await expect(requesterAgain.getByTestId('timeline')).toContainText('Finished: Fulfilled', { timeout: 15_000 })
  const timeline = await timelineText(requesterAgain)
  expect(timeline).toMatch(/approved Review request/)
  expect(timeline).toMatch(/completed \(reference \S+\)/)
  expect(timeline).toMatch(/Finished: Fulfilled/)

  // 5. USD 5,000 goes to finance; the finance rejection ends the request without a procurement call ------------------
  const big = await submitPurchase(await sessions.as('rhea.requester'), ws, workflowId, { title: 'Demo standing desks', purpose: 'Six desks for operations', costCenter: 'OPS', amount: '5000' })
  const manager2 = await sessions.as('mani.manager')
  await openTask(manager2, ws, 'Demo standing desks')
  await decideOpenTask(manager2, 'approve')
  const finance = await sessions.as('fin.finance')
  await openTask(finance, ws, 'Demo standing desks')
  await decideOpenTask(finance, 'reject', 'Over the quarterly budget')

  const requester3 = await sessions.as('rhea.requester')
  await requester3.goto(big.runUrl)
  await expect(requester3.getByTestId('run-status')).toContainText('Completed · Rejected', { timeout: 90_000 })
  await expect(requester3.getByTestId('timeline')).toContainText('Finished: Rejected', { timeout: 15_000 })
  const rejectedTimeline = await timelineText(requester3)
  expect(rejectedTimeline).toMatch(/rejected Finance review/)
  expect(rejectedTimeline).not.toMatch(/completed \(reference/)
  await expect(requester3.getByText('Reason: Over the quarterly budget')).toBeVisible()

  // 6. Publish v2 while a v1 task waits: the waiting request keeps its original version -----------------------------------
  const waiting = await submitPurchase(await sessions.as('rhea.requester'), ws, workflowId, { title: 'Demo monitor', purpose: 'Second screen', costCenter: 'IT', amount: '320' })
  const requester4 = await sessions.as('rhea.requester')
  await requester4.goto(waiting.runUrl)
  await expect(requester4.getByText(/Version 1 /)).toBeVisible()
  const publisher2 = await sessions.as('paul.publisher')
  await publisher2.goto(`/w/${ws}/workflows/${workflowId}/publish?revision=1`)
  await expect(publisher2.getByTestId('check-clean')).toBeVisible()
  await publisher2.getByRole('button', { name: /Publish version \d+/ }).click()
  await expect(publisher2.getByTestId('publish-success')).toBeVisible()
  const requester5 = await sessions.as('rhea.requester')
  await requester5.goto(waiting.runUrl)
  await expect(requester5.getByText(/Version 1 /)).toBeVisible()
  const manager3 = await sessions.as('mani.manager')
  await openTask(manager3, ws, 'Demo monitor')
  await decideOpenTask(manager3, 'approve')
  const requester6 = await sessions.as('rhea.requester')
  await requester6.goto(waiting.runUrl)
  await expect(requester6.getByTestId('run-status')).toContainText('Completed · Fulfilled', { timeout: 90_000 })

  // 7. An unauthorized cross-workspace action is denied -----------------------------------------------------------------------
  sessions.allowHttpErrors()
  const outsider = await sessions.as('fred.facilities')
  await outsider.goto(`/w/${ws}`)
  await expect(outsider.getByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible()
  await outsider.goto(`/w/${ws}/runs/${waiting.runUrl.split('/').pop()}`)
  await expect(outsider.getByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible()
})

test('operator reconciles an unknown external outcome (mock mode)', async ({ sessions }) => {
  test.skip(!MOCK, 'Needs a fault-injectable procurement endpoint; the real stack uses the stub faults of S9 T-EFF-04..07.')
  test.setTimeout(180_000)
  await sessions.mockCall('reset', { published: true, sampleRuns: false })
  await sessions.mockCall('setProcurementFault', 'unknown')

  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  const workflowId = await workflowIdByKey(requester, ws)
  const { runUrl } = await submitPurchase(requester, ws, workflowId, { title: 'Demo flaky order', purpose: 'Recovery drill', costCenter: 'HR', amount: '400' })
  const manager = await sessions.as('mani.manager')
  await openTask(manager, ws, 'Demo flaky order')
  await decideOpenTask(manager, 'approve')

  // The requester sees a calm, honest message.
  const requester2 = await sessions.as('rhea.requester')
  await requester2.goto(runUrl)
  await expect(requester2.getByTestId('run-status')).toContainText('Needs attention')
  await expect(requester2.getByText(/An operator has been alerted/).first()).toBeVisible()

  // The operator never sees a plain "Failed": the outcome is unknown and the next allowed action is reconcile.
  await sessions.mockCall('setProcurementFault', 'none')
  const operator = await sessions.as('olga.operator')
  await operator.goto(`/w/${ws}/runs`)
  await operator.getByRole('button', { name: 'Needs attention' }).click()
  await operator.getByRole('link', { name: /ORQ-|[A-F0-9]{8}/ }).first().click()
  await expect(operator.getByTestId('attention-message')).toContainText('Procurement service may have created the order. Check it before retrying.')
  await expect(operator.getByRole('button', { name: 'Retry step' })).toBeDisabled()
  await operator.getByRole('button', { name: 'Reconcile outcome' }).click()
  const dialog = operator.getByRole('dialog', { name: 'Reconcile the outcome' })
  await dialog.getByLabel('Evidence').fill('Checked the procurement system: order PO-9000 exists.')
  await dialog.getByLabel('Reference in the other system').fill('PO-9000')
  await dialog.getByLabel('procurementId').fill('PO-9000')
  await dialog.getByRole('button', { name: 'Record outcome' }).click()
  await expect(operator.getByTestId('run-status')).toContainText('Completed · Fulfilled')
  await expect(operator.getByTestId('timeline')).toContainText('confirmed the outcome of Create purchase order')
})
