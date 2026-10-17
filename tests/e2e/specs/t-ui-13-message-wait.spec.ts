import { readFileSync } from 'node:fs'
import path from 'node:path'
import type { Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, selectStep, workspaceId } from '../support/app'

/**
 * Wait for a message (S16): the palette item, the inspector with its plain-language preview, the canvas card, and a
 * request that waits for a message, receives a test message sent from run detail, or gives up after its time limit.
 * Mock mode only: the real stack is driven by tools/api-e2e scenario 40. Mock timers are shortened to a few seconds.
 */

const SAMPLE = JSON.parse(readFileSync(path.resolve(process.cwd(), '../../examples/order-payment-wait.json'), 'utf8'))

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

/** Creates a workflow holding the order sample (optionally without its time limit) as its draft; returns its id. */
async function createWorkflow(designer: Page, ws: string, key: string, withLimit: boolean): Promise<string> {
  const created = await call(designer, 'POST', `/workspaces/${ws}/workflows`, { key, name: 'Order payment demo' })
  expect(created.status, JSON.stringify(created.body)).toBe(201)
  const draft = await apiGet<{ revision: number }>(designer, `/workspaces/${ws}/workflows/${created.body.id}/draft`)
  const definition = structuredClone(SAMPLE)
  definition.workflowKey = key
  if (!withLimit) {
    delete definition.nodes[1].config.timeoutAfter
    definition.transitions = definition.transitions.filter((t: { on: string }) => t.on !== 'timedOut')
    definition.nodes = definition.nodes.filter((n: { id: string }) => n.id !== 'notifyCancelled' && n.id !== 'cancelled')
    definition.transitions = definition.transitions.filter((t: { id: string }) => t.id !== 't5')
    delete definition.uiLayout.positions.notifyCancelled
    delete definition.uiLayout.positions.cancelled
  }
  const saved = await call(designer, 'PUT', `/workspaces/${ws}/workflows/${created.body.id}/draft`, { definition }, { 'If-Match': `"r${draft.revision}"` })
  expect(saved.status, JSON.stringify(saved.body)).toBe(200)
  return created.body.id
}

async function publish(sessions: { as(user: string): Promise<Page> }, ws: string, id: string): Promise<void> {
  const published = await call(await sessions.as('paul.publisher'), 'POST', `/workspaces/${ws}/workflows/${id}/publish`, { draftRevision: 2, acknowledgeWarnings: true })
  expect(published.status, JSON.stringify(published.body)).toBe(201)
}

async function startOrder(page: Page, ws: string, workflowId: string, orderNumber: string): Promise<string> {
  const started = await call(page, 'POST', `/workspaces/${ws}/runs`, { workflowId, input: { orderNumber, customerName: 'Acme Ltd' } })
  expect(started.status, JSON.stringify(started.body)).toBe(201)
  return started.body.id
}

test.describe('wait for a message (mock API)', () => {
  test.skip(!MOCK, 'Mock API only; scenario 40 of tools/api-e2e covers the real stack')

  test('studio: palette item, canvas card, inspector with a plain-language preview and the values a message may carry', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const id = await createWorkflow(designer, ws, 'order-payment-studio', true)
    await designer.goto(`/w/${ws}/workflows/${id}/edit`)
    await expect(designer.getByTestId('studio')).toBeVisible()
    await expect(designer.getByRole('button', { name: /^Wait for a message/ }).first()).toBeVisible()

    const card = designer.locator('.react-flow__node[data-id="waitPayment"]')
    await expect(card).toBeVisible()
    await expect(card).toContainText('payment-received · Order number')

    await designer.getByRole('tab', { name: 'Steps' }).click()
    await selectStep(designer, 'waitPayment')
    await expect(designer.getByTestId('message-wait-preview')).toHaveText("Waits until a message named payment-received arrives for this request's Order number. If nothing arrives in time, the request follows Timed out.")
    await expect(designer.getByLabel(/^Which field identifies the request/)).toHaveValue('/input/orderNumber')
    await expect(designer.getByTestId('duration-preview')).toHaveText('Waits 2 days.')
    await expect(designer.getByTestId('message-value-0')).toBeVisible()
    await expect(designer.getByTestId('message-value-1')).toBeVisible()

    // A message name must be an identifier; the problem is explained in business language.
    const name = designer.getByLabel(/^Message name/)
    await name.fill('Payment Received')
    await name.press('Enter')
    await expect(designer.getByText(/Use lowercase letters and digits joined with dashes/)).toBeVisible()
    await name.fill('payment-confirmed')
    await name.press('Enter')
    await expect(designer.getByTestId('message-wait-preview')).toContainText('named payment-confirmed')
    await expect(designer.getByTestId('step-row-waitPayment')).toContainText('payment-confirmed')

    // Clearing the time limit removes the Timed out route.
    const limit = designer.getByLabel(/^Give up after/)
    await limit.fill('')
    await limit.press('Enter')
    await expect(designer.getByTestId('message-wait-preview')).not.toContainText('Timed out')
  })

  test('a waiting request receives a test message sent from run detail and finishes', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const id = await createWorkflow(designer, ws, 'order-payment-send', false)
    await publish(sessions, ws, id)
    const requester = await sessions.as('rhea.requester')
    const runId = await startOrder(requester, ws, id, 'SO-1043')

    // People without message.send do not see the action.
    await requester.goto(`/w/${ws}/runs/${runId}`)
    await expect(requester.getByTestId('run-status')).toContainText('Waiting for message payment-received')
    await expect(requester.getByTestId('send-test-message')).toHaveCount(0)

    const operator = await sessions.as('olga.operator')
    await operator.goto(`/w/${ws}/runs/${runId}`)
    await expect(operator.getByTestId('run-status')).toContainText('Waiting for message payment-received')
    // A matching subscription supplies the schema; unmatched buffered sends cannot know it yet.
    const refused = await call(operator, 'POST', `/workspaces/${ws}/messages`, { name: 'payment-received', correlationKey: 'SO-1043', payload: { surprise: 1 } })
    expect(refused.status).toBe(422)
    await expect(operator.getByTestId('run-status')).toContainText('Waiting for message payment-received')
    await expect(operator.getByTestId('send-test-message')).toContainText('Waiting for a message')
    await operator.getByRole('button', { name: 'Send test message' }).click()
    const dialog = operator.getByRole('dialog', { name: 'Send test message' })
    await expect(dialog.getByLabel('Message name')).toHaveValue('payment-received')
    await expect(dialog.getByLabel(/^Value that identifies the request/)).toHaveValue('SO-1043')
    // The declared required value is needed.
    await dialog.getByRole('button', { name: 'Send message' }).click()
    await expect(dialog.getByRole('alert')).toBeVisible()
    await dialog.getByLabel(/^Payment reference/).fill('PAY-77')
    await dialog.getByRole('button', { name: 'Send message' }).click()
    await expect(operator.getByTestId('message-sent')).toContainText('delivered to 1 waiting request')
    await expect(operator.getByTestId('run-status')).toContainText('Order paid', { timeout: 20_000 })
    await expect(operator.getByText(/the message payment-received arrived/)).toBeVisible()
  })

  test('a message that arrives first is kept and used when the wait starts; the time limit ends a request that hears nothing', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const id = await createWorkflow(designer, ws, 'order-payment-buffer', true)
    await publish(sessions, ws, id)
    const operator = await sessions.as('olga.operator')
    const early = await call(operator, 'POST', `/workspaces/${ws}/messages`, { name: 'payment-received', correlationKey: 'SO-2001', payload: { paymentReference: 'EARLY-1' } })
    expect(early.status, JSON.stringify(early.body)).toBe(202)
    expect(early.body).toMatchObject({ status: 'buffered', matched: 0 })

    const requester = await sessions.as('rhea.requester')
    const first = await startOrder(requester, ws, id, 'SO-2001')
    await requester.goto(`/w/${ws}/runs/${first}`)
    await expect(requester.getByTestId('run-status')).toContainText('Order paid', { timeout: 20_000 })

    // Nobody sends anything for SO-2003: the (shortened) time limit ends it.
    const quiet = await startOrder(requester, ws, id, 'SO-2003')
    await requester.goto(`/w/${ws}/runs/${quiet}`)
    await expect(requester.getByTestId('run-status')).toContainText('Cancelled no payment', { timeout: 30_000 })
    await expect(requester.getByText(/did not arrive in time/)).toBeVisible()
  })
})
