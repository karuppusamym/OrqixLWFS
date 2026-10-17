import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { createPurchaseWorkflow, workspaceId, selectStep } from '../support/app'

test.describe('Shared workflow publication tests (mock API)', () => {
  test.skip(!MOCK, 'Mock browser journey; backend integration tests enforce real publication and sample privacy')

  test('shares an expected case, publishes its tested draft and blocks an edited revision', async ({ sessions }) => {
    sessions.allowHttpErrors()
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    let page = await sessions.as('dana.designer')
    const ws = await workspaceId(page)
    const wf = await createPurchaseWorkflow(page, ws)
    await page.goto(`/w/${ws}/workflows/${wf}/edit`)
    await page.getByRole('button', { name: /Test workflow/ }).click()
    await page.getByTestId('run-test').click()
    await expect(page.getByTestId('test-outcome')).toContainText('Fulfilled')
    await page.getByLabel('Keep this case').fill('Purchase success')
    await page.getByRole('button', { name: 'Save case', exact: true }).click()
    await page.getByRole('button', { name: 'Share and test for publication' }).click()
    await expect(page.getByTestId('case-notice')).toContainText('This exact draft satisfies the publication test gate')
    // Mock responses are returned by an in-page FetchInterceptor, so Chromium has no network response event.
    const report = await page.evaluate(async path => {
      const suite = await (await fetch(path)).json() as { version: number }
      return (await fetch(`${path}/run`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision: 1, suiteVersion: suite.version }) })).json()
    }, `/api/v1/workspaces/${ws}/workflows/${wf}/test-suite`)
    expect(report.results).toHaveLength(1)
    expect(JSON.stringify(report)).not.toContain('Purchase success')
    expect(report.results[0]).toEqual({ id: expect.any(String), passed: true, status: 'finished' })

    page = await sessions.as('paul.publisher')
    const sampleAccess = await page.evaluate(async path => {
      const response = await fetch(path)
      return { status: response.status, body: await response.text() }
    }, `/api/v1/workspaces/${ws}/workflows/${wf}/test-suite`)
    expect(sampleAccess.status).toBe(403)
    expect(sampleAccess.body).not.toContain('Purchase success')
    await page.goto(`/w/${ws}/workflows/${wf}/publish?revision=1`)
    const publish = page.getByRole('button', { name: /^Publish version/ })
    const warnings = page.getByRole('checkbox', { name: /warnings/i })
    if (await warnings.isVisible()) await warnings.check()
    await publish.click()
    await expect(page.getByRole('heading', { name: 'Version 1 published', exact: true })).toBeVisible()

    page = await sessions.as('dana.designer')
    await page.goto(`/w/${ws}/workflows/${wf}/edit`)
    await page.getByRole('tab', { name: 'Steps', exact: true }).click()
    await selectStep(page, 'amountPolicy')
    const threshold = page.locator('[data-field="rows.0.when.value"]')
    await threshold.fill('6000')
    await threshold.press('Enter')
    await page.keyboard.press('Control+s')
    await expect(page.getByTestId('save-state')).toHaveText(/Saved/)

    page = await sessions.as('paul.publisher')
    await page.goto(`/w/${ws}/workflows/${wf}/publish?revision=2`)
    if (await warnings.isVisible()) await warnings.check()
    await page.getByRole('button', { name: /^Publish version/ }).click()
    await expect(page.getByText(/Run and pass the current shared tests/)).toBeVisible()
  })

  test('authors a conjunctive rule and retains every condition after reload', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const page = await sessions.as('dana.designer')
    const ws = await workspaceId(page)
    const wf = await createPurchaseWorkflow(page, ws)
    await page.goto(`/w/${ws}/workflows/${wf}/edit`)
    await page.getByRole('tab', { name: 'Steps', exact: true }).click()
    await selectStep(page, 'amountPolicy')
    await page.getByLabel('Condition group').selectOption('all')
    await page.getByLabel('Value to look at').nth(1).selectOption('/input/costCenter')
    await expect(page.getByLabel('Sample: /input/costCenter')).toBeVisible()
    await page.keyboard.press('Control+s')
    await expect(page.getByTestId('save-state')).toHaveText(/Saved/)
    await page.reload()
    await page.getByRole('tab', { name: 'Steps', exact: true }).click()
    await selectStep(page, 'amountPolicy')
    await expect(page.getByLabel('Condition group')).toHaveValue('all')
    await expect(page.getByLabel('Value to look at').nth(1)).toHaveValue('/input/costCenter')
  })
})
