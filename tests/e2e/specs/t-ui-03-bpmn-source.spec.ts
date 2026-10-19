import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { createWorkflow, editDraft, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * UI03 BPMN source (docs/build/S60): the Source tab shows the draft as BPMN. On an editable draft an edit is checked
 * (a foreign attribute is refused with its reason, a rename is accepted) and applied as the next revision; on a draft that
 * does not round-trip (separation of duties set) the text is read-only and editing is explained as unavailable. A viewer
 * reads but cannot edit. Real stack only: the mock writes a simplified BPMN (S60 "Implementation").
 */
test.skip(MOCK, 'The mock BPMN double is much simpler than the compiler (docs/build/S60).')

test('BPMN source: view, refuse a bad edit, apply a rename, and read-only on a draft that does not round-trip', async ({ sessions }) => {
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const wf = await createWorkflow(page, ws, uniq('Bpmn flow'))
  await page.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await page.getByRole('tab', { name: 'Source' }).click()
  await page.getByRole('button', { name: 'BPMN', exact: true }).click()
  const text = page.getByLabel('Workflow source (BPMN)')
  await expect(page.getByTestId('bpmn-roundtrip')).toContainText('without any difference')
  const original = await text.inputValue()
  expect(original).toContain('<process')
  await expect(text).toBeEditable()
  await expectNoSeriousViolations(page, 'BPMN source, editable draft')

  // A foreign attribute cannot be represented.
  await text.fill(original.replace('<process ', '<process xmlns:foo="urn:foo" foo:bar="1" '))
  await page.getByRole('button', { name: 'Check BPMN' }).click()
  await expect(page.getByTestId('bpmn-problems')).toBeVisible()
  await expectNoSeriousViolations(page, 'BPMN source, refused edit')
  await page.getByRole('button', { name: 'Discard edits' }).click()
  await expect(text).toHaveValue(original)

  // A rename is accepted and applied.
  const renamed = `${wf.name} renamed`
  const edited = original.replace(new RegExp(`(<process [^>]*name=")[^"]*"`), `$1${renamed}"`)
  expect(edited).not.toEqual(original)
  await text.fill(edited)
  await page.getByRole('button', { name: 'Check BPMN' }).click()
  await expect(page.getByTestId('bpmn-ok')).toBeVisible()
  await expectNoSeriousViolations(page, 'BPMN source, edit that can be applied')
  await page.getByRole('button', { name: 'Apply BPMN to draft' }).click()
  await expect(page.getByTestId('bpmn-ok')).toHaveCount(0)
  await expect(text).toHaveValue(new RegExp(`name="${renamed}"`))

  // A draft with separation of duties does not round-trip: read-only and explained.
  const ro = await createWorkflow(page, ws, uniq('Bpmn readonly'))
  await editDraft(page, ws, ro.id, (d) => {
    const finance = d.nodes.find((n: { id: string }) => n.id === 'finance')
    finance.config.separateFrom = ['manager']
  })
  await page.goto(`/w/${ws}/workflows/${ro.id}/edit`)
  await page.getByRole('tab', { name: 'Source' }).click()
  await page.getByRole('button', { name: 'BPMN', exact: true }).click()
  await expect(page.getByTestId('bpmn-roundtrip')).toContainText('BPMN editing is not available for this draft')
  await expect(page.getByLabel('Workflow source (BPMN)')).not.toBeEditable()
  await expect(page.getByRole('button', { name: 'Apply BPMN to draft' })).toHaveCount(0)
  await expectNoSeriousViolations(page, 'BPMN source, read-only draft')

  // A viewer reads the editable draft's BPMN and cannot edit it.
  const viewer = await sessions.as('vic.viewer')
  await viewer.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await viewer.getByRole('tab', { name: 'Source' }).click()
  await viewer.getByRole('button', { name: 'BPMN', exact: true }).click()
  await expect(viewer.getByLabel('Workflow source (BPMN)')).not.toBeEditable()
  await expect(viewer.getByRole('button', { name: 'Apply BPMN to draft' })).toHaveCount(0)
})
