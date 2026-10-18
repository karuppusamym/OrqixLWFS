import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { decideOpenTask, openTask, selectStep, submitPurchase, workspaceId } from '../support/app'
import { createWorkflow, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * EX06 (docs/build/S51): a designer asks for two approvers on the manager step in the inspector; the draft saves and
 * checks clean. After publication a request opens two tasks; the same person cannot decide both (separation of duties),
 * and the request moves on only after a second, different manager approves. Real stack only: the mock engine does not
 * understand the new setting (S51 "Not covered").
 */
test.skip(MOCK, 'Mock validation and simulation do not understand several approvers (docs/build/S51).')

test('two approvers are set in the studio and two different managers must approve', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const wf = await createWorkflow(designer, ws, 'Purchase approval')
  await designer.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await designer.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(designer, 'manager')
  const field = designer.getByLabel(/^Number of approvers/)
  await field.fill('2')
  await field.blur()
  await expect(field).toHaveValue('2')
  await expectNoSeriousViolations(designer, 'Approval inspector with two approvers')
  await expect(designer.getByText(/^Saved/).first()).toBeVisible({ timeout: 20_000 })
  await designer.getByRole('button', { name: 'Check workflow' }).click()
  await expect(designer.getByText(/no problems|ready to publish|Everything looks right|passes/i).first()).toBeVisible({ timeout: 20_000 })

  const publisher = await sessions.as('paul.publisher')
  await publish(publisher, ws, wf.id)

  const requester = await sessions.as('rhea.requester')
  const title = uniq('Two-approver purchase')
  const { runUrl } = await submitPurchase(requester, ws, wf.id, { title, purpose: 'Needs two managers', costCenter: 'OPS', amount: '1200' })

  const first = await sessions.as('mani.manager')
  await openTask(first, ws, title)
  await decideOpenTask(first, 'approve', 'First approval')
  // The same person is not offered the second task.
  await first.goto(`/w/${ws}/tasks?view=claimable`)
  await expect(first.getByRole('link', { name: title, exact: false })).toHaveCount(0)

  // Request still waits for the other manager.
  await requester.goto(runUrl)
  await expect(requester.getByTestId('timeline')).not.toContainText(/Fulfilled|completed/i)

  const second = await sessions.as('max.manager')
  await openTask(second, ws, title)
  await decideOpenTask(second, 'approve', 'Second approval')
  await requester.goto(runUrl)
  await expect(requester.getByRole('main')).toContainText(/Fulfilled|Approved|Completed/i, { timeout: 60_000 })
  await expectNoSeriousViolations(requester, 'Completed two-approver request')
})
