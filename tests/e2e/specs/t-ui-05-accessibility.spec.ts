import type { Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { expectNoSeriousViolations } from '../support/axe'
import { MOCK } from '../support/env'
import { apiRead } from '../support/api'
import { apiGet, ensureTemplatePublished, openQuestion, selectStep, submitPurchase, workflowIdByKey, workspaceId } from '../support/app'

/**
 * T-UI-05: axe (WCAG 2.2 A and AA rules) on every route and on the main dialogs and inspector variants.
 * Serious and critical findings fail the test. Each scan runs on a settled page (heading visible, data loaded).
 */
test.describe.configure({ mode: 'serial' })

async function settled(page: Page): Promise<void> {
  await expect(page.locator('main h1, h1').first()).toBeVisible()
  await expect(page.locator('[role="status"]:has-text("Loading")')).toHaveCount(0, { timeout: 20_000 })
}

async function scan(page: Page, label: string): Promise<void> {
  await settled(page)
  await expectNoSeriousViolations(page, label)
}

test('requester screens', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: true })
  const page = await sessions.as('rhea.requester')
  const ws = await workspaceId(page)
  if (!MOCK) await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
  const workflowId = await workflowIdByKey(page, ws)

  await scan(page, 'home')
  await page.goto(`/w/${ws}/start/${workflowId}`)
  await scan(page, 'request form')
  // Error summary state.
  await page.getByRole('button', { name: 'Review request' }).click()
  await expect(page.getByTestId('error-summary')).toBeVisible()
  await scan(page, 'request form with errors')
  await page.getByLabel('What are you buying?').fill('Accessibility check item')
  await page.getByLabel('Business purpose').fill('Scanning every screen')
  await page.getByLabel('Cost center').selectOption('IT')
  await page.getByLabel('Amount (USD)').fill('99.50')
  await page.getByRole('button', { name: 'Review request' }).click()
  await scan(page, 'request review')
  await page.getByRole('button', { name: 'Submit request' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Request submitted' })).toBeVisible()
  await scan(page, 'request submitted')

  await page.goto(`/w/${ws}/requests`)
  await scan(page, 'my requests')
  await page.getByRole('link', { name: /^ORQ-\d+$|^[A-F0-9]{8}$/ }).first().click()
  await expect(page.getByTestId('timeline')).toBeVisible()
  await scan(page, 'run detail (requester)')
  await page.goto('/not-found')
  await scan(page, 'not found')
  await page.goto('/forbidden')
  await scan(page, 'forbidden')
})

test('approver screens', async ({ sessions }) => {
  const page = await sessions.as('mani.manager')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/tasks?view=claimable&sort=newest`)
  await scan(page, 'tasks: available to claim')
  if (MOCK) {
    await page.getByRole('link', { name: /Accessibility check item|Replacement laptop/ }).first().click()
  } else {
    // The claimable list is paged and a long-lived shared database holds many open tasks, so a title search on the first page
    // can miss the task. Find one through the API (preferring the seeded titles) and open it directly.
    type TaskPage = { items: Array<{ id: string; label?: string; workflowName?: string }>; nextCursor?: string | null }
    let taskId: string | undefined
    let fallback: string | undefined
    let cursor: string | null = null
    do {
      const r: { status: number; body: TaskPage } = await apiRead<TaskPage>(page, `/workspaces/${ws}/tasks?view=claimable&limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
      for (const t of r.body?.items ?? []) {
        fallback ??= t.id
        if (/Accessibility check item|Replacement laptop/.test(`${t.label ?? ''} ${t.workflowName ?? ''}`)) { taskId = t.id; break }
      }
      cursor = taskId ? null : (r.body?.nextCursor ?? null)
    } while (cursor)
    const target = taskId ?? fallback
    if (!target) throw new Error('No claimable task for the approver')
    await page.goto(`/w/${ws}/tasks/${target}`)
  }
  await expect(page.getByTestId('task-actions')).toBeVisible()
  await scan(page, 'task panel (unclaimed)')
  await page.getByRole('button', { name: 'Claim task' }).click()
  await expect(page.getByLabel('Comment')).toBeVisible()
  await scan(page, 'task panel (claimed)')
  await page.getByRole('button', { name: 'Reject', exact: true }).click()
  await expect(page.getByText('Add a reason so the requester knows what to change')).toBeVisible()
  await scan(page, 'task panel (reason error)')
  await page.goto(`/w/${ws}/tasks?view=completed`)
  await scan(page, 'tasks: completed')
})

test('operator screens', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('setProcurementFault', 'unknown')
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  if (!MOCK) await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
  const workflowId = await workflowIdByKey(requester, ws)
  let attentionRun: string | undefined
  if (MOCK) {
    const { runUrl } = await submitPurchase(requester, ws, workflowId, { title: 'Axe attention item', purpose: 'Operator scan', costCenter: 'OPS', amount: '75' })
    const manager = await sessions.as('mani.manager')
    await manager.goto(`/w/${ws}/tasks?view=claimable&sort=newest`)
    await manager.getByRole('link', { name: 'Axe attention item' }).click()
    await manager.getByRole('button', { name: 'Claim task' }).click()
    await manager.getByRole('button', { name: 'Approve', exact: true }).click()
    await expect(manager.getByTestId('task-banner')).toContainText('You approved')
    attentionRun = runUrl
    await sessions.mockCall('setProcurementFault', 'none')
  }

  const page = await sessions.as('olga.operator')
  await page.goto(`/w/${ws}/runs`)
  await scan(page, 'runs')
  await page.getByRole('button', { name: 'Needs attention' }).click()
  await scan(page, 'runs: needs attention')
  if (attentionRun) {
    await page.goto(attentionRun)
    await expect(page.getByTestId('attention-message')).toBeVisible()
    await scan(page, 'run detail (operator, needs attention)')
    await page.getByRole('button', { name: 'Reconcile outcome' }).click()
    await expect(page.getByRole('dialog', { name: 'Reconcile the outcome' })).toBeVisible()
    await scan(page, 'reconcile dialog')
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Cancel request' }).click()
    await expect(page.getByRole('dialog', { name: 'Cancel this request' })).toBeVisible()
    await scan(page, 'cancel dialog')
    await page.keyboard.press('Escape')
  } else {
    const runs = await apiGet<{ items: Array<{ id: string }> }>(page, `/workspaces/${ws}/runs`)
    if (runs.items[0]) {
      await page.goto(`/w/${ws}/runs/${runs.items[0].id}`)
      await scan(page, 'run detail (operator)')
    }
  }
})

test('designer and publisher screens', async ({ sessions }) => {
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  if (!MOCK) await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
  const workflowId = await workflowIdByKey(page, ws)

  await page.goto(`/w/${ws}/workflows`)
  await scan(page, 'workflows')
  await page.getByRole('button', { name: 'New workflow' }).click()
  await expect(page.getByRole('dialog', { name: 'New workflow' })).toBeVisible()
  await scan(page, 'new workflow dialog')
  await page.keyboard.press('Escape')
  await page.goto(`/w/${ws}/workflows/${workflowId}`)
  await scan(page, 'workflow overview')

  await page.goto(`/w/${ws}/workflows/${workflowId}/edit`)
  await expect(page.getByTestId('studio')).toBeVisible()
  await expect(page.locator('.react-flow__node').first()).toBeVisible()
  await scan(page, 'studio: canvas')
  await page.getByRole('tab', { name: 'Steps' }).click()
  await scan(page, 'studio: steps')

  // Every kind of inspector.
  for (const [step, label] of [
    ['start', 'request form'],
    ['manager', 'approval'],
    ['amountPolicy', 'decision'],
    ['route', 'choice'],
    ['procure', 'call a system'],
    ['notify', 'notification'],
    ['fulfilled', 'finish'],
  ] as const) {
    await selectStep(page, step)
    await scan(page, `studio: inspector (${label})`)
  }

  await page.getByRole('button', { name: 'Check workflow' }).click()
  await expect(page.getByTestId('check-panel')).toBeVisible()
  await expect(page.getByTestId('check-summary')).not.toContainText('Checking')
  await scan(page, 'studio: check panel')
  // The More menu's preview opens the Form tab, where the live preview sits beside the questions (S120).
  await page.getByRole('button', { name: 'More actions' }).click()
  await page.getByRole('menuitem', { name: 'Preview the request form' }).click()
  await expect(page.getByTestId('form-preview')).toBeVisible()
  await openQuestion(page, 'amount')
  await scan(page, 'studio: form tab')
  await page.getByRole('tab', { name: 'Steps' }).click()
  await page.getByRole('button', { name: /Add step after Review request/ }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await scan(page, 'studio: add step dialog')
  await page.keyboard.press('Escape')

  const publisher = await sessions.as('paul.publisher')
  await publisher.goto(`/w/${ws}/workflows/${workflowId}/publish?revision=1`)
  await expect(publisher.getByText('What changed')).toBeVisible()
  await scan(publisher, 'publish review')
})

test('settings and workspace without access', async ({ sessions }) => {
  const admin = await sessions.as('wade.admin')
  const ws = await workspaceId(admin)
  await admin.goto(`/w/${ws}/settings`)
  await scan(admin, 'settings: members and groups')
  await admin.getByRole('tab', { name: /Connections/ }).click()
  await expect(admin.getByRole('heading', { name: 'Procurement service' })).toBeVisible()
  await scan(admin, 'settings: connections')

  const outsider = await sessions.as('otto.outsider')
  await scan(outsider, 'no workspace')
})
