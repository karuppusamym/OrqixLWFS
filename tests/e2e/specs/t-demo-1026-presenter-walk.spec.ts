import AxeBuilder from '@axe-core/playwright'
import type { Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { workspaceId } from '../support/app'
import { record, shot } from '../support/rehearsal'
import { MOCK, type Username } from '../support/env'

/**
 * S93 presenter walk: for each demo persona, sign in and open EVERY sidebar destination, recording every HTTP 4xx/5xx,
 * console error (the `sessions` fixture fails the test on any console error other than 4xx, which are listed here),
 * page title/h1 and axe serious/critical findings. Operator also opens the Runs list and the needs-attention run (ORQ-1009)
 * and must not see the consistency-findings 500 (defect D1). Real stack only.
 */
// Demo rehearsals need a seeded demo stack (docs/build/S86); the general suite and CI leave them out.
test.skip(MOCK || process.env.E2E_REHEARSAL !== 'true', 'Demo rehearsal: real seeded stack and E2E_REHEARSAL=true only.')
test.describe.configure({ mode: 'serial' })
test.setTimeout(240_000)
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']
const users: Username[] = ['dana.designer', 'paul.publisher', 'rhea.requester', 'mani.manager', 'fin.finance', 'olga.operator', 'wade.admin']

async function visit(page: Page, user: string, label: string, bad: string[]): Promise<void> {
  await page.waitForLoadState('networkidle').catch(() => undefined)
  await page.locator('main h1').first().waitFor({ timeout: 30_000 }).catch(() => undefined)
  const h1 = (await page.locator('main h1').first().innerText().catch(() => '(no h1)')).trim()
  const text = (await page.locator('main').innerText().catch(() => '')).replace(/\s+/g, ' ')
  const r = await new AxeBuilder({ page }).withTags(TAGS).analyze()
  const blocking = r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
  const err = /something went wrong|could not load|unexpected error|page not found/i.test(text)
  await shot(page, `walk-${user}-${label.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}`)
  record(`walk ${user} > ${label}`, blocking.length || err ? 'FAIL' : 'PASS', `h1 "${h1}"${err ? ' | error text on page: ' + text.slice(0, 160) : ''}${blocking.length ? ' | axe ' + blocking.map((v) => v.id).join(',') : ''}`)
  expect.soft(blocking.map((v) => v.id), `${user} ${label} axe`).toEqual([])
  void bad
}

for (const user of users) {
  test(`walk ${user}`, async ({ sessions }) => {
    sessions.allowHttpErrors() // 4xx are recorded below, not failed blindly
    const page = await sessions.as(user)
    const bad: string[] = []
    page.on('response', (r) => {
      if (r.status() >= 400 && r.url().includes('/api/')) bad.push(`${r.status()} ${r.request().method()} ${new URL(r.url()).pathname}`)
    })
    const ws = await workspaceId(page)
    await page.goto(`/w/${ws}`)
    await visit(page, user, 'home', bad)
    const nav = page.getByRole('navigation').first()
    const names = (await nav.getByRole('link').allInnerTexts()).map((s) => s.trim()).filter(Boolean)
    record(`walk ${user}: sidebar`, 'INFO', names.join(' | '))
    for (const name of names) {
      if (name === 'Home') continue
      await nav.getByRole('link', { name, exact: true }).click()
      await visit(page, user, name, bad)
    }
    if (user === 'olga.operator') {
      await page.goto(`/w/${ws}/runs`)
      await visit(page, user, 'Runs list', bad)
      const rows = page.getByRole('row').filter({ hasText: 'ORQ-1009' })
      record('walk olga: ORQ-1009 in runs list', (await rows.count()) ? 'PASS' : 'FAIL', `${await rows.count()} rows`)
      if (await rows.count()) {
        await rows.first().getByRole('link').first().click()
        await expect(page.getByTestId('attention-message')).toBeVisible({ timeout: 30_000 })
        await visit(page, user, 'ORQ-1009 needs attention', bad)
      }
    }
    const consistency = bad.filter((b) => /consisten/i.test(b))
    record(`walk ${user}: HTTP >=400 on /api`, bad.length ? 'INFO' : 'PASS', bad.join(' ; ') || 'none')
    if (user === 'olga.operator') record('D1: consistency-findings 500 gone on operator screens', consistency.length ? 'FAIL' : 'PASS', consistency.join(' ; ') || 'no consistency 4xx/5xx')
    expect(bad.filter((b) => /^5\d\d /.test(b)), `${user}: 5xx responses`).toEqual([])
  })
}
