import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * DOC08 (docs/build/S52): designers keep versioned document templates (create, add a version, archive, restore);
 * invalid placeholders are refused; an administrator sets and clears the file retention period. A viewer can read
 * templates but not change them.
 */
test('document templates are versioned in settings and retention is set by an administrator', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  await designer.goto(`/w/${ws}/settings`)
  await designer.getByRole('tab', { name: 'Document templates' }).click()
  await expect(designer.getByRole('heading', { name: 'Document templates', level: 2 })).toBeVisible()
  const name = uniq('Offer letter')
  await designer.getByRole('button', { name: 'New template' }).click()
  const form = designer.getByRole('form', { name: 'New document template' })
  await form.getByLabel(/Template name/).fill(name)
  await form.getByLabel(/Template text/).fill('Dear {{customer}}, your offer is {{amount}}.')
  await expectNoSeriousViolations(designer, 'New template form')
  await form.getByRole('button', { name: 'Create template' }).click()
  const row = designer.getByTestId('template-row').filter({ hasText: name })
  await expect(row).toContainText('Version 1')
  await expect(row).toContainText('2 fields')
  const detail = row.getByTestId('template-detail')
  await expect(detail.getByTestId('template-version')).toHaveCount(1)

  // A new version keeps the earlier one.
  const next = designer.getByRole('form', { name: `New version of ${name}` })
  await expect(next.getByRole('button', { name: /Save as version 2/ })).toBeDisabled()
  await next.getByLabel(/Template text/).fill('Dear {{customer}}, your offer of {{amount}} stands.')
  await next.getByLabel(/What changed/).fill('Softer wording')
  await next.getByRole('button', { name: 'Save as version 2' }).click()
  await expect(detail.getByTestId('template-version')).toHaveCount(2)
  await expect(row).toContainText('Version 2')
  await expectNoSeriousViolations(designer, 'Template with two versions')

  // Unbalanced placeholders are refused with a message, not saved.
  sessions.allowHttpErrors() // the unbalanced placeholder is refused with 422 on purpose
  await next.getByLabel(/Template text/).fill('Dear {{customer')
  await next.getByRole('button', { name: /Save as version 3/ }).click()
  await expect(detail.getByRole('alert').or(detail.getByText(/placeholder/i)).first()).toBeVisible()
  await expect(detail.getByTestId('template-version')).toHaveCount(2)

  // Archive hides it unless "Show archived"; restore brings it back.
  await row.getByRole('button', { name: 'Archive template' }).click()
  await expect(designer.getByTestId('template-row').filter({ hasText: name })).toHaveCount(0)
  await designer.getByLabel('Show archived').check()
  const archived = designer.getByTestId('template-row').filter({ hasText: name })
  await expect(archived).toContainText('Archived')
  await archived.getByRole('button', { name: 'Restore template' }).click()
  await designer.getByLabel('Show archived').uncheck()
  await expect(designer.getByTestId('template-row').filter({ hasText: name })).toBeVisible()

  // Viewer reads, cannot edit. (Mock mode keeps templates and retention in module memory that a page load resets, so a
  // second persona cannot see them; the permission split is proven on the real stack and by DocumentLifecycleTest.)
  if (MOCK) return
  const viewer = await sessions.as('vic.viewer')
  await viewer.goto(`/w/${ws}/settings`)
  await viewer.getByRole('tab', { name: 'Document templates' }).click()
  const vrow = viewer.getByTestId('template-row').filter({ hasText: name })
  await expect(vrow).toBeVisible()
  await expect(viewer.getByRole('button', { name: 'New template' })).toHaveCount(0)
  await vrow.getByRole('button', { name: new RegExp(name) }).click()
  await expect(vrow.getByTestId('template-detail')).toBeVisible()
  await expect(vrow.getByRole('button', { name: 'Archive template' })).toHaveCount(0)
})

test('administrator sets and clears the file retention period', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  const admin = await sessions.as('wade.admin')
  const ws = await workspaceId(admin)
  await admin.goto(`/w/${ws}/settings`)
  await admin.getByRole('tab', { name: 'File retention' }).click()
  await expect(admin.getByRole('heading', { name: 'File retention', level: 2 })).toBeVisible()
  const days = admin.getByLabel('Days to keep files after a request ends')
  await days.fill('0')
  await admin.getByRole('button', { name: 'Save retention' }).click()
  await expect(admin.getByText(/whole number of days from 1 to 3650/)).toBeVisible()
  await expectNoSeriousViolations(admin, 'Retention with validation error')
  await days.fill('90')
  await admin.getByRole('button', { name: 'Save retention' }).click()
  await expect(admin.getByTestId('retention-eligible')).toContainText('past this period')
  if (!MOCK) {
    await admin.reload()
    await admin.getByRole('tab', { name: 'File retention' }).click()
    await expect(admin.getByLabel('Days to keep files after a request ends')).toHaveValue('90')
  }
  await admin.getByLabel('Days to keep files after a request ends').fill('')
  await admin.getByRole('button', { name: 'Save retention' }).click()
  await expect(admin.getByTestId('retention-eligible')).toHaveCount(0)
  await expectNoSeriousViolations(admin, 'Retention cleared')
})
