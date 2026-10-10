import type { Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, selectStep, workflowIdByKey, workspaceId } from '../support/app'

/**
 * T-UI-11 (S12, Demo 4): build and view the workflow patterns in the studio, then watch a request that runs in
 * parallel, waits, and starts a child request. Mock mode only: the real stack is driven by tools/api-e2e scenario 15.
 * The mock API shortens timers to a few seconds; everything else follows the same rules as the server.
 */

async function send(page: Page, method: 'POST' | 'PUT', path: string, body: unknown): Promise<{ status: number; body: any }> {
  return page.evaluate(async ({ method, path, body }) => {
    const response = await fetch('/api/v1' + path, {
      method,
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(body),
    })
    return { status: response.status, body: await response.json().catch(() => null) }
  }, { method, path, body })
}

test.describe('workflow patterns (mock API)', () => {
  test.skip(!MOCK, 'Mock API only; scenario 15 of tools/api-e2e covers the real stack')

  test('palette, wait inspector with plain-language preview, and the onboarding template on the canvas', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)

    // Workflows -> New workflow -> template.
    await designer.goto(`/w/${ws}/workflows`)
    await designer.getByRole('button', { name: /new workflow/i }).first().click()
    await expect(designer.getByText('starts workflows: Purchase approval')).toBeVisible()
    await designer.getByLabel(/New hire onboarding/).check()
    await designer.getByLabel(/^Name/).fill('Onboarding demo')
    await designer.getByRole('button', { name: 'Create workflow' }).click()
    await expect(designer.getByTestId('studio')).toBeVisible()

    // The canvas shows both branches, the join, the wait and the child step.
    for (const id of ['setup', 'it', 'itEscalation', 'facilities', 'setupDone', 'leadTime', 'orderEquipment']) {
      await expect(designer.locator(`.react-flow__node[data-id="${id}"]`)).toBeVisible()
    }

    // The palette offers the three new steps.
    for (const name of ['Run in parallel', 'Wait', 'Start another workflow']) {
      await expect(designer.getByRole('button', { name: new RegExp(`^${name}`) }).first()).toBeVisible()
    }

    // Wait inspector: duration with a reading in plain language.
    await designer.getByRole('tab', { name: 'Steps' }).click()
    await selectStep(designer, 'leadTime')
    await expect(designer.getByTestId('duration-preview')).toHaveText('Waits 1 minute.')
    const duration = designer.getByLabel('Wait duration')
    await duration.fill('P2D')
    await duration.press('Enter')
    await expect(designer.getByTestId('duration-preview')).toHaveText('Waits 2 days.')
    await duration.fill('soon')
    await duration.press('Enter')
    await expect(designer.getByTestId('duration-preview')).toContainText('Use days, hours and minutes')

    // The approval time limit reads the same way.
    await selectStep(designer, 'it')
    await expect(designer.getByTestId('duration-preview')).toHaveText('Allows 4 hours.')

    // The child step shows the pinned workflow and its request values.
    await selectStep(designer, 'orderEquipment')
    await expect(designer.getByLabel('Published workflow')).toHaveValue('purchase-approval')
  })

  test('a request runs in parallel, waits, starts a child and continues with its result', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    // Mock mode shares one browser context: as() switches the signed-in user, so ask for the page before each action.
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const created = await send(designer, 'POST', `/workspaces/${ws}/workflows`, { key: 'onboarding-demo', name: 'New hire onboarding', templateId: 'new-hire-onboarding' })
    expect(created.status).toBe(201)
    const published = await send(await sessions.as('paul.publisher'), 'POST', `/workspaces/${ws}/workflows/${created.body.id}/publish`, { draftRevision: 1, acknowledgeWarnings: true })
    expect(published.status, JSON.stringify(published.body)).toBe(201)

    let requester = await sessions.as('rhea.requester')
    const started = await send(requester, 'POST', `/workspaces/${ws}/runs`, {
      workflowId: created.body.id,
      input: { employeeName: 'Ada Lovelace', role: 'Engineer', department: 'IT', equipment: 'Laptop and dock', equipmentBudget: 1800, justification: 'New starter' },
    })
    expect(started.status, JSON.stringify(started.body)).toBe(201)
    const runUrl = `/w/${ws}/runs/${started.body.id}`

    // Two approvals are open at the same time, for different groups.
    await requester.goto(runUrl)
    await expect(requester.getByTestId('run-status')).toContainText('IT accounts and access')
    await expect(requester.getByTestId('run-status')).toContainText('Desk and building pass')

    // Both groups decide; the request now waits on its timer.
    const approve = async (user: string, label: string) => {
      const page = await sessions.as(user)
      const tasks = await apiGet<{ items: Array<{ id: string; label: string; version: number }> }>(page, `/workspaces/${ws}/tasks?view=claimable`)
      const task = tasks.items.find((t) => t.label === label)!
      expect((await send(page, 'POST', `/workspaces/${ws}/tasks/${task.id}/claim`, { expectedVersion: task.version })).status).toBe(200)
      expect((await send(page, 'POST', `/workspaces/${ws}/tasks/${task.id}/decision`, { expectedVersion: task.version + 1, action: 'approve' })).status).toBe(201)
    }
    await approve('mani.manager', 'IT accounts and access')
    await approve('fin.finance', 'Desk and building pass')
    requester = await sessions.as('rhea.requester')
    await requester.goto(runUrl)
    await expect(requester.getByTestId('run-status')).toContainText('Allow lead time before ordering')

    // The timer ends, the purchase starts as its own request with a link back.
    const childSection = requester.getByTestId('child-runs')
    await expect(async () => {
      await requester.reload()
      await expect(childSection).toBeVisible({ timeout: 2_000 })
    }).toPass({ timeout: 30_000 })
    await expect(childSection).toContainText('Purchase approval ORQ-')
    await requester.getByTestId('child-runs').getByRole('link').first().click()
    await expect(requester.getByTestId('parent-run-link')).toBeVisible()

    // Approving the purchase finishes the child and the parent continues with its result.
    await approve('mani.manager', 'Review request')
    requester = await sessions.as('rhea.requester')
    await requester.goto(runUrl)
    await expect(requester.getByTestId('run-status')).toContainText('Ready')
    await expect(requester.getByText(/finished: Fulfilled/)).toBeVisible()
    await expect(requester.getByText(/Started Purchase approval ORQ-/)).toBeVisible()
    await expect(workflowIdByKey(requester, ws, 'onboarding-demo')).resolves.toBe(created.body.id)
  })
})
