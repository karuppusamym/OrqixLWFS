import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, createPurchaseWorkflow, decideOpenTask, ensureTemplatePublished, openTask, submitPurchase, workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

test('assigned task delegates to an eligible colleague with an explicit reason and decision', async ({ sessions }) => {
  test.skip(MOCK, 'Real eligibility, ownership and persisted delegation are required')
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const workflow = await createPurchaseWorkflow(designer, ws)
  const definition = await apiGet<{ key: string }>(designer, `/workspaces/${ws}/workflows/${workflow}`)
  await ensureTemplatePublished(await sessions.as('paul.publisher'), ws, definition.key)
  // Sign in the recipient so the known-subject directory includes the current identity and groups.
  const recipient = await sessions.as('max.manager')
  const me = await apiGet<{ subject: { displayName: string } }>(recipient, '/me')
  const requester = await sessions.as('rhea.requester')
  const title = `Delegation ${Date.now()}`
  await submitPurchase(requester, ws, workflow, { title, purpose: 'Cover an assigned approval during leave.', costCenter: 'IT', amount: '10' })
  const owner = await sessions.as('mani.manager')
  await openTask(owner, ws, title)
  await owner.getByRole('button', { name: 'Claim task', exact: true }).click()
  await owner.getByRole('button', { name: 'Delegate to a colleague', exact: true }).click()
  await owner.getByLabel('Colleague', { exact: true }).selectOption({ label: me.subject.displayName })
  await expect(owner.getByRole('button', { name: 'Confirm delegation', exact: true })).toBeDisabled()
  const reason = 'Please cover this approval while I am away.'
  await owner.getByLabel('Reason for delegation').fill(reason)
  await expectNoSeriousViolations(owner, 'Task delegation')
  await owner.getByRole('button', { name: 'Confirm delegation', exact: true }).click()
  await expect(owner.getByRole('button', { name: 'Delegate to a colleague', exact: true })).toHaveCount(0)
  await owner.reload()
  await expect(owner.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0)
  await openTask(recipient, ws, title, 'assigned')
  await decideOpenTask(recipient, 'approve', 'Covered the delegated approval.')
})
