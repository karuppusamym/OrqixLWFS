import { execFileSync } from 'node:child_process'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { apiRead, apiSend, createWorkflow, editDraft, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'
import type { Page } from '@playwright/test'

/**
 * EX09 extension (docs/build/S72): the rebuild now covers a message wait and an external-worker step, and an opt-in
 * periodic scan reports drifted requests on the Runs page. Drift is made in the verification database (the projection of
 * the waiting step is cleared, as in t-ex-09-repair); the private stack runs with the scan switched on and a 10 s
 * interval (evidence/s76/compose.override.yml). The scan only reports: the operator repairs through Check consistency.
 */
test.skip(MOCK || !process.env.E2E_DB_CONTAINER, 'Needs the real stack, its verification database and the scan switched on.')

const DB = process.env.E2E_DB_CONTAINER as string
const psql = (sql: string): string => execFileSync('docker', ['exec', DB, 'psql', '-U', 'postgres', '-d', 'orqix', '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-c', sql], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()

async function startRun(page: Page, ws: string, workflowId: string, input: Record<string, unknown>): Promise<string> {
  const r = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/runs`, { workflowId, input })
  expect(r.status, JSON.stringify(r.body)).toBe(201)
  return r.body.id
}

async function rebuild(operator: Page, ws: string, runId: string, what: string): Promise<void> {
  await operator.goto(`/w/${ws}/runs/${runId}`)
  await operator.getByRole('button', { name: 'Check consistency' }).click()
  const dialog = operator.getByRole('dialog', { name: 'Check this request' })
  await expect(dialog.getByText('Open steps are missing from this request')).toBeVisible()
  await expectNoSeriousViolations(operator, `Consistency check, ${what} drift`)
  await dialog.getByRole('button', { name: 'Rebuild steps' }).click()
  await dialog.getByLabel(/Reason/).fill(`Projection lost in a test (${what})`)
  await dialog.getByRole('button', { name: 'Rebuild steps' }).click()
  await expect(dialog).toHaveCount(0)
  await operator.getByRole('button', { name: 'Check consistency' }).click()
  await expect(operator.getByRole('dialog', { name: 'Check this request' }).getByText('Everything matches')).toBeVisible()
  await operator.getByRole('dialog', { name: 'Check this request' }).getByRole('button', { name: 'Close', exact: true }).click()
}

const clear = (runId: string): string => psql(`UPDATE orqix.workflow_run SET current_steps='[]'::jsonb, updated_at=now()-interval '10 minutes' WHERE id='${runId}'`)

test('message wait and worker step are rebuilt; the scan lists drifted requests and clears after the repair', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const publisher = await sessions.as('paul.publisher')
  const requester = await sessions.as('rhea.requester')
  const operator = await sessions.as('olga.operator')

  // Message wait (order payment template).
  const wait = await createWorkflow(designer, ws, uniq('Order wait'), 'order-payment-wait')
  await publish(publisher, ws, wait.id)
  const waitRun = await startRun(requester, ws, wait.id, { orderNumber: uniq('ORD').toUpperCase(), customerName: 'Acme' })

  // Worker step before the manager approval of the purchase template.
  const worker = await createWorkflow(designer, ws, 'Purchase approval')
  await editDraft(designer, ws, worker.id, (d) => {
    d.nodes.push({ id: 'check', type: 'externalTask', label: 'Check the address', config: { topic: uniq('s76topic').toLowerCase().replace(/[^a-z0-9.-]/g, ''), inputMapping: { purpose: '/input/purpose' }, result: [{ name: 'score', type: 'number', required: true }] } })
    for (const t of d.transitions) if (t.from === 'start' && t.to === 'manager') t.to = 'check'
    d.transitions.push({ id: 'c1', from: 'check', to: 'manager', on: 'next' })
    delete d.uiLayout
  })
  await publish(publisher, ws, worker.id)
  const workerRun = await startRun(requester, ws, worker.id, { title: uniq('Worker wait'), purpose: 'Waits for a worker', costCenter: 'OPS', amount: 100 })

  await expect.poll(() => psql(`SELECT count(*) FROM orqix.external_job WHERE run_id='${workerRun}'`), { timeout: 30_000 }).toBe('1')
  await expect.poll(() => psql(`SELECT count(*) FROM orqix.message_subscription WHERE run_id='${waitRun}'`), { timeout: 30_000 }).not.toBe('0')

  const refOf = async (id: string): Promise<string> => (await apiRead<{ reference: string }>(operator, `/workspaces/${ws}/runs/${id}`)).body.reference
  const waitRef = await refOf(waitRun)
  const workerRef = await refOf(workerRun)
  const finding = (ref: string) => operator.getByRole('link', { name: `Review ${ref}`, exact: true })

  // Healthy: nothing found for these two; then drift both.
  clear(waitRun)
  clear(workerRun)

  // The scan (10 s interval, 15 s watchdog) reports both without changing them.
  await operator.goto(`/w/${ws}/runs`)
  const panel = operator.getByRole('region', { name: /Consistency scan/ })
  await expect.poll(async () => {
    await operator.reload()
    await operator.getByRole('heading', { name: 'Consistency scan' }).waitFor({ timeout: 10_000 })
    await operator.waitForLoadState('networkidle')
    return await finding(waitRef).count()
  }, { timeout: 120_000, intervals: [5_000] }).toBeGreaterThan(0)
  await expect(finding(workerRef).first()).toBeVisible()
  await expect(panel).toContainText('The scan only reports')
  await expectNoSeriousViolations(operator, 'Runs, consistency scan with findings')

  // A requester never sees the scan (the Runs page itself answers 403 to them, logged by the browser).
  sessions.allowHttpErrors()
  await requester.goto(`/w/${ws}/runs`)
  await expect(requester.getByRole('heading', { name: 'Consistency scan' })).toHaveCount(0)

  await rebuild(operator, ws, waitRun, 'message wait')
  await rebuild(operator, ws, workerRun, 'worker step')
  expect(psql(`SELECT count(*) FROM orqix.external_job WHERE run_id='${workerRun}' AND status IN ('available','locked')`)).toBe('1')

  // The finding is cleared at once by a successful repair.
  await operator.goto(`/w/${ws}/runs`)
  await expect(finding(waitRef)).toHaveCount(0)
  await expect(finding(workerRef)).toHaveCount(0)
})
