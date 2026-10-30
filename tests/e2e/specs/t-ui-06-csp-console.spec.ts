import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { ensureTemplatePublished, workflowIdByKey, workspaceId } from '../support/app'

/**
 * T-UI-06: no CSP violations and no console errors or uncaught exceptions. The `sessions` fixture already enforces
 * this for every spec; this one deliberately touches the parts of the UI that tend to break a strict policy
 * (script-src 'self', style-src 'self'): menus and popovers, native dialogs, the canvas (React Flow), drag and drop,
 * schema-driven forms, downloads and the form validator (which must not use eval).
 */
test('a tour of menus, dialogs, the canvas and forms raises no CSP violation or console error', async ({ sessions }) => {
  test.setTimeout(240_000)
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: true })

  // Menus and popovers (Radix) -------------------------------------------------------------------------------------------
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  if (!MOCK) await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
  const workflowId = await workflowIdByKey(designer, ws)
  await designer.getByRole('button', { name: /Switch workspace/ }).click()
  await expect(designer.getByRole('menuitem').first()).toBeVisible()
  await designer.keyboard.press('Escape')
  await designer.getByRole('button', { name: /Notifications/ }).click()
  await expect(designer.getByRole('heading', { name: 'Notifications' })).toBeVisible()
  await designer.keyboard.press('Escape')
  await designer.getByRole('button', { name: /Account menu/ }).click()
  await expect(designer.getByRole('menuitem', { name: 'Sign out' })).toBeVisible()
  await designer.keyboard.press('Escape')

  // Canvas: render, select, drag a node, add from the palette, undo --------------------------------------------------------
  await designer.goto(`/w/${ws}/workflows/${workflowId}/edit`)
  await expect(designer.locator('.react-flow__node').first()).toBeVisible()
  const node = designer.locator('.react-flow__node[data-id="notify"]')
  await node.click()
  await expect(designer.getByTestId('inspector')).toBeVisible()
  const box = await node.boundingBox()
  if (!box) throw new Error('notify node has no box')
  await designer.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await designer.mouse.down()
  await designer.mouse.move(box.x + box.width / 2 + 40, box.y + box.height / 2 + 60, { steps: 8 })
  await designer.mouse.up()
  await expect(designer.getByTestId('save-state')).toHaveText(/Unsaved changes|Saving|Saved/)
  // Relative counts: earlier specs may already have saved extra steps into this shared draft on a real stack.
  const nodesBefore = await designer.locator('.react-flow__node').count()
  await designer.getByRole('button', { name: /Notify someone/ }).click()
  await expect(designer.locator('.react-flow__node')).toHaveCount(nodesBefore + 1)
  await designer.keyboard.press('Control+z')
  await expect(designer.locator('.react-flow__node')).toHaveCount(nodesBefore)
  await designer.keyboard.press('Control+s')
  await expect(designer.getByTestId('save-state')).toHaveText(/Saved/)

  // Native dialogs and the form validator ---------------------------------------------------------------------------------
  // The More menu's preview opens the Form tab's live preview (S120).
  await designer.getByRole('button', { name: 'More actions' }).click()
  await designer.getByRole('menuitem', { name: 'Preview the request form' }).click()
  const preview = designer.getByTestId('form-preview')
  await preview.getByLabel('Amount (USD)').fill('12.345')
  await preview.getByRole('button', { name: 'Check my answers' }).click()
  await expect(preview.locator('[data-field-error]').filter({ hasText: /two decimal places/ })).toBeVisible()
  await designer.getByRole('button', { name: 'Check workflow' }).click()
  await expect(designer.getByTestId('check-summary')).not.toContainText('Checking')

  // Export download (blob URL) as the publisher ------------------------------------------------------------------------------
  const publisher = await sessions.as('paul.publisher')
  await publisher.goto(`/w/${ws}/workflows/${workflowId}`)
  const download = publisher.waitForEvent('download')
  await publisher.getByRole('button', { name: /Export version/ }).first().click()
  expect((await download).suggestedFilename()).toMatch(/\.zip$/)

  // Requester form with a server-side and client-side error ---------------------------------------------------------------------
  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/start/${workflowId}`)
  await requester.getByLabel('Amount (USD)').fill('1800.071')
  await requester.getByRole('button', { name: 'Review request' }).click()
  await expect(requester.locator('[data-field-error]').filter({ hasText: /two decimal places/ })).toBeVisible()

  // Explicitly: nothing was blocked by the Content-Security-Policy in any open page.
  const problems = await sessions.collectProblems()
  expect(problems.filter((p) => p.kind === 'csp-violation')).toEqual([])
})
