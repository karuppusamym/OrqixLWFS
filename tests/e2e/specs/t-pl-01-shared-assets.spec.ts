import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { selectStep, workspaceId } from '../support/app'
import { createWorkflow, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * PL01 shared assets (docs/build/S61): a designer shares a rule table from the studio, then finds the asset under
 * Settings > Shared assets, sees where it is used, archives and restores it. Archiving blocks "Check workflow" for the
 * workflow that still links it; restoring clears the block.
 */
test.skip(MOCK, 'Mock state is held in the page and a navigation resets it (docs/build/S62 observations); the journey crosses pages.')

test('a designer shares a decision table, sees where it is used, archives and restores it', async ({ sessions }) => {
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const wf = await createWorkflow(page, ws, uniq('Shared decision flow'))
  const key = uniq('amt').toLowerCase().replace(/[^a-z0-9-]/g, '')
  const name = `Amount policy ${key}`

  await page.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await expect(page.getByRole('tab', { name: 'Steps' })).toBeVisible()
  await page.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(page, 'amountPolicy')
  const inspector = page.getByTestId('inspector')
  await inspector.getByRole('button', { name: 'Share this rule table' }).click()
  const form = inspector.getByRole('form', { name: 'Share this rule table' })
  await form.getByLabel('Shared name').fill(name)
  await form.getByLabel('Shared key').fill(key)
  await expectNoSeriousViolations(page, 'Studio, share a rule table')
  await form.getByRole('button', { name: 'Share', exact: true }).click()
  await expect(inspector.getByText(`Shared as ${name}, version 1`)).toBeVisible()
  await expect(inspector.getByTestId('shared-decision-link')).toContainText(`(${key}), version 1`)
  await expectNoSeriousViolations(page, 'Studio, linked shared decision')
  // Autosave stores the link before the settings page looks for where it is used.
  await expect(page.getByText(/Saved|All changes saved/).first()).toBeVisible({ timeout: 15_000 })

  await page.goto(`/w/${ws}/settings`)
  await page.getByRole('tab', { name: 'Shared assets' }).click()
  const search = page.getByRole('search', { name: 'Search shared assets' })
  await search.getByLabel('Name, key or owner').fill(key)
  await search.getByRole('button', { name: 'Search' }).click()
  const row = page.getByTestId('asset-row').filter({ hasText: name })
  await expect(row).toHaveCount(1)
  await expect(row).toContainText('Version 1')
  await row.getByRole('button').first().click()
  const detail = row.getByTestId('asset-detail')
  await expect(detail.getByRole('heading', { name: 'Where it is used' })).toBeVisible()
  await expect(detail.getByTestId('asset-usage').filter({ hasText: wf.name })).toBeVisible({ timeout: 20_000 })
  await expect(detail.getByTestId('asset-version')).toHaveCount(1)
  await expectNoSeriousViolations(page, 'Shared assets, asset detail with where-used')

  // Archive: the asset leaves the default list (archived assets are hidden), reappears under "Show archived", and the
  // workflow that links it is told it can no longer be published.
  await detail.getByRole('button', { name: 'Archive' }).click()
  await expect(page.getByTestId('asset-row').filter({ hasText: name })).toHaveCount(0)
  await search.getByLabel('Show archived').check()
  const archived = page.getByTestId('asset-row').filter({ hasText: name })
  await expect(archived).toContainText('Archived')
  // The row stays open after archiving.
  await expect(archived.getByRole('button', { name: 'Restore' })).toBeVisible()
  await expectNoSeriousViolations(page, 'Shared assets, archived asset')
  await page.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await page.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(page, 'amountPolicy')
  await expect(page.getByTestId('inspector').getByText(/is archived\. Publishing is blocked/)).toBeVisible()

  await page.goto(`/w/${ws}/settings`)
  await page.getByRole('tab', { name: 'Shared assets' }).click()
  await search.getByLabel('Name, key or owner').fill(key)
  await search.getByLabel('Show archived').check()
  await search.getByRole('button', { name: 'Search' }).click()
  const again = page.getByTestId('asset-row').filter({ hasText: name })
  await again.getByRole('button').first().click()
  await again.getByRole('button', { name: 'Restore' }).click()
  await expect(again).not.toContainText('Archived')
  await page.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await page.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(page, 'amountPolicy')
  await expect(page.getByTestId('inspector').getByText(/is archived/)).toHaveCount(0)

  // A requester has no settings at all.
  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/settings`)
  await expect(requester.getByRole('tab', { name: 'Shared assets' })).toHaveCount(0)
})
