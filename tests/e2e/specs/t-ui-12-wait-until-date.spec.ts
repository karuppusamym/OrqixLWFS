import { readFileSync } from 'node:fs'
import path from 'node:path'
import type { Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, selectStep, workspaceId } from '../support/app'

/**
 * Wait until a date (S12 section 10): a Date question on the request form, a wait step that ends on that date at a
 * UTC time, the plain-language preview, and a request that waits until its date. Mock mode only: the real stack is
 * driven by tools/api-e2e scenario 18. The mock API shortens timers to a few seconds; a date in the past ends the
 * wait at once.
 */

const SAMPLE = JSON.parse(readFileSync(path.resolve(process.cwd(), '../../examples/start-date-onboarding.json'), 'utf8'))

async function call(page: Page, method: 'POST' | 'PUT', url: string, body: unknown, headers: Record<string, string> = {}): Promise<{ status: number; body: any }> {
  return page.evaluate(async ({ method, url, body, headers }) => {
    const response = await fetch('/api/v1' + url, {
      method,
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), ...headers },
      body: JSON.stringify(body),
    })
    return { status: response.status, body: await response.json().catch(() => null) }
  }, { method, url, body, headers })
}

/** Creates a workflow holding the sample definition as its draft; returns its id. */
async function createDateWorkflow(designer: Page, ws: string, key: string): Promise<string> {
  const created = await call(designer, 'POST', `/workspaces/${ws}/workflows`, { key, name: 'Start date demo' })
  expect(created.status, JSON.stringify(created.body)).toBe(201)
  const draft = await apiGet<{ revision: number }>(designer, `/workspaces/${ws}/workflows/${created.body.id}/draft`)
  const saved = await call(designer, 'PUT', `/workspaces/${ws}/workflows/${created.body.id}/draft`, { definition: { ...SAMPLE, workflowKey: key } }, { 'If-Match': `"r${draft.revision}"` })
  expect(saved.status, JSON.stringify(saved.body)).toBe(200)
  return created.body.id
}

function isoDay(offsetDays: number): string {
  return new Date(Date.now() + offsetDays * 86_400_000).toISOString().slice(0, 10)
}

test.describe('wait until a date (mock API)', () => {
  test.skip(!MOCK, 'Mock API only; scenario 18 of tools/api-e2e covers the real stack')

  test('studio: Date question, wait inspector with a UTC preview, canvas card, and business-language checks', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const id = await createDateWorkflow(designer, ws, 'start-date-studio')
    await designer.goto(`/w/${ws}/workflows/${id}/edit`)
    await expect(designer.getByTestId('studio')).toBeVisible()

    // The canvas card reads in plain language.
    const card = designer.locator('.react-flow__node[data-id="untilStart"]')
    await expect(card).toBeVisible()
    await expect(card).toContainText('Until Start date, 09:00 UTC')

    // The wait inspector: until a date, the date question, a UTC time and the preview.
    await designer.getByRole('tab', { name: 'Steps' }).click()
    await selectStep(designer, 'untilStart')
    await expect(designer.getByRole('radio', { name: 'Until a date' })).toBeChecked()
    await expect(designer.getByLabel(/^Date to wait until/)).toHaveValue('/input/startDate')
    await expect(designer.getByTestId('date-wait-preview')).toHaveText('Waits until the date in "Start date", at 09:00 UTC.')
    await designer.getByLabel(/^Try a date/).fill('2026-10-14')
    await expect(designer.getByTestId('date-wait-preview')).toHaveText('Waits until 14 Oct 2026, 09:00 UTC.')
    const time = designer.getByLabel(/^Time of day/)
    await time.fill('17:30')
    await time.press('Enter')
    await expect(designer.getByTestId('date-wait-preview')).toHaveText('Waits until 14 Oct 2026, 17:30 UTC.')
    await expect(designer.getByTestId('step-row-untilStart')).toContainText('Until Start date, 17:30 UTC')

    // A duration is still available.
    await designer.getByRole('radio', { name: 'For a duration' }).check()
    await expect(designer.getByLabel('Wait duration')).toHaveValue('PT1H')
    await expect(designer.getByTestId('duration-preview')).toHaveText('Waits 1 hour.')
    await designer.getByRole('radio', { name: 'Until a date' }).check()
    await expect(designer.getByLabel(/^Date to wait until/)).toHaveValue('/input/startDate')

    // The request form editor offers the Date type; making the date optional is explained in business language.
    await selectStep(designer, 'start')
    const card2 = designer.getByTestId('field-card-startDate')
    await expect(card2.getByLabel('Type')).toHaveValue('date')
    await card2.getByRole('checkbox', { name: 'Required' }).uncheck()
    await designer.getByRole('button', { name: 'Check workflow' }).click()
    await expect(designer.getByTestId('check-panel')).toContainText('that field is optional on the request form')
    await card2.getByRole('checkbox', { name: 'Required' }).check()
    await card2.getByLabel('Type').selectOption('text')
    await designer.getByRole('button', { name: 'Check workflow' }).click()
    await expect(designer.getByTestId('check-panel')).toContainText('not a date')
  })

  test('a request with a past date does not wait; a future date shows when it ends in UTC; too far ahead is refused', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const id = await createDateWorkflow(designer, ws, 'start-date-run')
    const published = await call(await sessions.as('paul.publisher'), 'POST', `/workspaces/${ws}/workflows/${id}/publish`, { draftRevision: 2, acknowledgeWarnings: true })
    expect(published.status, JSON.stringify(published.body)).toBe(201)

    // The form shows a labelled native date input.
    let requester = await sessions.as('rhea.requester')
    await requester.goto(`/w/${ws}/start/${id}`)
    const date = requester.getByLabel(/^Start date/)
    await expect(date).toHaveAttribute('type', 'date')
    await requester.getByLabel(/^New colleague/).fill('Ada Lovelace')

    // Too far ahead: the server refuses it on the field.
    await date.fill(isoDay(400))
    await requester.getByRole('button', { name: 'Review request' }).click()
    await requester.getByRole('button', { name: 'Submit request' }).click()
    await expect(requester.getByText(/Choose a date no later than/).first()).toBeVisible()

    // A date a few days ahead: submitted; the manager confirms; the request waits until 09:00 UTC on that day.
    const future = isoDay(5)
    await date.fill(future)
    await requester.getByRole('button', { name: 'Review request' }).click()
    await expect(requester.getByRole('heading', { level: 1, name: 'Check your request' })).toBeVisible()
    await requester.getByRole('button', { name: 'Submit request' }).click()
    await expect(requester.getByRole('heading', { level: 1, name: 'Request submitted' })).toBeVisible()
    const runUrl = (await requester.getByRole('link', { name: 'View your request' }).getAttribute('href')) ?? ''

    const manager = await sessions.as('mani.manager')
    const tasks = await apiGet<{ items: Array<{ id: string; label: string; version: number }> }>(manager, `/workspaces/${ws}/tasks?view=claimable`)
    const task = tasks.items.find((t) => t.label === 'Confirm the start')!
    expect((await call(manager, 'POST', `/workspaces/${ws}/tasks/${task.id}/claim`, { expectedVersion: task.version })).status).toBe(200)
    expect((await call(manager, 'POST', `/workspaces/${ws}/tasks/${task.id}/decision`, { expectedVersion: task.version + 1, action: 'approve' })).status).toBe(201)

    requester = await sessions.as('rhea.requester')
    await requester.goto(runUrl)
    const [year, month, day] = future.split('-').map(Number) as [number, number, number]
    const month3 = new Date(Date.UTC(year, month - 1, day)).toLocaleString('en-GB', { month: 'short', timeZone: 'UTC' })
    await expect(requester.getByTestId('run-status')).toContainText(`Waiting until ${day} ${month3} ${year}, 09:00 UTC`)

    // Mock timers are short: the wait ends and the request finishes.
    await expect(requester.getByTestId('run-status')).toContainText('First day reached', { timeout: 30_000 })
  })

  test('a date in the past ends the wait at once', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const id = await createDateWorkflow(designer, ws, 'start-date-past')
    expect((await call(await sessions.as('paul.publisher'), 'POST', `/workspaces/${ws}/workflows/${id}/publish`, { draftRevision: 2, acknowledgeWarnings: true })).status).toBe(201)
    const requester = await sessions.as('rhea.requester')
    const started = await call(requester, 'POST', `/workspaces/${ws}/runs`, { workflowId: id, input: { employeeName: 'Grace Hopper', startDate: isoDay(-10) } })
    expect(started.status, JSON.stringify(started.body)).toBe(201)
    const manager = await sessions.as('mani.manager')
    const tasks = await apiGet<{ items: Array<{ id: string; label: string; version: number }> }>(manager, `/workspaces/${ws}/tasks?view=claimable`)
    const task = tasks.items.find((t) => t.label === 'Confirm the start')!
    await call(manager, 'POST', `/workspaces/${ws}/tasks/${task.id}/claim`, { expectedVersion: task.version })
    await call(manager, 'POST', `/workspaces/${ws}/tasks/${task.id}/decision`, { expectedVersion: task.version + 1, action: 'approve' })
    const page = await sessions.as('rhea.requester')
    await page.goto(`/w/${ws}/runs/${started.body.id}`)
    await expect(async () => {
      await page.reload()
      await expect(page.getByTestId('run-status')).toContainText('First day reached', { timeout: 2_000 })
    }).toPass({ timeout: 15_000 })
  })
})
