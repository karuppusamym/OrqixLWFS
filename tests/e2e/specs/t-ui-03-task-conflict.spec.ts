import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { decideOpenTask, ensureTemplatePublished, openTask, submitPurchase, workflowIdByKey, workspaceId } from '../support/app'

/**
 * T-UI-03 (docs/build/S7-frontend.md section 3, conflict UX): when another approver wins, the second one sees who did
 * what, the task refreshes and the actions go away. No error page, no lost context.
 */
test.describe.configure({ mode: 'serial' })

async function taskIdOf(page: import('@playwright/test').Page): Promise<string> {
  const match = /\/tasks\/([^/?]+)/.exec(page.url())
  if (!match?.[1]) throw new Error(`No task id in ${page.url()}`)
  return match[1]
}

test('second manager sees "This task was completed by ..." after the first approved', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  if (!MOCK) await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
  const workflowId = await workflowIdByKey(requester, ws)
  await submitPurchase(requester, ws, workflowId, { title: 'Conflict laptop', purpose: 'Two managers, one task', costCenter: 'OPS', amount: '1800' })

  // Max opens the task and keeps the panel open.
  const max = await sessions.as('max.manager')
  await openTask(max, ws, 'Conflict laptop')
  await expect(max.getByRole('button', { name: 'Claim task' })).toBeVisible()
  const taskId = await taskIdOf(max)

  // Meanwhile Mani completes it.
  if (MOCK) {
    await sessions.mockCall('decideTaskAs', 'mani.manager', taskId, 'approve')
  } else {
    const mani = await sessions.as('mani.manager')
    await openTask(mani, ws, 'Conflict laptop')
    await decideOpenTask(mani, 'approve')
  }

  // Max, still looking at the stale panel, tries to claim. The server answers 409 by design, which Chromium logs.
  sessions.allowHttpErrors()
  await max.getByRole('button', { name: 'Claim task' }).click()
  const banner = max.getByTestId('task-banner')
  await expect(banner).toContainText('This task was completed by Mani Manager')
  await expect(banner).toBeFocused() // focus moves to the result message
  await expect(max.getByRole('button', { name: 'Claim task' })).toHaveCount(0)
  await expect(max.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0)
  await expect(max.getByRole('button', { name: 'Reject', exact: true })).toHaveCount(0)
  await expect(max.getByTestId('task-why-not')).toBeHidden() // the banner already says it
  // The page is still usable: the decision is in the history and the list has moved on.
  await expect(max.getByText('Mani Manager approved Review request')).toBeVisible()
})

test('second manager sees "Claimed by ..." when the first only picked it up', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  if (!MOCK) await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
  const workflowId = await workflowIdByKey(requester, ws)
  await submitPurchase(requester, ws, workflowId, { title: 'Claim race laptop', purpose: 'Claim conflict', costCenter: 'IT', amount: '900' })

  const max = await sessions.as('max.manager')
  await openTask(max, ws, 'Claim race laptop')
  const taskId = await taskIdOf(max)

  if (MOCK) {
    await sessions.mockCall('claimTaskAs', 'mani.manager', taskId)
  } else {
    const mani = await sessions.as('mani.manager')
    await openTask(mani, ws, 'Claim race laptop')
    await mani.getByRole('button', { name: 'Claim task' }).click()
    await expect(mani.getByLabel('Comment')).toBeVisible()
  }

  // The stale claim gets 409 TASK_ALREADY_CLAIMED by design, which Chromium logs.
  sessions.allowHttpErrors()
  await max.getByRole('button', { name: 'Claim task' }).click()
  await expect(max.getByTestId('task-banner')).toContainText('Claimed by Mani Manager')
  await expect(max.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0)
})

test('rejecting needs a reason, and the reason reaches the requester', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  if (!MOCK) await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
  const workflowId = await workflowIdByKey(requester, ws)
  const { runUrl } = await submitPurchase(requester, ws, workflowId, { title: 'Reject me', purpose: 'Reason required', costCenter: 'HR', amount: '250' })

  const manager = await sessions.as('mani.manager')
  await openTask(manager, ws, 'Reject me')
  await manager.getByRole('button', { name: 'Claim task' }).click()
  const comment = manager.getByLabel('Comment')
  await expect(comment).toBeFocused() // keyboard users land on the next field after claiming
  await manager.getByRole('button', { name: 'Reject', exact: true }).click()
  await expect(manager.getByText('Add a reason so the requester knows what to change')).toBeVisible()
  await expect(comment).toHaveAttribute('aria-invalid', 'true')
  await comment.fill('Please use the existing spare device.')
  await manager.getByRole('button', { name: 'Reject', exact: true }).click()
  await expect(manager.getByTestId('task-banner')).toContainText('You rejected')

  const requesterAgain = await sessions.as('rhea.requester')
  await requesterAgain.goto(runUrl)
  await expect(requesterAgain.getByTestId('run-status')).toContainText('Completed · Rejected', { timeout: 60_000 })
  await expect(requesterAgain.getByText('Reason: Please use the existing spare device.')).toBeVisible()
})

test('a requester cannot decide their own request', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  // Mani is a manager and can also request; the server hides his own request from his claimable list.
  const manager = await sessions.as('mani.manager')
  const ws = await workspaceId(manager)
  const workflowId = await workflowIdByKey(manager, ws)
  await submitPurchase(manager, ws, workflowId, { title: 'Mani own request', purpose: 'Separation of duties', costCenter: 'OPS', amount: '100' })
  await manager.goto(`/w/${ws}/tasks?view=claimable`)
  await expect(manager.getByRole('link', { name: 'Mani own request' })).toHaveCount(0)
})
