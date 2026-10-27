import { expect, test } from '../support/fixtures'
import { expectNoSeriousViolations } from '../support/axe'
import { workflowIdByKey, workspaceId } from '../support/app'

/**
 * T-UI-07: the studio's single side panel (Details, Ask AI, Discussion) and the More menu on a 1280 px wide screen.
 * Opens the seeded purchase approval workflow; changes nothing. Serious or critical axe findings fail the test.
 */
test('studio side panel tabs and the More menu pass the accessibility scan at 1280 px', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  await designer.setViewportSize({ width: 1280, height: 800 })
  const ws = await workspaceId(designer)
  const workflowId = await workflowIdByKey(designer, ws)
  await designer.goto(`/w/${ws}/workflows/${workflowId}/edit`)
  await expect(designer.getByTestId('studio')).toBeVisible()
  const panel = designer.getByRole('complementary', { name: 'Side panel' })
  await expect(panel).toBeVisible()

  await expectNoSeriousViolations(designer, 'studio details tab')

  await panel.getByRole('tab', { name: 'Ask AI' }).click()
  await expect(designer.getByTestId('authoring-panel')).toBeVisible()
  await expectNoSeriousViolations(designer, 'studio Ask AI tab')

  await panel.getByRole('tab', { name: 'Discussion' }).click()
  await expectNoSeriousViolations(designer, 'studio discussion tab')

  await designer.getByRole('button', { name: 'More actions' }).click()
  await expect(designer.getByRole('menuitem', { name: /Download BPMN/ })).toBeVisible()
  await expectNoSeriousViolations(designer, 'studio More menu')
})
