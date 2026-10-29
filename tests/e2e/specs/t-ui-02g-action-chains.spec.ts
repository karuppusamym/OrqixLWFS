import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, workspaceId } from '../support/app'
import { apiSend, createWorkflow, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'
import { approvalView, createView, publishView, routePage, slugOf } from '../support/views'

/**
 * UI02g (S111) composed actions on a task page: an operator who is not an approver is stopped at the claim and nothing after
 * it runs; a reject without a reason stops after the claim with the navigation skipped (the claim is not undone); the
 * eligible manager then approves and the chain's navigation step runs.
 */
test('action chains stop at the first refused step and complete when every step is authorized', async ({ sessions }) => {
  test.setTimeout(240_000)
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const workflow = await createWorkflow(page, ws, uniq('Chain journey'))
  const reviewId = await createView(page, ws, uniq('Chain review'), approvalView())
  page = await sessions.as('paul.publisher')
  await publish(page, ws, workflow.id)
  expect(await publishView(page, ws, reviewId, 1)).toBe(1)
  page = await sessions.as('dana.designer')
  const tasks = `/w/${ws}/tasks`
  const pageId = await createView(page, ws, uniq('Chain desk'), { schemaVersion: 1, kind: 'page', title: 'Decision desk', context: 'task', layout: 'single', breakpoint: 'md',
    regions: [{ id: 'main', label: 'Main', components: [
      { id: 'facts', type: 'details', label: 'Request facts', source: 'task', fields: [{ field: 'task.requestTitle', label: 'Request' }, { field: 'task.workflowName', label: 'Workflow' }] },
      { id: 'decision', type: 'view', view: { id: reviewId, version: 1 } },
      { id: 'bar', type: 'actions', label: 'Decide', chains: ['approveNow', 'rejectNow', 'approveHeld'] },
    ] }],
    chains: [
      { id: 'approveNow', label: 'Claim and approve', stopOnFailure: true, steps: [{ action: 'claim', component: 'decision' }, { action: 'approve', component: 'decision' }, { action: 'navigate', target: tasks }] },
      { id: 'rejectNow', label: 'Claim and reject', stopOnFailure: true, steps: [{ action: 'claim', component: 'decision' }, { action: 'reject', component: 'decision' }, { action: 'navigate', target: tasks }] },
      { id: 'approveHeld', label: 'Approve held task', stopOnFailure: true, steps: [{ action: 'approve', component: 'decision' }, { action: 'navigate', target: tasks }] },
    ] })
  page = await sessions.as('paul.publisher')
  expect(await publishView(page, ws, pageId, 1)).toBe(1)
  const slug = slugOf(uniq('decide'))
  await routePage(page, ws, slug, 0, pageId, 1)

  page = await sessions.as('rhea.requester')
  const title = uniq('Chain request')
  const started = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/runs`, { workflowId: workflow.id, input: { title, purpose: 'Chain acceptance', costCenter: 'OPS', amount: 80 } })
  expect(started.status).toBe(201)
  const run = await apiGet<{ tasks: Array<{ id: string; nodeId: string }> }>(page, `/workspaces/${ws}/runs/${started.body.id}`)
  const taskId = run.tasks.find((task) => task.nodeId === 'manager')?.id
  expect(taskId).toBeTruthy()
  const outcome = (p: typeof page, label: string) => p.getByRole('status').filter({ hasText: `${label}:` })

  // The operator may read the task but is not one of its approvers: the claim is refused and nothing after it runs.
  page = await sessions.as('olga.operator')
  await page.goto(`/w/${ws}/apps/${slug}/${taskId}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Decision desk' })).toBeVisible()
  await page.getByRole('button', { name: 'Claim and approve' }).click()
  await expect(outcome(page, 'Claim and approve')).toContainText('Claim and approve: stopped')
  await expect(outcome(page, 'Claim and approve').getByRole('listitem')).toHaveText([/Claim task\s*failed/, /Approve\s*skipped/, /Go to page\s*skipped/])

  // The manager rejects without a reason: the claim succeeds, the decision is refused, the navigation is skipped.
  page = await sessions.as('mani.manager')
  await page.goto(`/w/${ws}/apps/${slug}/${taskId}`)
  await expect(page.getByRole('region', { name: 'Request facts' })).toContainText(title)
  await expectNoSeriousViolations(page, 'Task page with chains')
  await page.getByRole('button', { name: 'Claim and reject' }).click()
  await expect(outcome(page, 'Claim and reject')).toContainText('Claim and reject: stopped')
  await expect(outcome(page, 'Claim and reject').getByRole('listitem')).toHaveText([/Claim task\s*succeeded/, /Reject\s*failed/, /Go to page\s*skipped/])
  await expect(page).toHaveURL(new RegExp(`/apps/${slug}/${taskId}$`))

  // The claim stands, so the manager approves the held task; every step succeeds and the navigation runs.
  await page.getByLabel('Comment (optional)').fill('Approved from the decision desk')
  await page.getByRole('button', { name: 'Approve held task' }).click()
  await expect(page).toHaveURL(new RegExp(`${tasks}$`))
  if (!MOCK) {
    const task = await apiGet<{ status: string }>(page, `/workspaces/${ws}/tasks/${taskId}`)
    expect(task.status).toBe('completed')
  }
})
