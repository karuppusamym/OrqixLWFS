import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, decideOpenTask, openTask, selectStep, submitPurchase, workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

test.skip(!MOCK, 'Mock browser acceptance only; backend storage, scanning and stage scheduling have separate integration tests')
const purchase = JSON.parse(readFileSync(fileURLToPath(new URL('../../../examples/purchase-approval.json', import.meta.url)), 'utf8'))

async function write(page: Page, method: string, route: string, body: unknown, headers: Record<string, string> = {}) {
  return page.evaluate(async ({ method, route, body, headers }) => {
    const response = await fetch('/api/v1' + route, { method, headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), ...headers }, body: JSON.stringify(body) })
    return { status: response.status, body: await response.json() }
  }, { method, route, body, headers })
}

async function draft(page: Page, ws: string, key: string, attachment = false) {
  const created = await write(page, 'POST', `/workspaces/${ws}/workflows`, { key, name: 'Purchase approval' })
  expect(created.status).toBe(201)
  const definition = structuredClone(purchase)
  definition.workflowKey = key
  if (attachment) {
    definition.inputSchema.properties.supplierQuote = { type: 'string', title: 'Supplier quote', format: 'attachment', acceptTypes: ['txt'], maxFileBytes: 1024 }
    definition.inputSchema.required.push('supplierQuote')
    definition.inputUi.order.push('supplierQuote')
  }
  const saved = await write(page, 'PUT', `/workspaces/${ws}/workflows/${created.body.id}/draft`, { definition }, { 'If-Match': '"r1"' })
  expect(saved.status).toBe(200)
  return created.body.id as string
}

test('stage authoring survives save and overdue notices do not reroute approval', async ({ sessions }) => {
  await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const id = await draft(designer, ws, 'stage-browser')
  await designer.goto(`/w/${ws}/workflows/${id}/edit`)
  await designer.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(designer, 'start')
  await designer.getByRole('button', { name: 'Add a case stage' }).click()
  let first = designer.getByRole('group', { name: 'Stage 1: Stage 1', exact: true })
  await first.getByLabel(/^Stage name/).fill('Initial review')
  await first.getByLabel(/^Stage name/).press('Enter')
  first = designer.getByRole('group', { name: 'Stage 1: Initial review', exact: true })
  await first.getByLabel('Stage time limit').fill('P1D')
  await first.getByLabel('Stage time limit').press('Enter')
  await first.getByRole('checkbox', { name: 'Review request', exact: true }).check()
  await designer.getByRole('button', { name: 'Add a case stage' }).click()
  let second = designer.getByRole('group', { name: 'Stage 2: Stage 2', exact: true })
  await second.getByLabel(/^Stage name/).fill('Finance review')
  await second.getByLabel(/^Stage name/).press('Enter')
  second = designer.getByRole('group', { name: 'Stage 2: Finance review', exact: true })
  await second.getByRole('checkbox', { name: 'Finance review', exact: true }).check()
  await expectNoSeriousViolations(designer, 'Case stage authoring')
  await designer.keyboard.press('Control+s')
  await expect(designer.getByTestId('save-state')).toHaveText(/Saved/)
  await designer.reload()
  await designer.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(designer, 'start')
  await expect(designer.getByRole('group', { name: 'Stage 1: Initial review', exact: true }).getByLabel('Stage time limit')).toHaveValue('P1D')
  const saved = await apiGet<{ revision: number; definition: { stages: Array<{ id: string }> } }>(designer, `/workspaces/${ws}/workflows/${id}/draft`)
  const publisher = await sessions.as('paul.publisher')
  expect((await write(publisher, 'POST', `/workspaces/${ws}/workflows/${id}/publish`, { draftRevision: saved.revision, acknowledgeWarnings: true })).status).toBe(201)
  const requester = await sessions.as('rhea.requester')
  const submitted = await submitPurchase(requester, ws, id, { title: 'Staged laptop', purpose: 'Stage acceptance', costCenter: 'OPS', amount: '7500' })
  await requester.goto(submitted.runUrl)
  await expect(requester.getByTestId('stage-sentence')).toContainText('Initial review')
  await sessions.mockCall('expireStage', submitted.runUrl.split('/').at(-1)!, saved.definition.stages[0]!.id)
  await requester.reload()
  await expect(requester.getByTestId('stage-progress')).toContainText(/overdue/i)
  const manager = await sessions.as('mani.manager')
  await openTask(manager, ws, 'Staged laptop')
  await expect(manager.getByRole('complementary', { name: 'Task details' }).getByTestId('task-stage')).toContainText('Initial review')
  await decideOpenTask(manager, 'approve')
  await sessions.as('rhea.requester')
  await requester.goto(submitted.runUrl)
  await expect(requester.getByTestId('stage-sentence')).toContainText('Finance review')
  await expectNoSeriousViolations(requester, 'Case stage progress')
})

test('required file question rejects excess size, attaches to the run and stays private from metadata readers', async ({ sessions }) => {
  await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const id = await draft(designer, ws, 'file-browser', true)
  const publisher = await sessions.as('paul.publisher')
  expect((await write(publisher, 'POST', `/workspaces/${ws}/workflows/${id}/publish`, { draftRevision: 2, acknowledgeWarnings: true })).status).toBe(201)
  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/start/${id}`)
  await requester.getByLabel('What are you buying?').fill('Quote laptop')
  await requester.getByLabel('Business purpose').fill('File acceptance')
  await requester.getByLabel('Cost center').selectOption('OPS')
  await requester.getByLabel('Amount (USD)').fill('1800')
  const file = requester.getByLabel('Supplier quote', { exact: false })
  await file.setInputFiles({ name: 'large.txt', mimeType: 'text/plain', buffer: Buffer.alloc(1025, 65) })
  await expect(requester.getByText(/This file is .*The largest allowed is/)).toBeVisible()
  await file.setInputFiles({ name: 'quote.txt', mimeType: 'text/plain', buffer: Buffer.from('Supplier quote: 1800 USD') })
  await expect(requester.getByTestId('file-chosen-supplierQuote')).toContainText('quote.txt')
  await expectNoSeriousViolations(requester, 'File question')
  await requester.getByRole('button', { name: 'Review request' }).click()
  await requester.getByRole('button', { name: 'Submit request' }).click()
  const runUrl = await requester.getByRole('link', { name: 'View your request' }).getAttribute('href')
  await requester.goto(runUrl!)
  await expect(requester.getByTestId('run-files')).toContainText('quote.txt')
  const download = requester.waitForEvent('download')
  await requester.getByRole('button', { name: 'Download quote.txt' }).click()
  expect((await download).suggestedFilename()).toBe('quote.txt')
  await expectNoSeriousViolations(requester, 'Request files')
  const viewer = await sessions.as('vic.viewer')
  await viewer.goto(runUrl!)
  await expect(viewer.getByTestId('run-files')).toHaveCount(0)
  await expect(viewer.getByText('quote.txt', { exact: true })).toHaveCount(0)
})

