import { readFile } from 'node:fs/promises'
import type { Download } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'
import { MOCK } from '../support/env'

async function contents(download: Download) {
  const path = await download.path()
  if (!path) throw new Error('The report download did not finish')
  return readFile(path, 'utf8')
}

test('Insights exports filtered aggregate reports and daily CSV, with no individual request details', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  const page = await sessions.as('olga.operator')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/insights`)
  await page.getByRole('button', { name: 'Last 90 days' }).click()
  const reportButton = page.getByRole('button', { name: 'Download report (JSON)' })
  await expect(reportButton).toBeEnabled()
  const reportDownload = page.waitForEvent('download')
  await reportButton.click()
  const file = await reportDownload
  expect(file.suggestedFilename()).toMatch(/^orqix-insights-\d{4}-\d{2}-\d{2}-to-\d{4}-\d{2}-\d{2}\.json$/)
  const raw = await contents(file)
  const report = JSON.parse(raw)
  expect(report).toMatchObject({ format: 'orqix.insights', version: 1, workspace: { id: ws }, filters: { workflowId: null, timezone: 'UTC' } })
  expect(report.dailyThroughput).toHaveLength(90)
  expect(report.limits.bottlenecks).toHaveProperty('truncated')
  expect(report.generatedAt).toHaveProperty('summary')
  expect(raw).not.toMatch(/"(?:runId|reference|oldestOpen|input|output|title)"|Replacement laptop|ORQ-1009/)

  const dailyDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download daily CSV' }).click()
  const csvFile = await dailyDownload
  const csv = await contents(csvFile)
  expect(csvFile.suggestedFilename()).toBe(file.suggestedFilename().replace(/\.json$/, '.csv'))
  expect(csv.split('\r\n')[0]).toBe('"UTC date","Started","Completed","Fulfilled","Rejected"')
  expect(csv.trimEnd().split('\r\n')).toHaveLength(91)

  const select = page.getByLabel('Workflow', { exact: true })
  if (await select.count()) {
    const option = select.locator('option').nth(1)
    const workflowId = await option.getAttribute('value')
    await select.selectOption(workflowId!)
    await expect(reportButton).toBeEnabled()
    const filteredDownload = page.waitForEvent('download')
    await reportButton.click()
    const filtered = JSON.parse(await contents(await filteredDownload))
    expect(filtered.filters.workflowId).toBe(workflowId)
    for (const row of [...filtered.cycleTimes, ...filtered.bottlenecks, ...filtered.sla.timeouts]) expect(row.workflow.id).toBe(workflowId)
  }
  await expectNoSeriousViolations(page, 'Insights report downloads')
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(reportButton).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
  await expectNoSeriousViolations(page, 'Insights report downloads on mobile')

  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/insights`)
  await expect(requester.getByText('No access', { exact: true })).toBeVisible()
  await expect(requester.getByRole('button', { name: /Download/ })).toHaveCount(0)
})
