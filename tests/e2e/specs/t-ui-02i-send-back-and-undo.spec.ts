import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, workspaceId } from '../support/app'
import { apiSend, createWorkflow, editDraft, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'
import { createView, publishView, routePage, slugOf } from '../support/views'

type Run = { tasks: Array<{ id: string; nodeId: string }> }

/**
 * UI02 round 3 (S116): the approver sends a request back to its requester for the field the step declares correctable,
 * the decision waits, the requester answers on the request page, and the approver then decides on the corrected data.
 * A page chain whose later step fails returns the claimed task to its group (the declared undo) and says so.
 */
test('a request goes back to its requester and a failed chain undoes its claim', async ({ sessions }) => {
  test.setTimeout(240_000)
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const workflow = await createWorkflow(page, ws, uniq('Send back'))
  await editDraft(page, ws, workflow.id, (definition) => {
    const manager = (definition.nodes as Array<{ id: string; config: Record<string, unknown> }>).find((node) => node.id === 'manager')!
    manager.config.correctable = ['purpose']
  })
  const reviewId = await createView(page, ws, uniq('Return review'), { schemaVersion: 1, kind: 'approval',
    sections: [{ id: 'summary', label: 'Request summary', columns: 1, fields: [
      { id: 'title', label: 'Item', type: 'text', binding: 'request.title', required: true },
      { id: 'amount', label: 'Amount', type: 'number', binding: 'request.amount', required: true }] }],
    actions: [{ id: 'approve', label: 'Approve', type: 'approve' }, { id: 'reject', label: 'Reject', type: 'reject' }, { id: 'fix', label: 'Return', type: 'requestCorrection' }] })
  page = await sessions.as('paul.publisher')
  await publish(page, ws, workflow.id)
  await publishView(page, ws, reviewId, 1)

  page = await sessions.as('rhea.requester')
  const title = uniq('Returned request')
  const started = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/runs`, { workflowId: workflow.id, input: { title, purpose: 'Unclear', costCenter: 'OPS', amount: 75 } })
  expect(started.status).toBe(201)
  const taskId = (await apiGet<Run>(page, `/workspaces/${ws}/runs/${started.body.id}`)).tasks.find((task) => task.nodeId === 'manager')!.id

  // The approver claims and sends it back with a reason; the decision now waits.
  page = await sessions.as('mani.manager')
  await page.goto(`/w/${ws}/tasks/${taskId}`)
  await page.getByRole('button', { name: 'Claim task', exact: true }).click()
  await page.getByRole('button', { name: 'Send back to the requester' }).click()
  await page.getByLabel('What should the requester change?').fill('Say who the item is for')
  await page.getByRole('button', { name: 'Send back', exact: true }).click()
  await expect(page.getByText('Waiting for the requester')).toBeVisible()
  await expectNoSeriousViolations(page, 'Task sent back')

  // The requester corrects the returned field on the request page.
  page = await sessions.as('rhea.requester')
  await page.goto(`/w/${ws}/runs/${started.body.id}`)
  const section = page.getByRole('region', { name: 'Sent back for corrections' })
  await expect(section).toContainText('Say who the item is for')
  await section.getByLabel('Business purpose').fill('Laptop for the new hire')
  await expectNoSeriousViolations(page, 'Send-back answer')
  await section.getByRole('button', { name: 'Send corrections' }).click()
  await expect(section).toContainText('answered')

  // The approver decides on the corrected data.
  page = await sessions.as('mani.manager')
  await page.goto(`/w/${ws}/tasks/${taskId}`)
  await expect(page.getByText('Waiting for the requester')).toHaveCount(0)
  await expect(page.getByText('Laptop for the new hire')).toBeVisible()
  const task = await apiGet<{ version: number }>(page, `/workspaces/${ws}/tasks/${taskId}`)
  expect((await apiSend(page, 'POST', `/workspaces/${ws}/tasks/${taskId}/decision`, { expectedVersion: task.version, action: 'approve' })).status).toBe(201)

  // A chain whose reject has no reason fails; the claim before it declared its undo and is returned to the group.
  page = await sessions.as('dana.designer')
  const pageId = await createView(page, ws, uniq('Undo desk'), { schemaVersion: 1, kind: 'page', title: 'Undo desk', context: 'task', layout: 'single', breakpoint: 'md',
    regions: [{ id: 'main', label: 'Main', components: [{ id: 'decision', type: 'view', view: { id: reviewId, version: 1 } }, { id: 'bar', type: 'actions', label: 'Decide', chains: ['claimThenReject'] }] }],
    chains: [{ id: 'claimThenReject', label: 'Claim and reject', stopOnFailure: true, steps: [{ action: 'claim', component: 'decision', compensate: 'releaseTask' }, { action: 'reject', component: 'decision' }] }] })
  page = await sessions.as('paul.publisher')
  await publishView(page, ws, pageId, 1)
  const slug = slugOf(uniq('undo'))
  await routePage(page, ws, slug, 0, pageId, 1)
  page = await sessions.as('rhea.requester')
  const second = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/runs`, { workflowId: workflow.id, input: { title: uniq('Undo request'), purpose: 'Undo check', costCenter: 'OPS', amount: 20 } })
  const secondTask = (await apiGet<Run>(page, `/workspaces/${ws}/runs/${second.body.id}`)).tasks.find((item) => item.nodeId === 'manager')!.id
  page = await sessions.as('mani.manager')
  await page.goto(`/w/${ws}/apps/${slug}/${secondTask}`)
  await page.getByRole('button', { name: 'Claim and reject' }).click()
  const outcome = page.getByRole('status').filter({ hasText: 'Claim and reject:' })
  await expect(outcome).toContainText('stopped')
  await expect(outcome.getByRole('listitem').first()).toContainText('Return the task to its group: undone')
  if (!MOCK) expect((await apiGet<{ assignee: unknown }>(page, `/workspaces/${ws}/tasks/${secondTask}`)).assignee).toBeNull()
})
