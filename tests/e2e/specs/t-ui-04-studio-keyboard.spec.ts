import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { createPurchaseWorkflow, workspaceId } from '../support/app'
import type { Page } from '@playwright/test'

/**
 * T-UI-04 (FR03, WCAG 2.2 SC 2.5.7): change the finance threshold using only the keyboard through the Steps tab and
 * the inspector, save, reload, and undo. No pointer events are used after the page has loaded.
 */

/** Press Tab until the focused element matches `selector`. */
async function tabTo(page: Page, selector: string, maxTabs = 120): Promise<void> {
  for (let i = 0; i < maxTabs; i++) {
    const hit = await page.evaluate((sel) => !!document.activeElement?.matches(sel), selector)
    if (hit) return
    await page.keyboard.press('Tab')
  }
  throw new Error(`Could not reach ${selector} with the keyboard within ${maxTabs} Tab presses`)
}

async function openStepsWithKeyboard(page: Page): Promise<void> {
  await tabTo(page, '[role="tab"][aria-selected="true"]')
  await page.keyboard.press('ArrowRight') // Canvas -> Steps (tabs activate on arrow keys)
  await expect(page.getByRole('tab', { name: 'Steps' })).toHaveAttribute('aria-selected', 'true')
}

async function selectThresholdStep(page: Page): Promise<void> {
  await tabTo(page, '[data-testid="step-row-amountPolicy"] button[aria-pressed]')
  await page.keyboard.press('Enter')
  await expect(page.locator('[data-field="rows.0.when.value"]')).toBeVisible()
}

async function typeThreshold(page: Page, value: string): Promise<void> {
  await tabTo(page, '[data-field="rows.0.when.value"]')
  await page.keyboard.press('Control+a')
  await page.keyboard.type(value)
  await page.keyboard.press('Enter') // commits the field: one undo entry
  await expect(page.getByTestId('save-state')).toHaveText('Unsaved changes')
}

test('keyboard-only: change the finance threshold, save, reload, undo', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const workflowId = await createPurchaseWorkflow(designer, ws)
  await designer.goto(`/w/${ws}/workflows/${workflowId}/edit`)
  await expect(designer.getByTestId('studio')).toBeVisible()
  const threshold = designer.locator('[data-field="rows.0.when.value"]')

  // 1. Steps tab and inspector, keyboard only.
  await openStepsWithKeyboard(designer)
  await selectThresholdStep(designer)
  await expect(threshold).toHaveValue('5000')
  await typeThreshold(designer, '10000')

  // 2. Save with Ctrl+S, then reload and find the change still there.
  await designer.keyboard.press('Control+s')
  await expect(designer.getByTestId('save-state')).toHaveText(/Saved/)
  await designer.reload()
  await expect(designer.getByTestId('studio')).toBeVisible()
  await openStepsWithKeyboard(designer)
  await selectThresholdStep(designer)
  await expect(threshold).toHaveValue('10000')

  // 3. Change it again, then undo: the previous threshold and the routes come back.
  await typeThreshold(designer, '12000')
  await designer.keyboard.press('Control+z')
  await expect(threshold).toHaveValue('10000')
  await expect(designer.getByTestId('save-state')).toHaveText(/Saved/) // back at the saved document
  await expect(designer.locator('#route-route-t5')).toHaveValue('finance') // routes unchanged
  await expect(designer.locator('#route-route-t6')).toHaveValue('procure')

  // Redo with Ctrl+Shift+Z, undo again with Ctrl+Y's counterpart; history is plain keyboard.
  await designer.keyboard.press('Control+Shift+z')
  await expect(threshold).toHaveValue('12000')
  await designer.keyboard.press('Control+z')
  await expect(threshold).toHaveValue('10000')
})

test('Steps tab: add a step after another step and its routes follow', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const workflowId = await createPurchaseWorkflow(designer, ws)
  await designer.goto(`/w/${ws}/workflows/${workflowId}/edit`)
  await openStepsWithKeyboard(designer)

  // "Add step after..." on the manager approval, on the Approved route.
  await tabTo(designer, 'button[aria-label="Add step after Review request"]')
  await designer.keyboard.press('Enter')
  const dialog = designer.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('radio', { name: /Notify someone/ }).check()
  await dialog.getByRole('button', { name: 'Add step' }).click()
  await expect(dialog).toBeHidden()
  await expect(designer.getByTestId('save-state')).toHaveText('Unsaved changes')

  // The new step sits between "Review request" and "Check purchase amount".
  await expect(designer.locator('#route-manager-t2 option:checked')).toHaveText('Tell the requester')
  await designer.keyboard.press('Control+s')
  await expect(designer.getByTestId('save-state')).toHaveText(/Saved/)
})
