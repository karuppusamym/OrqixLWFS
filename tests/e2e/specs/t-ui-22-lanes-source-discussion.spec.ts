import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { selectStep, workspaceId } from '../support/app'
import { createWorkflow, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * UI03 (docs/build/S53): the Lanes view groups steps by who does them and lists handoffs; the Source view shows the
 * draft as YAML, refuses invalid edits with line numbers and applies valid ones; each step has a threaded discussion
 * that can be replied to and resolved. A viewer can read but not post.
 */
test('lanes, YAML source and step discussion in the studio', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const wf = await createWorkflow(page, ws, uniq('Lanes flow'))
  await page.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await expect(page.getByRole('tab', { name: 'Steps' })).toBeVisible()

  // Lanes
  await page.getByRole('tab', { name: 'Lanes' }).click()
  const lanes = page.getByTestId('lanes')
  await expect(lanes).toBeVisible()
  await expect(lanes.getByRole('list', { name: 'Swimlanes' })).toBeVisible()
  expect(await lanes.getByRole('listitem').count()).toBeGreaterThan(1)
  await expect(page.getByTestId('handoffs')).toBeVisible()
  await expectNoSeriousViolations(page, 'Lanes view')

  // Source: round trip, invalid edit refused, valid edit applied.
  await page.getByRole('tab', { name: 'Source' }).click()
  const text = page.getByLabel('Workflow source (YAML)')
  await expect(page.getByTestId('draft-roundtrip')).toContainText('without any difference')
  const original = await text.inputValue()
  expect(original.length).toBeGreaterThan(100)
  await expectNoSeriousViolations(page, 'Source view')
  await text.fill(original + '\n  : : not valid yaml [')
  await page.getByRole('button', { name: 'Check source' }).click()
  await expect(page.getByTestId('source-problems')).toBeVisible()
  await expect(page.getByTestId('source-problems')).toContainText(/Line \d+|problem/)
  await expectNoSeriousViolations(page, 'Source view with problems')
  await page.getByRole('button', { name: 'Discard edits' }).click()
  await expect(text).toHaveValue(original)
  const nameLine = /^(\s*name:\s*)(.+)$/m.exec(original)
  expect(nameLine, 'YAML has a name: line').not.toBeNull()
  await text.fill(original.replace(/^(\s*name:\s*)(.+)$/m, `$1${wf.name} (edited)`))
  await page.getByRole('button', { name: 'Check source' }).click()
  await expect(page.getByTestId('source-ok')).toContainText('The source is valid')
  await page.getByRole('button', { name: 'Apply to draft' }).click()
  await expect(page.getByTestId('source-ok')).toHaveCount(0)

  // Discussion on a step: comment, reply, resolve, reopen.
  await page.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(page, 'start')
  const discussion = page.getByTestId('discussion')
  await expect(discussion).toBeVisible()
  const note = uniq('Is this field needed')
  await discussion.getByLabel('New comment').fill(note)
  await discussion.getByRole('button', { name: 'Comment', exact: true }).click()
  const thread = discussion.getByTestId('thread').filter({ hasText: note })
  await expect(thread).toBeVisible()
  await thread.getByRole('button', { name: 'Reply' }).click()
  await thread.getByLabel('Your reply').fill('Yes, finance asked for it')
  await expectNoSeriousViolations(page, 'Discussion with reply box')
  await thread.getByRole('button', { name: 'Post reply' }).click()
  await expect(thread).toContainText('Yes, finance asked for it')
  await thread.getByRole('button', { name: /^Resolve/ }).click()
  await expect(thread.getByRole('button', { name: /^Reopen/ })).toBeVisible()

  // Viewer: reads the discussion, cannot post.
  const viewer = await sessions.as('vic.viewer')
  await viewer.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await viewer.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(viewer, 'start')
  await expect(viewer.getByTestId('discussion')).toContainText(note)
  await expect(viewer.getByTestId('discussion').getByLabel('New comment')).toHaveCount(0)
})
