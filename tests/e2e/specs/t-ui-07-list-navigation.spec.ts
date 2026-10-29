import { expect, test } from '../support/fixtures'
import { workspaceId } from '../support/app'
import { MOCK } from '../support/env'

test('run search survives reload and Back, including an unfinished search', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: true })
  const page = await sessions.as('olga.operator')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/runs?lifecycle=waiting`)
  await expect(page.getByLabel('Status')).toHaveValue('waiting')
  await page.getByRole('textbox', { name: 'Request reference', exact: true }).fill('  ORQ-1003  ')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page).toHaveURL(/businessKey=ORQ-1003/)
  if (MOCK) {
    await expect(page.getByRole('link', { name: 'ORQ-1003', exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'ORQ-1006', exact: true })).toHaveCount(0)
  }
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Request reference', exact: true })).toHaveValue('ORQ-1003')
  await page.getByRole('button', { name: 'Clear filters' }).click()
  await expect(page).toHaveURL(`/w/${ws}/runs`)
  await page.getByRole('textbox', { name: 'Request reference', exact: true }).fill('unfinished search')
  await page.goBack()
  await expect(page.getByRole('textbox', { name: 'Request reference', exact: true })).toHaveValue('ORQ-1003')
  await expect(page.getByLabel('Status')).toHaveValue('waiting')
})

test('home shortcuts and run filters fit narrow screens', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: true })
  const page = await sessions.as('mani.manager')
  const ws = await workspaceId(page)
  await page.setViewportSize({ width: 390, height: 844 })
  const shortcuts = page.getByRole('region', { name: 'Workspace shortcuts' })
  await expect(shortcuts.getByRole('link', { name: /Assigned to you/ })).toBeVisible()
  await expect(shortcuts.getByRole('link', { name: /Available to claim/ })).toHaveAttribute('href', `/w/${ws}/tasks?view=claimable`)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const operator = await sessions.as('olga.operator')
  await operator.setViewportSize({ width: 320, height: 740 })
  await operator.goto(`/w/${ws}/runs?lifecycle=waiting`)
  await expect(operator.getByRole('textbox', { name: 'Request reference', exact: true })).toBeVisible()
  await expect(operator.getByRole('button', { name: 'Search', exact: true })).toBeVisible()
  await expect.poll(() => operator.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
