import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, workspaceId } from '../support/app'
import { createWorkflow, editDraft, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'
import { createView, publishView, requestView, saveViewDraft } from '../support/views'

/**
 * UI02h (S114) reviewed version migration: a workflow draft pins version 1 of a request view, version 2 is published,
 * and the designer reviews every pin of the view and moves the workflow draft with a reason. The release is untouched
 * until a publisher publishes the migrated draft, which passes the release's own view-pin checks.
 */
test('a reviewed pin migration moves a workflow draft to a new view version and leaves the release to publication', async ({ sessions }) => {
  test.skip(MOCK, 'Mock workflows do not pin library views; the migration inventory needs the server')
  test.setTimeout(240_000)
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const workflow = await createWorkflow(page, ws, uniq('Pinned form'))
  const viewName = uniq('Migrating form')
  const viewId = await createView(page, ws, viewName, requestView('Purchase'))
  page = await sessions.as('paul.publisher')
  expect(await publishView(page, ws, viewId, 1)).toBe(1)
  await publish(page, ws, workflow.id)

  page = await sessions.as('dana.designer')
  await editDraft(page, ws, workflow.id, (definition) => {
    definition.inputUi = { ...(definition.inputUi ?? {}), viewRef: { id: viewId, version: 1 } }
  })
  await saveViewDraft(page, ws, viewId, 1, viewName, requestView('Purchase details'))
  page = await sessions.as('paul.publisher')
  expect(await publishView(page, ws, viewId, 2)).toBe(2)

  page = await sessions.as('dana.designer')
  await page.goto(`/w/${ws}/views/${viewId}`)
  const panel = page.getByRole('region', { name: 'Where this view is used' })
  await expect(panel.getByLabel('Version to move pins to')).toHaveValue('2')
  const pins = panel.getByRole('list', { name: 'Pins of this view' })
  const item = pins.getByRole('listitem').filter({ hasText: 'Workflow draft' }).filter({ hasText: workflow.name })
  await expect(item).toContainText('Workflow draft')
  await expect(item).toContainText('Request form')
  await expect(item).toContainText('Can update')
  await expect(panel.getByRole('button', { name: 'Select pins to update' })).toBeDisabled()
  await expectNoSeriousViolations(page, 'Pin review')

  await panel.getByRole('checkbox', { name: workflow.name }).click()
  await panel.getByRole('button', { name: 'Review update of 1 pin' }).click()
  const dialog = page.getByRole('dialog', { name: 'Move 1 pin to version 2' })
  await expect(dialog).toContainText('Publish a release to use it.')
  const update = dialog.getByRole('button', { name: 'Update 1 pin' })
  await expect(update).toBeDisabled()
  await dialog.getByLabel('Reason (recorded with the update)').fill('Adopt the clearer section title')
  await update.click()
  await expect(panel.getByText('Moved 1 pin to version 2')).toBeVisible()
  await expect(item).toContainText('Already on this version')
  await expect(panel.getByText(/Earlier updates \(1\)/)).toBeVisible()

  const draft = await apiGet<{ definition: { inputUi: { viewRef: { version: number }; layout: { sections: Array<{ title: string }> } } } }>(page, `/workspaces/${ws}/workflows/${workflow.id}/draft`)
  expect(draft.definition.inputUi.viewRef.version).toBe(2)
  expect(draft.definition.inputUi.layout.sections[0]!.title).toBe('Purchase details')
  const history = await apiGet<{ items: Array<{ targetVersion: number; reason: string }> }>(page, `/workspaces/${ws}/views/${viewId}/pin-migrations`)
  expect(history.items[0]).toMatchObject({ targetVersion: 2, reason: 'Adopt the clearer section title' })

  // The active release still has no pin until the migrated draft is published through the normal release flow.
  page = await sessions.as('paul.publisher')
  await publish(page, ws, workflow.id)
  page = await sessions.as('dana.designer')
  await page.reload()
  const release = pins.getByRole('listitem').filter({ hasText: 'Workflow release' }).filter({ hasText: workflow.name })
  await expect(release).toContainText('Fixed version')
  await expect(release).toContainText('pins version 2')
})
