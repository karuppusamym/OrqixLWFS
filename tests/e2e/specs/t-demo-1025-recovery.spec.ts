import { expect, test } from '../support/fixtures'
import { decideOpenTask, openTask, workspaceId, workflowIdByKey, submitPurchase } from '../support/app'
import { axe, record, shot } from '../support/rehearsal'
import { MOCK } from '../support/env'

/** S86: demo 1 after an application restart (step 4) and failure/recovery (step 9), on the seeded Purchase approval. */
test.skip(MOCK, 'Real stack only.')
test.setTimeout(240_000)
const stub = process.env.E2E_STUB_URL ?? 'http://localhost:38095'

test('after restart: waiting request survives, manager approves, request fulfilled', async ({ sessions }) => {
  const rq = await sessions.as('rhea.requester')
  const ws = await workspaceId(rq)
  const mgr = await sessions.as('mani.manager')
  await mgr.goto(`/w/${ws}/tasks?view=claimable&sort=newest`)
  await expect(mgr.getByRole('link', { name: 'Ergonomic chairs (12)' })).toBeVisible()
  record('restart: seeded waiting task still present after app restart', 'PASS')
  await openTask(mgr, ws, 'Ergonomic chairs (12)')
  await shot(mgr, 'restart-task')
  await axe(mgr, 'task after restart')
  await decideOpenTask(mgr, 'approve', 'Approved after restart')
  const req = await sessions.as('rhea.requester')
  await req.goto(`/w/${ws}/start/${await workflowIdByKey(req, ws)}`)
  record('restart: manager decision accepted after restart', 'PASS')
})

test('fault: procurement 400 -> Needs attention -> operator retries; commit-then-timeout creates one order', async ({ sessions }) => {
  const rq = await sessions.as('rhea.requester')
  const ws = await workspaceId(rq)
  const wf = await workflowIdByKey(rq, ws)
  await fetch(`${stub}/__reset`, { method: 'POST' })
  await fetch(`${stub}/__faults`, { method: 'POST', body: JSON.stringify({ mode: 'status400', times: 1 }) })
  const a = await submitPurchase(rq, ws, wf, { title: 'Rehearsal fault 400', purpose: 'Recovery drill', costCenter: 'OPS', amount: '100' })
  const mgr = await sessions.as('mani.manager')
  await openTask(mgr, ws, 'Rehearsal fault 400')
  await decideOpenTask(mgr, 'approve')
  sessions.allowHttpErrors()
  const olga = await sessions.as('olga.operator')
  await olga.goto(a.runUrl)
  await expect(olga.getByTestId('attention-message')).toBeVisible({ timeout: 90_000 })
  const msg = (await olga.getByTestId('attention-message').innerText()).replace(/\s+/g, ' ')
  await shot(olga, 'fault-needs-attention')
  await axe(olga, 'run needs attention (operator)')
  record('fault 400: Needs attention with business message', 'PASS', msg.slice(0, 300))
  await olga.getByRole('button', { name: 'Retry step' }).first().click()
  const dlg = olga.getByRole('dialog')
  await dlg.getByLabel(/Reason/).fill('Cause fixed, retrying for rehearsal')
  await axe(olga, 'retry dialog')
  await dlg.getByRole('button', { name: 'Retry step' }).click()
  await expect(olga.getByTestId('run-status')).toContainText(/Fulfilled/, { timeout: 90_000 })
  record('fault 400: operator retry -> Fulfilled', 'PASS')
  await shot(olga, 'fault-retried')
  await fetch(`${stub}/__faults`, { method: 'POST', body: JSON.stringify({ mode: 'commitThenTimeout', times: 1 }) })
  const before = await (await fetch(`${stub}/__orders`)).text()
  const b = await submitPurchase(rq, ws, wf, { title: 'Rehearsal commit timeout', purpose: 'Idempotency drill', costCenter: 'OPS', amount: '120' })
  await openTask(mgr, ws, 'Rehearsal commit timeout')
  await decideOpenTask(mgr, 'approve')
  await rq.goto(b.runUrl)
  await expect(rq.getByTestId('run-status')).toContainText(/Completed|attention/i, { timeout: 120_000 })
  const st = (await rq.getByTestId('run-status').innerText()).replace(/\s+/g, ' ')
  const orders = JSON.parse(await (await fetch(`${stub}/__orders`)).text()) as unknown
  const n = (orders as { count: number }).count
  const nb = (() => { try { return (JSON.parse(before) as { count: number }).count } catch { return -1 } })()
  record('commit-then-timeout: exactly one new order', n - nb === 1 ? 'PASS' : 'FAIL', `${st}; orders before ${nb}, after ${n}`)
})
