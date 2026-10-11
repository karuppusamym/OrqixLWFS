import type { Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * T-UI-14: the "Where is my request" diagram on the run detail page and the Insights dashboard (docs/build/S7-frontend.md
 * sections 4 and 9). Mock data only: the demo seed has requests in every state and a synthetic three-month history, which the
 * real stack does not have. Set EVIDENCE_DIR to also save screenshots (evidence/design/<timestamp>/).
 */
test.skip(!MOCK, 'Uses the demo seed and its synthetic history')

async function shot(page: Page, name: string) {
  const dir = process.env.EVIDENCE_DIR
  if (!dir) return
  mkdirSync(dir, { recursive: true })
  await page.screenshot({ path: join(dir, `${name}.png`), fullPage: false })
}

async function openRun(page: Page, ws: string, reference: string) {
  await page.goto(`/w/${ws}/runs`)
  await page.getByRole('link', { name: new RegExp(reference) }).first().click()
  await expect(page.getByTestId('run-reference')).toHaveText(reference)
}

async function noSidewaysScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
}

test('diagram: an operator sees the current step, the path taken and step details, and the timeline stays', async ({ sessions }) => {
  await sessions.mockCall('reset', { demo: true })
  const page = await sessions.as('olga.operator')
  const ws = await workspaceId(page)
  await openRun(page, ws, 'ORQ-1003')

  // The timeline is the default; the diagram is one click away and the timeline comes back.
  await expect(page.getByTestId('timeline')).toBeVisible()
  await page.getByRole('button', { name: 'Diagram' }).click()
  await expect(page.getByTestId('run-diagram-canvas')).toBeVisible()
  const current = page.getByTestId('run-step-finance')
  await expect(current).toHaveAttribute('data-step-status', 'waiting')
  await expect(current.getByTestId('run-step-chip')).toHaveText('Waiting here')
  await expect(page.getByTestId('run-step-manager')).toHaveAttribute('data-step-status', 'approved')
  await expect(page.getByTestId('run-step-manager').getByTestId('run-step-chip')).toHaveText('Approved')
  await expect(page.getByTestId('run-step-start')).toHaveAttribute('data-step-status', 'done')
  await expect(page.getByTestId('run-step-procure')).toHaveAttribute('data-step-status', 'ahead')

  // Keyboard: steps are focusable, named, described, and Enter shows the details.
  const node = page.locator('.react-flow__node[data-id="finance"]')
  await expect(node).toHaveAttribute('aria-label', /Approval: Finance review\. Waiting here\./)
  await expect(node).toHaveAttribute('aria-describedby', /run-step-desc/)
  await node.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('step-details-body')).toContainText('Finance review')
  await expect(page.getByTestId('step-details-body')).toContainText('Technical details')
  await expect(page.getByTestId('step-details-body')).toContainText('Step ID')
  await shot(page, 'diagram-operator-desktop')
  await expectNoSeriousViolations(page, 'Run diagram (operator)')

  await page.getByRole('button', { name: 'Timeline' }).click()
  await expect(page.getByTestId('timeline')).toBeVisible()
})

test('diagram: a finished request dims the steps it did not use; a failed step says it needs attention', async ({ sessions }) => {
  await sessions.mockCall('reset', { demo: true })
  const page = await sessions.as('olga.operator')
  const ws = await workspaceId(page)
  await openRun(page, ws, 'ORQ-1007')
  await page.getByRole('button', { name: 'Diagram' }).click()
  await expect(page.getByTestId('run-step-manager')).toHaveAttribute('data-step-status', 'rejected')
  await expect(page.getByTestId('run-step-finance')).toHaveAttribute('data-step-status', 'skipped')
  await expect(page.getByTestId('run-step-finance').getByTestId('run-step-chip')).toHaveText('Not used')
  await shot(page, 'diagram-rejected-desktop')

  await openRun(page, ws, 'ORQ-1009')
  await expect(page.getByTestId('run-step-procure')).toHaveAttribute('data-step-status', 'attention')
  await expect(page.getByTestId('run-step-procure').getByTestId('run-step-chip')).toHaveText('Needs attention')
  await shot(page, 'diagram-attention-desktop')
})

test('diagram: the requester sees business wording and no technical details', async ({ sessions }) => {
  await sessions.mockCall('reset', { demo: true })
  const page = await sessions.as('rhea.requester')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/requests`)
  await page.getByRole('link', { name: /ORQ-1001|Replacement laptop/ }).first().click()
  await page.getByRole('button', { name: 'Diagram' }).click()
  await expect(page.getByTestId('run-step-manager').getByTestId('run-step-chip')).toHaveText('You are here')
  await page.locator('.react-flow__node[data-id="manager"]').focus()
  await page.keyboard.press('Enter')
  const details = page.getByTestId('step-details-body')
  await expect(details).toContainText('Waiting for')
  await expect(details).not.toContainText('Technical details')
  await expect(details).not.toContainText('Step ID')
  await expectNoSeriousViolations(page, 'Run diagram (requester)')
})

test('diagram: at 375 px the diagram scrolls inside its box and the page does not scroll sideways', async ({ sessions }) => {
  await sessions.mockCall('reset', { demo: true })
  const page = await sessions.as('olga.operator')
  const ws = await workspaceId(page)
  await page.setViewportSize({ width: 375, height: 812 })
  await openRun(page, ws, 'ORQ-1003')
  await page.getByRole('button', { name: 'Diagram' }).click()
  await expect(page.getByTestId('run-diagram-canvas')).toBeVisible()
  await expect(page.getByTestId('run-step-finance')).toBeVisible()
  await noSidewaysScroll(page)
  const box = await page.getByTestId('run-diagram-canvas').boundingBox()
  expect(box && box.width).toBeLessThanOrEqual(375)
  await shot(page, 'diagram-operator-mobile')
  await expectNoSeriousViolations(page, 'Run diagram (375 px)')
})

test('insights: cards, chart with a table, bottlenecks with CSV, lists and the SLA view; no request data', async ({ sessions }) => {
  await sessions.mockCall('reset', { demo: true })
  const page = await sessions.as('olga.operator')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}`)
  await page.getByRole('link', { name: 'Insights' }).click()
  await expect(page.getByRole('heading', { name: 'Insights', level: 1 })).toBeVisible()
  await expect(page.getByTestId('insights-cards')).toBeVisible()
  const started = Number(await page.getByTestId('card-started').locator('p').nth(1).innerText())
  expect(started).toBeGreaterThan(50)
  await expect(page.getByTestId('throughput-chart').getByRole('img')).toBeVisible()
  await page.getByText('Show the numbers as a table').click()
  await expect(page.getByRole('region', { name: 'Requests started and completed per day' })).toBeVisible()
  await expect(page.getByTestId('bottleneck-table')).toContainText('Finance review')
  await expect(page.getByTestId('sla-view')).toContainText('Approvals that timed out')
  await expect(page.getByTestId('attention-list')).toContainText('ORQ-1009')
  // Aggregates only.
  await expect(page.locator('main')).not.toContainText(/Replacement laptop|Ergonomic chairs|Label printer/)

  // Longer period, then download the bottleneck table.
  await page.getByRole('button', { name: 'Last 90 days' }).click()
  await expect(page.getByRole('button', { name: 'Last 90 days' })).toHaveAttribute('aria-pressed', 'true')
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: /Download CSV/ }).click()
  expect((await download).suggestedFilename()).toBe('orqix-insights-steps.csv')
  await page.evaluate(() => window.scrollTo(0, 0))
  await shot(page, 'insights-desktop')
  await expectNoSeriousViolations(page, 'Insights')
})

test('insights: at 375 px nothing scrolls sideways; requesters have no Insights link', async ({ sessions }) => {
  await sessions.mockCall('reset', { demo: true })
  const operator = await sessions.as('vic.viewer')
  const ws = await workspaceId(operator)
  await operator.setViewportSize({ width: 375, height: 812 })
  await operator.goto(`/w/${ws}/insights`)
  await expect(operator.getByTestId('insights-cards')).toBeVisible()
  await expect(operator.getByTestId('throughput-chart')).toBeVisible()
  await noSidewaysScroll(operator)
  await shot(operator, 'insights-mobile')
  await expectNoSeriousViolations(operator, 'Insights (375 px)')

  const requester = await sessions.as('rhea.requester')
  await requester.setViewportSize({ width: 1440, height: 900 })
  await requester.goto(`/w/${ws}`)
  await expect(requester.getByRole('link', { name: 'Insights' })).toHaveCount(0)
  await requester.goto(`/w/${ws}/insights`)
  await expect(requester.getByText('No access')).toBeVisible()
})
