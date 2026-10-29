import { expect, test } from '../support/fixtures'
import { workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'
import { MOCK } from '../support/env'

test('personal workflow pins survive searches and reloads and can be removed from the overview', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/workflows`)
  const panel = page.getByRole('region', { name: 'Pinned workflows' })
  // Other specs leave copies named "Purchase approval" in a shared database; any one of them serves here.
  await page.getByRole('button', { name: 'Pin Purchase approval', exact: true }).first().click()
  await expect(panel.getByRole('link', { name: 'Purchase approval', exact: true })).toBeVisible()
  const saved = await page.evaluate(() => {
    const key = Object.keys(localStorage).find(value => value.startsWith('orqix.workflowPins.v1:'))!
    return { key, value: JSON.parse(localStorage.getItem(key)!) }
  })
  expect(Object.keys(saved.value).sort()).toEqual(['ids', 'version'])
  expect(saved.value.ids).toHaveLength(1)
  expect(saved.key).toContain(`:${ws}`)
  await page.getByLabel('Search workflows').fill('no-workflow-with-this-name')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByText('No workflows match your search')).toBeVisible()
  await expect(panel.getByRole('link', { name: 'Purchase approval', exact: true })).toBeVisible()
  await page.reload()
  await expect(panel.getByRole('link', { name: 'Purchase approval', exact: true })).toBeVisible()
  await expectNoSeriousViolations(page, 'Pinned workflows with an empty catalog search')
  await page.setViewportSize({ width: 375, height: 812 })
  // The layout reflows after the resize; wait for it rather than measuring the old width.
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
  await expectNoSeriousViolations(page, 'Pinned workflows on mobile')
  await page.setViewportSize({ width: 1440, height: 900 })
  await panel.getByRole('link', { name: 'Purchase approval', exact: true }).click()
  const unpin = page.getByRole('button', { name: 'Unpin Purchase approval', exact: true })
  await expect(unpin).toHaveAttribute('aria-pressed', 'true')
  await unpin.click()
  await expect(page.getByRole('button', { name: 'Pin Purchase approval', exact: true }).first()).toHaveAttribute('aria-pressed', 'false')
  await page.goto(`/w/${ws}/workflows`)
  await expect(panel.getByRole('link')).toHaveCount(0)
  await expect(panel.getByText('Use Pin beside a workflow to keep it here.')).toBeVisible()

  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/workflows`)
  await expect(requester.getByRole('heading', { name: 'Workflows', level: 1 })).toBeVisible()
  await expect(requester.getByRole('region', { name: 'Pinned workflows' })).toHaveCount(0)
  await expect(requester.getByRole('button', { name: /^Pin / })).toHaveCount(0)
})
