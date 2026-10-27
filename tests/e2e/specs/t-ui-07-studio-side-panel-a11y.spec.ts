import { expect, test } from '../support/fixtures'
import { expectNoSeriousViolations } from '../support/axe'
import { workflowIdByKey, workspaceId } from '../support/app'

/**
 * T-UI-07: the studio's single side panel (Details, Ask AI, Discussion) and the More menu on a 1280 px wide screen.
 * Opens the seeded purchase approval workflow; changes nothing. Serious or critical axe findings fail the test.
 */
test('studio panes, View and More menus, Ask Orqix and the Assistant pass the accessibility scan at 1280 px', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  await designer.setViewportSize({ width: 1280, height: 800 })
  const ws = await workspaceId(designer)
  const workflowId = await workflowIdByKey(designer, ws)
  await designer.goto(`/w/${ws}/workflows/${workflowId}/edit`)
  await expect(designer.getByTestId('studio')).toBeVisible()
  const panel = designer.getByRole('complementary', { name: 'Details and discussion' })
  await expect(panel).toBeVisible()
  await expect(designer.getByTestId('ask-dock')).toBeVisible()

  await expectNoSeriousViolations(designer, 'studio with the dock and the cards')

  await panel.getByRole('button', { name: 'Discussion' }).click()
  await expectNoSeriousViolations(designer, 'studio with the discussion card open')

  await designer.getByRole('button', { name: /View: show or hide panels/ }).click()
  await expect(designer.getByRole('menuitemcheckbox', { name: 'Ask AI' })).toBeVisible()
  await expectNoSeriousViolations(designer, 'studio View menu')
  await designer.keyboard.press('Escape')

  await designer.getByRole('button', { name: 'More actions' }).click()
  await expect(designer.getByRole('menuitem', { name: /Download BPMN/ })).toBeVisible()
  await expectNoSeriousViolations(designer, 'studio More menu')

  await designer.goto(`/w/${ws}/assistant`)
  await expect(designer.getByRole('complementary', { name: 'About this conversation' })).toBeVisible()
  await expectNoSeriousViolations(designer, 'assistant page')

  await designer.keyboard.press('Control+k')
  await expect(designer.getByRole('dialog', { name: 'Ask Orqix' })).toBeVisible()
  await expectNoSeriousViolations(designer, 'Ask Orqix dialog')
})
