import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { createWorkflow, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * PL01 (docs/build/S48): a designer creates a folder, files a workflow into it, filters the catalog by folder and
 * owner (state lives in the URL), renames and deletes the folder (workflows return to unfiled). A requester sees no
 * folder management. Keyboard-reachable dialogs and no serious accessibility findings.
 */
test('designer files a workflow in a folder, filters by folder and owner, then deletes the folder', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const wf = await createWorkflow(page, ws, uniq('Filed flow'))
  const folderName = uniq('Folder')
  const renamed = `${folderName} renamed`

  await page.goto(`/w/${ws}/workflows`)
  await page.getByLabel('Search workflows').fill(wf.name)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByRole('link', { name: new RegExp(wf.name) }).first()).toBeVisible()

  // Create a folder; duplicate names are refused (case-insensitive).
  await page.getByText('Manage library', { exact: true }).click()
  await page.getByRole('button', { name: 'Manage folders' }).click()
  const manage = page.getByRole('dialog', { name: 'Manage folders' })
  await manage.getByLabel('New folder name').fill(folderName)
  await expectNoSeriousViolations(page, 'Manage folders dialog')
  await manage.getByRole('button', { name: 'Create folder' }).click()
  await expect(manage.getByText(folderName, { exact: true })).toBeVisible()
  sessions.allowHttpErrors() // the duplicate name is refused with 409 on purpose
  await manage.getByLabel('New folder name').fill(folderName.toUpperCase())
  await manage.getByRole('button', { name: 'Create folder' }).click()
  await expect(manage.getByRole('alert')).toContainText('A folder with this name already exists')
  await manage.getByRole('button', { name: 'Close', exact: true }).click()

  // File the workflow into the folder and hand ownership to another editor.
  await page.getByRole('button', { name: `Organize ${wf.name}`, exact: true }).click()
  const organize = page.getByRole('dialog', { name: `Organize ${wf.name}` })
  await organize.getByLabel('Folder').selectOption({ label: folderName })
  const ownerSelect = organize.getByLabel('Owner')
  const ownerOptions = await ownerSelect.locator('option').evaluateAll((os) => os.map((o) => ({ value: (o as HTMLOptionElement).value, label: o.textContent ?? '' })))
  const current = await ownerSelect.inputValue()
  const other = ownerOptions.find((o) => o.value !== current)
  if (other) await ownerSelect.selectOption(other.value)
  await expectNoSeriousViolations(page, 'Organize dialog')
  await organize.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(organize).toHaveCount(0)
  const row = page.getByRole('row').filter({ hasText: wf.name })
  await expect(row).toContainText(folderName)
  if (other) await expect(row).toContainText(`Owner: ${other.label}`)

  // Folder filter lives in the URL and survives reload; Clear filters resets it.
  await page.getByLabel('Search workflows').fill('')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await page.getByLabel('Folder', { exact: true }).selectOption({ label: `${folderName} (1)` })
  await expect(page).toHaveURL(/folder=/)
  await expect(page.getByRole('row').filter({ hasText: wf.name })).toHaveCount(1)
  await page.reload()
  await expect(page.getByLabel('Folder', { exact: true })).toHaveValue(/.+/)
  await expect(page.getByRole('row').filter({ hasText: wf.name })).toHaveCount(1)
  await expectNoSeriousViolations(page, 'Workflows filtered by folder')
  const owner = page.getByLabel('Owner', { exact: true })
  await expect(owner).toBeVisible()
  if (other) {
    await owner.selectOption({ label: new RegExp(`^${other.label} \(1\)$`) as never }).catch(async () => owner.selectOption({ index: 1 }))
    await expect(page).toHaveURL(/owner=/)
    await expect(page.getByRole('row').filter({ hasText: wf.name })).toHaveCount(1)
    await owner.selectOption('')
  }
  await page.getByRole('button', { name: 'Clear filters' }).click()
  await expect(page).not.toHaveURL(/folder=/)

  // Rename, then delete: the workflow is kept and becomes unfiled.
  await page.getByText('Manage library', { exact: true }).click()
  await page.getByRole('button', { name: 'Manage folders' }).click()
  await manage.getByRole('button', { name: `Rename ${folderName}` }).click()
  await manage.getByLabel(`Rename ${folderName}`).fill(renamed)
  await manage.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(manage.getByText(renamed, { exact: true })).toBeVisible()
  page.once('dialog', (d) => void d.accept())
  await manage.getByRole('button', { name: `Delete ${renamed}` }).click()
  await expect(manage.getByText(renamed, { exact: true })).toHaveCount(0)
  await manage.getByRole('button', { name: 'Close', exact: true }).click()
  await page.getByLabel('Search workflows').fill(wf.name)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: wf.name })).toContainText('Unfiled')

  // Requester: no folder management or Organize.
  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/workflows`)
  await expect(requester.getByRole('heading', { name: 'Workflows', level: 1 })).toBeVisible()
  await expect(requester.getByRole('button', { name: 'Manage folders' })).toHaveCount(0)
  await expect(requester.getByRole('button', { name: /^Organize / })).toHaveCount(0)
})
