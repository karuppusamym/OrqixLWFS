import { expect, test } from '../support/fixtures'
import { apiGet, createPurchaseWorkflow, workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'
import { MOCK } from '../support/env'

test('HTTP failure routes survive editing, reload and canvas rendering, and accept an inserted step', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const workflow = await createPurchaseWorkflow(designer, ws)
  await designer.goto(`/w/${ws}/workflows/${workflow}/edit`)
  await expect(designer.getByTestId('studio')).toBeVisible()
  await designer.getByRole('tab', { name: 'Steps', exact: true }).click()
  const row = designer.getByTestId('step-row-procure')
  const successTarget = await row.getByLabel('Next', { exact: true }).inputValue()
  await expect(row.getByLabel('When failed', { exact: true })).toHaveValue('')
  await row.getByLabel('When failed', { exact: true }).selectOption('rejected')
  await expect(designer.getByTestId('save-state')).toHaveText(/^Saved/)
  await designer.reload()
  await designer.getByRole('tab', { name: 'Steps', exact: true }).click()
  await expect(row.getByLabel('When failed', { exact: true })).toHaveValue('rejected')
  await expect(row.getByLabel('Next', { exact: true })).toHaveValue(successTarget)
  type Draft = { definition: { transitions: Array<{ id: string; from: string; to: string; on: string }> } }
  const draft = await apiGet<Draft>(designer, `/workspaces/${ws}/workflows/${workflow}/draft`)
  const failure = draft.definition.transitions.find(route => route.from === 'procure' && route.on === 'failed')!
  expect(failure.to).toBe('rejected')
  await designer.getByRole('tab', { name: 'Canvas', exact: true }).click()
  await designer.getByRole('button', { name: 'Fit View', exact: true }).click()
  const card = designer.locator('.react-flow__node[data-id="procure"]')
  await expect(card.locator('[data-handleid="failed"]')).toBeAttached()
  // Cards print "Failed" beside the step's failure handle; BPMN shapes (the default since STU01) print it on the route.
  await expect(designer.locator('.react-flow').getByText('Failed', { exact: true }).first()).toBeVisible()
  const edge = designer.locator(`.react-flow__edge[data-id="${failure.id}"]`)
  await expect(edge).toBeAttached()
  await expect.poll(() => edge.locator('.react-flow__edge-path').evaluate(element => {
    const path = element as SVGPathElement
    const first = path.getPointAtLength(0)
    const transform = path.getScreenCTM()
    if (!transform) throw new Error('The failure edge is not rendered')
    const point = new DOMPoint(first.x, first.y).matrixTransform(transform)
    const handle = document.querySelector('.react-flow__node[data-id="procure"] [data-handleid="failed"]')
    if (!handle) throw new Error('The failure handle is not rendered')
    const box = handle.getBoundingClientRect()
    return Math.hypot(point.x - box.x - box.width / 2, point.y - box.y - box.height / 2)
  })).toBeLessThan(12)
  const stroke = await edge.locator('.react-flow__edge-path').evaluate(element => getComputedStyle(element).stroke)
  expect(stroke).not.toBe('none')
  await designer.screenshot({ path: '../../evidence/http-failure-authoring-canvas.png' })
  await expectNoSeriousViolations(designer, 'HTTP failure route on the workflow canvas')
  await designer.getByRole('tab', { name: 'Steps', exact: true }).click()
  await row.getByRole('button', { name: /^Add step after/ }).click()
  const dialog = designer.getByRole('dialog', { name: /Add a step after/ })
  await dialog.getByLabel('Where?', { exact: true }).selectOption({ label: 'When failed' })
  await dialog.getByRole('radio', { name: /^Notify someone/ }).check()
  await dialog.getByRole('button', { name: 'Add step', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(designer.getByTestId('save-state')).toHaveText(/^Saved/)
  const updated = await apiGet<Draft>(designer, `/workspaces/${ws}/workflows/${workflow}/draft`)
  const inserted = updated.definition.transitions.find(route => route.from === 'procure' && route.on === 'failed')!
  expect(inserted.to).not.toBe('rejected')
  expect(updated.definition.transitions).toEqual(expect.arrayContaining([
    expect.objectContaining({ from: inserted.to, to: 'rejected', on: 'next' }),
    expect.objectContaining({ from: 'procure', to: successTarget, on: 'next' }),
  ]))
})
