import { expect, type Page } from '@playwright/test'

/** Same-origin API read from inside the page (works with the session cookie, and with the in-page mock API). */
export async function apiGet<T>(page: Page, path: string): Promise<T> {
  const result = await page.evaluate(async (p) => {
    const response = await fetch(`/api/v1${p}`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
    return { status: response.status, body: (await response.json().catch(() => null)) as unknown }
  }, path)
  if (result.status >= 400) throw new Error(`GET ${path} -> ${result.status}`)
  return result.body as T
}

/** Workspace id by its key (the dev seed has `purchasing`, `purchasing-sandbox` and `facilities`). */
export async function workspaceId(page: Page, key = 'purchasing'): Promise<string> {
  const me = await apiGet<{ workspaces: Array<{ id: string; key: string }> }>(page, '/me')
  const ws = me.workspaces.find((w) => w.key === key)
  if (!ws) throw new Error(`Workspace ${key} not found for the signed-in user`)
  return ws.id
}

/** Workflow id by its key (the template workflow is `purchase-approval`). */
export async function workflowIdByKey(page: Page, ws: string, key = 'purchase-approval'): Promise<string> {
  let cursor: string | null = null
  const seen = new Set<string>()
  type WorkflowList = { items: Array<{ id: string; key: string }>; nextCursor?: string | null }
  do {
    const list: WorkflowList = await apiGet<WorkflowList>(page,
      `/workspaces/${ws}/workflows${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`)
    const wf = list.items.find((w) => w.key === key)
    if (wf) return wf.id
    cursor = list.nextCursor ?? null
    if (cursor && seen.has(cursor)) throw new Error('Workflow listing returned a repeated cursor')
    if (cursor) seen.add(cursor)
  } while (cursor)
  throw new Error(`Workflow ${key} not found`)
}

/** Select a step in the studio's Steps tab by its step id (keyboard accessible buttons). */
export async function selectStep(page: Page, stepId: string): Promise<void> {
  await page.getByTestId(`step-row-${stepId}`).locator('button[aria-pressed]').first().click()
  await expect(page.getByTestId('inspector')).toBeVisible()
}

export interface PurchaseInput {
  title: string
  purpose: string
  costCenter: 'OPS' | 'IT' | 'HR'
  /** Typed exactly as a person would type it. */
  amount: string
}

/** Fill the request form, check the summary, submit, and return the request id shown on the confirmation page. */
export async function submitPurchase(page: Page, ws: string, workflowId: string, input: PurchaseInput): Promise<{ requestId: string; runUrl: string }> {
  await page.goto(`/w/${ws}/start/${workflowId}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Purchase approval' })).toBeVisible()
  await page.getByLabel('What are you buying?').fill(input.title)
  await page.getByLabel('Business purpose').fill(input.purpose)
  await page.getByLabel('Cost center').selectOption(input.costCenter)
  await page.getByLabel('Amount (USD)').fill(input.amount)
  await page.getByRole('button', { name: 'Review request' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Check your request' })).toBeVisible()
  await page.getByRole('button', { name: 'Submit request' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Request submitted' })).toBeVisible()
  const requestId = (await page.getByTestId('request-id').innerText()).trim()
  const href = await page.getByRole('link', { name: 'View your request' }).getAttribute('href')
  return { requestId, runUrl: href ?? '' }
}

/** Open a task from "Available to claim" or "Assigned to me" by the request title it shows. */
export async function openTask(page: Page, ws: string, title: string, view: 'claimable' | 'assigned' = 'claimable'): Promise<void> {
  await page.goto(`/w/${ws}/tasks?view=${view}&sort=newest`)
  await page.getByRole('link', { name: title, exact: false }).first().click()
  await expect(page.getByTestId('task-actions')).toBeVisible()
}

/** Claim (when needed) and decide the task that is open in the task panel. */
export async function decideOpenTask(page: Page, action: 'approve' | 'reject', text?: string): Promise<void> {
  const claim = page.getByRole('button', { name: 'Claim task' })
  if (await claim.isVisible()) await claim.click()
  const comment = page.getByLabel('Comment')
  await expect(comment).toBeVisible()
  if (text) await comment.fill(text)
  await page.getByRole('button', { name: action === 'approve' ? 'Approve' : 'Reject', exact: true }).click()
  await expect(page.getByTestId('task-banner')).toContainText(action === 'approve' ? 'You approved' : 'You rejected')
}

/** Text of the run timeline, for assertions on business-language events. */
export async function timelineText(page: Page): Promise<string> {
  return page.getByTestId('timeline').innerText()
}

/** Fresh template per editing test, so persisted demo edits cannot affect reruns. */
export async function createPurchaseWorkflow(page: Page, ws: string): Promise<string> {
  return page.evaluate(async (workspace) => {
    const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=')
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (token) headers['X-XSRF-TOKEN'] = decodeURIComponent(token)
    const response = await fetch('/api/v1/workspaces/' + workspace + '/workflows', {
      method: 'POST', headers, body: JSON.stringify({ key: 'ui-' + crypto.randomUUID().slice(0, 8),
        name: 'Purchase approval', templateId: 'purchase-approval' }),
    })
    if (response.status !== 201) throw new Error('Create isolated workflow: ' + response.status)
    return (await response.json() as { id: string }).id
  }, ws)
}

/**
 * Real stack: publish the shared template workflow once if it has no active release yet. The demo path works on its own
 * copy, so specs that start requests on `purchase-approval` call this first with a publisher page. Mock mode needs no
 * call (its reset publishes the template); returns the workflow id either way.
 */
export async function ensureTemplatePublished(publisher: Page, ws: string, key = 'purchase-approval'): Promise<string> {
  const id = await workflowIdByKey(publisher, ws, key)
  const workflow = await apiGet<{ activeRelease: { id: string } | null; draftRevision: number }>(publisher, `/workspaces/${ws}/workflows/${id}`)
  if (workflow.activeRelease) return id
  await publisher.evaluate(async ({ ws, id, revision }) => {
    const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=')
    const headers: Record<string, string> = { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() }
    if (token) headers['X-XSRF-TOKEN'] = decodeURIComponent(token)
    const response = await fetch('/api/v1/workspaces/' + ws + '/workflows/' + id + '/publish', {
      method: 'POST', headers, body: JSON.stringify({ draftRevision: revision, acknowledgeWarnings: true }),
    })
    if (response.status !== 201) throw new Error('Publish template workflow: ' + response.status)
  }, { ws, id, revision: workflow.draftRevision })
  return id
}
