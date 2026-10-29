import path from 'node:path'
import type { Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { apiGet, workspaceId } from '../support/app'
import { axe, key, record, shot } from '../support/rehearsal'
import { MOCK } from '../support/env'

/**
 * S86 demo rehearsal: docs/demo-pega-migration.md followed step by step on the real stack. Results go to
 * evidence/demo-1025/results.jsonl. Real backend only (the mock rejects Pega conversion by design).
 */
// Demo rehearsals need a seeded demo stack (docs/build/S86); the general suite and CI leave them out.
test.skip(MOCK || process.env.E2E_REHEARSAL !== 'true', 'Demo rehearsal: real seeded stack and E2E_REHEARSAL=true only.')
test.describe.configure({ mode: 'serial' })
test.setTimeout(240_000)
const xpdl = path.resolve('../../examples/migration/pega-xpdl')

async function analyse(page: Page, ws: string, file: string, format = 'pegaXpdl') {
  await page.goto(`/w/${ws}/migrations`)
  await page.getByLabel('Source format').selectOption(format)
  await page.getByLabel(/Source file/).setInputFiles(file)
  await page.getByRole('button', { name: 'Analyze source' }).click()
}

async function convert(page: Page, ws: string, fixture: string, prefix: string, extra?: (p: Page) => Promise<void>): Promise<string> {
  await analyse(page, ws, path.join(xpdl, fixture))
  await expect(page.getByRole('region', { name: 'Compatibility report' })).toBeVisible()
  await shot(page, `${prefix}-report`)
  await axe(page, `${fixture} compatibility report`)
  const k = key(prefix)
  await page.getByLabel('Workflow key', { exact: true }).fill(k)
  if (extra) await extra(page)
  const acks = page.getByRole('checkbox')
  for (let i = 0; i < await acks.count(); i++) if (await acks.nth(i).isVisible()) await acks.nth(i).check()
  await shot(page, `${prefix}-form`)
  await axe(page, `${fixture} review form`)
  await page.getByRole('button', { name: 'Convert to draft' }).click()
  await expect(page.getByTestId('studio')).toBeVisible({ timeout: 30_000 })
  await shot(page, `${prefix}-studio`)
  await axe(page, `${fixture} studio`)
  const id = page.url().match(/workflows\/([0-9a-f-]+)/)?.[1]
  if (!id) throw new Error('no workflow id in ' + page.url())
  record(`${fixture}: convert to draft`, 'PASS', `key ${k}`)
  return id
}

async function publishAs(sessions: { as: (u: 'paul.publisher') => Promise<Page> }, ws: string, id: string, prefix: string) {
  const pub = await sessions.as('paul.publisher')
  await pub.goto(`/w/${ws}/workflows/${id}/publish`)
  await expect(pub.getByRole('button', { name: /Publish version \d+/ })).toBeVisible()
  await shot(pub, `${prefix}-publish`)
  await axe(pub, `${prefix} publish review`)
  const ack = pub.getByRole('checkbox')
  for (let i = 0; i < await ack.count(); i++) if (await ack.nth(i).isVisible() && !(await ack.nth(i).isChecked())) await ack.nth(i).check()
  await pub.getByRole('button', { name: /Publish version \d+/ }).click()
  await expect(pub.getByTestId('publish-success')).toBeVisible({ timeout: 30_000 })
  record(`${prefix}: publish`, 'PASS')
}

async function request(sessions: { as: (u: 'rhea.requester') => Promise<Page> }, ws: string, id: string, amount: string | null, prefix: string): Promise<{ page: Page; url: string; ref: string }> {
  const rq = await sessions.as('rhea.requester')
  await rq.goto(`/w/${ws}/start/${id}`)
  await rq.getByText('Add your own reference number').waitFor()
  if (amount !== null) await rq.locator('#req-amount').fill(amount)
  await rq.getByText('Add your own reference number').click()
  await rq.locator('#req-businessKey').fill(`${prefix}-${amount ?? 'none'}`)
  await rq.getByRole('button', { name: 'Review request' }).click()
  await rq.getByRole('button', { name: 'Submit request' }).click()
  await expect(rq.getByRole('heading', { level: 1, name: 'Request submitted' })).toBeVisible()
  const url = (await rq.getByRole('link', { name: 'View your request' }).getAttribute('href')) ?? ''
  const ref = (await rq.getByTestId('request-id').innerText()).trim()
  return { page: rq, url, ref }
}

async function outcome(rq: Page, url: string, expected: RegExp, label: string) {
  await rq.goto(url)
  await expect(rq.getByTestId('run-status')).toContainText(expected, { timeout: 90_000 })
  const t = (await rq.getByTestId('run-status').innerText()).replace(/\s+/g, ' ')
  const tl = (await rq.getByTestId('timeline').innerText()).replace(/\s+/g, ' ').slice(0, 300)
  await shot(rq, label.replace(/[^a-z0-9]+/gi, '-'))
  await axe(rq, `run ${label}`)
  record(`${label}: outcome`, 'PASS', `${t} | ${tl}`)
}

test('small.xpdl: convert, publish, run -> Done', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  const id = await convert(dana, ws, 'small.xpdl', 'rh-small')
  await publishAs(sessions, ws, id, 'small')
  const r = await request(sessions, ws, id, null, 'small')
  await outcome(r.page, r.url, /Completed/, 'small empty input')
  const status = await r.page.getByTestId('run-status').innerText()
  record('small: outcome text mentions Done', status.includes('Done') ? 'PASS' : 'FAIL', status)
})

test('medium.xpdl: 4,999.99 Standard, 5,000 and 5,000.01 Finance', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  const id = await convert(dana, ws, 'medium.xpdl', 'rh-medium')
  await publishAs(sessions, ws, id, 'medium')
  for (const [amt, exp] of [['4999.99', 'Standard'], ['5000', 'Finance'], ['5000.01', 'Finance']] as const) {
    const r = await request(sessions, ws, id, amt, 'medium')
    await outcome(r.page, r.url, new RegExp(exp), `medium ${amt} -> ${exp}`)
  }
})

test('review-branch.xpdl: approve -> Finance, reject -> Rejected', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  const id = await convert(dana, ws, 'review-branch.xpdl', 'rh-review', async (p) => {
    await p.getByLabel('Map source assignment: Managers').selectOption({ label: 'Managers' })
    await p.getByLabel(/Approval routes/).fill('{"review":{"approvedFlowId":"approvedPath","rejectedFlowId":"rejectedPath"}}')
  })
  await publishAs(sessions, ws, id, 'review')
  const a = await request(sessions, ws, id, '5000', 'review-approve')
  const b = await request(sessions, ws, id, '5000', 'review-reject')
  const mgr = await sessions.as('mani.manager')
  for (const [bk, action] of [[a.ref, 'approve'], [b.ref, 'reject']] as const) {
    await mgr.goto(`/w/${ws}/tasks?view=claimable&sort=newest`)
    await mgr.getByRole('link', { name: bk, exact: true }).first().click()
    await expect(mgr.getByTestId('task-actions')).toBeVisible()
    await shot(mgr, `review-task-${action}`)
    await axe(mgr, `review task ${action}`)
    const claim = mgr.getByRole('button', { name: 'Claim task' })
    if (await claim.isVisible()) await claim.click()
    await mgr.getByLabel('Comment').fill(action === 'reject' ? 'Not justified' : 'OK')
    await mgr.getByRole('button', { name: action === 'approve' ? 'Approve' : 'Reject', exact: true }).click()
    await expect(mgr.getByTestId('task-banner')).toContainText(action === 'approve' ? 'You approved' : 'You rejected')
    record(`review-branch manager ${action}`, 'PASS')
  }
  await outcome(a.page, a.url, /Finance/, 'review approve -> Finance')
  await outcome(b.page, b.url, /Rejected/, 'review reject -> Rejected')
})

test('unsupported-trigger.xpdl: source-linked blocker, no workflow created', async ({ sessions }) => {
  sessions.allowHttpErrors() // the refusal is a 422
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  await analyse(dana, ws, path.join(xpdl, 'unsupported-trigger.xpdl'))
  await expect(dana.getByRole('region', { name: 'Compatibility report' })).toBeVisible()
  const text = await dana.locator('main').innerText()
  await shot(dana, 'unsupported-trigger-report')
  await axe(dana, 'unsupported-trigger report')
  record('unsupported-trigger: blocker shown', /blocker/i.test(text) && /\/Package\[/.test(text) ? 'PASS' : 'FAIL', text.match(/[A-Z_]{6,} · \/Package[^\n]*/g)?.slice(0, 3).join(' ; ') ?? 'no coded finding')
  const k = key('rh-unsup')
  record('unsupported-trigger: Convert button offered despite blocker (UX)', (await dana.getByRole('button', { name: 'Convert to draft' }).count()) === 0 ? 'PASS' : 'INFO', 'button is visible')
  await dana.getByLabel('Workflow key', { exact: true }).fill(k)
  const acks = dana.getByRole('checkbox')
  for (let i = 0; i < await acks.count(); i++) if (await acks.nth(i).isVisible()) await acks.nth(i).check()
  await dana.getByRole('button', { name: 'Convert to draft' }).click()
  await dana.waitForTimeout(3000)
  await shot(dana, 'unsupported-trigger-after-convert')
  const after = (await dana.locator('main').innerText()).replace(/\s+/g, ' ')
  const stayed = /migrations/.test(dana.url()) && !/\/edit/.test(dana.url())
  const alertText = await dana.getByRole('alert').allInnerTexts()
  record('unsupported-trigger: Convert refused (no studio)', stayed ? 'PASS' : 'FAIL', `url ${dana.url()} alert ${JSON.stringify(alertText)}`)
  await axe(dana, 'unsupported-trigger after refused convert')
  const list = await apiGet<{ items: Array<{ key: string }> }>(dana, `/workspaces/${ws}/workflows?query=${k}`)
  record('unsupported-trigger: no workflow created', list.items.some((w) => w.key === k) ? 'FAIL' : 'PASS', after.slice(0, 200))
})

test('Pega ZIP archive assessment', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  for (const size of ['small', 'medium', 'complex']) {
    await analyse(dana, ws, path.resolve(`../../examples/migration/pega-synthetic/${size}/archive.zip`), 'pegaArchive')
    await expect(dana.getByText('Manual Pega mapping required')).toBeVisible()
    await shot(dana, `pega-archive-${size}`)
    if (size === 'small') await axe(dana, 'pega archive assessment')
    record(`pega archive ${size}`, (await dana.getByRole('button', { name: 'Convert to draft' }).count()) === 0 ? 'PASS' : 'FAIL', 'manual mapping required, no Convert button')
  }
})
