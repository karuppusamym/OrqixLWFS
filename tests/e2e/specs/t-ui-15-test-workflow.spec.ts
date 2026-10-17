import type { Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workflowIdByKey, workspaceId } from '../support/app'

/**
 * "Test this workflow" (docs/build/S7-frontend.md section 5, S4 section 13): a designer tries the purchase workflow with
 * sample requests before publishing. Mock mode: the in-browser API answers with the mock-side port of the simulator,
 * which reproduces the golden traces of the Java simulator. Nothing is started and no request exists afterwards.
 */

async function openTest(designer: Page, ws: string, id: string) {
  await designer.goto(`/w/${ws}/workflows/${id}/edit`)
  await expect(designer.getByTestId('studio')).toBeVisible()
  await designer.getByRole('button', { name: 'Test workflow' }).click()
  const panel = designer.getByTestId('test-panel')
  await expect(panel).toBeVisible()
  return panel
}

async function run(panel: ReturnType<Page['getByTestId']>, amount: string) {
  const field = panel.getByLabel(/Amount/)
  await field.fill(amount)
  await panel.getByTestId('run-test').click()
  await expect(panel.getByTestId('test-outcome')).toBeVisible()
}

test.describe('test this workflow (mock API)', () => {
  test.skip(!MOCK, 'Mock API only; the real API is covered by SimulationApiTest')

  test('1,800 goes to the manager and procurement; 5,000 also goes to Finance', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const id = await workflowIdByKey(designer, ws)
    const panel = await openTest(designer, ws, id)
    await expect(panel).toContainText('not the workflow engine')

    await run(panel, '1800')
    await expect(panel.getByTestId('test-outcome')).toContainText('Fulfilled')
    const list = panel.getByRole('list', { name: 'Steps of the test run' })
    await expect(list).toContainText('"Review request" goes to Managers')
    await expect(list).toContainText('no rule applies, so the "otherwise" result is used')
    await expect(list).not.toContainText('Finance approvers')
    await expect(panel.getByTestId('routes-tested')).toContainText('2 of 6 routes tested')

    // The canvas follows the playback: the first step is current, and the keyboard moves on.
    await expect(designer.locator('.react-flow__node.sim-current')).toHaveCount(1)
    await panel.getByRole('button', { name: /Next step/ }).focus()
    await designer.keyboard.press('ArrowRight')
    await expect(panel.getByText('Step 2 of 7', { exact: true })).toBeVisible()
    await expect(panel.getByTestId('playback-live')).toContainText('Review request')
    await expect(designer.locator('.react-flow__node.sim-visited')).toHaveCount(1)

    await panel.getByTestId('try-another').click()
    await run(panel, '5000')
    await expect(panel.getByTestId('test-outcome')).toContainText('Fulfilled')
    await expect(list).toContainText('rule 1 applies because Amount (USD 5,000) is at least USD 5,000')
    await expect(list).toContainText('Finance approvers')
    await expect(panel.getByTestId('routes-tested')).toContainText('3 of 6 routes tested')

    // A dry run leaves no request behind.
    await designer.goto(`/w/${ws}/runs`)
    await expect(designer.getByText(/Test workflow|dry run/i)).toHaveCount(0)
  })

  test('a rejection and a failing procurement system show their routes', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const id = await workflowIdByKey(designer, ws)
    const panel = await openTest(designer, ws, id)

    await panel.getByLabel(/^Review request/).selectOption('reject')
    await panel.getByLabel(/Reason for rejecting/).fill('Not in the budget')
    await run(panel, '900')
    await expect(panel.getByTestId('test-outcome')).toContainText('Rejected')
    await expect(panel.getByRole('list', { name: 'Steps of the test run' })).toContainText('with the reason "Not in the budget"')

    await panel.getByTestId('try-another').click()
    await panel.getByLabel(/^Review request/).selectOption('approve')
    await panel.getByLabel(/^Create purchase order/).selectOption('unavailable')
    await run(panel, '900')
    await expect(panel.getByTestId('test-outcome')).toContainText('Needs attention')
    await expect(panel.getByRole('list', { name: 'Steps of the test run' })).toContainText('Orqix would try 3 times, 30 seconds apart')
    await expect(panel.getByTestId('test-outcome')).toContainText('Create purchase order')
  })

  test('saved cases are kept per person and judged against what they expect', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const id = await workflowIdByKey(designer, ws)
    let panel = await openTest(designer, ws, id)
    await run(panel, '1800')
    await panel.getByLabel(/Keep this case/).fill('Small purchase')
    await panel.getByRole('button', { name: 'Save case' }).click()
    await expect(panel.getByTestId('case-notice')).toContainText('Saved')

    await designer.reload()
    await designer.getByRole('button', { name: 'Test workflow' }).click()
    panel = designer.getByTestId('test-panel')
    await expect(panel.getByTestId('saved-cases')).toContainText('Small purchase')
    await panel.getByRole('button', { name: 'Run all saved cases' }).click()
    await expect(panel.getByTestId('case-summary')).toContainText('1 of 1 passed')
    await panel.getByLabel('Expect').selectOption('Rejected')
    await panel.getByRole('button', { name: 'Run all saved cases' }).click()
    await expect(panel.getByTestId('case-summary')).toContainText('0 of 1 passed')
    await expect(panel.getByTestId('case-result-Small purchase')).toContainText('Expected Rejected but it ended with Fulfilled')
  })
})
