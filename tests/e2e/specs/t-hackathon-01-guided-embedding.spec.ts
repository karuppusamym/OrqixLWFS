import { expect, test } from '../support/fixtures'
import { workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'
import { MOCK } from '../support/env'

test('guided Tier-1 authoring collects missing answers and saves only a reviewable draft', async ({ sessions }) => {
  test.skip(MOCK, 'Qualifies the real application and compiler, without a model provider.')
  const page = await sessions.as('dana.designer'); const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/workflows`)
  await page.getByRole('button', { name: 'New workflow', exact: true }).click()
  await page.getByRole('button', { name: 'Create with guided questions', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Create with guided questions' })
  await dialog.getByRole('button', { name: 'Next', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(dialog.getByRole('alert')).toContainText('name and explain')
  const name = `Guided demo ${Date.now()}`
  await dialog.getByLabel('What is the workflow called?', { exact: false }).fill(name)
  await dialog.getByLabel('What should it accomplish?', { exact: false }).fill('Synthetic review; no customer policy or compliance claim.')
  await dialog.getByRole('button', { name: 'Next', exact: true }).click()
  await dialog.getByLabel('Who approves?').selectOption('managers')
  await dialog.getByRole('button', { name: 'Next', exact: true }).click()
  await expectNoSeriousViolations(page, 'Guided calendar configuration')
  await page.setViewportSize({ width: 375, height: 812 })
  await expectNoSeriousViolations(page, 'Guided calendar on mobile')
  await dialog.getByRole('button', { name: 'Next', exact: true }).click()
  await dialog.getByRole('button', { name: 'Next', exact: true }).click()
  await dialog.getByLabel('Business process owner').fill('Synthetic demo owner')
  await dialog.getByRole('checkbox', { name: /I have reviewed/ }).check()
  await expect(dialog).toContainText('does not publish or execute')
  await dialog.getByRole('button', { name: 'Create reviewable draft' }).click()
  await expect(page).toHaveURL(new RegExp(`/w/${ws}/workflows/[^/]+/edit$`))
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
  const id = /workflows\/([^/]+)\/edit/.exec(page.url())![1]
  const response = await page.request.get(`/api/v1/workspaces/${ws}/workflows/${id}/draft`)
  expect(response.ok()).toBe(true)
  const draft = await response.json()
  expect(draft.definition.acceptanceProfile).toBe('tier1')
  expect(draft.definition.tier1Assessment.requirementsReviewed).toBe(true)
  expect(draft.definition.nodes[1].config.notifications.overdue.recipients).toEqual(['operators'])
  const workflow = await (await page.request.get(`/api/v1/workspaces/${ws}/workflows/${id}`)).json()
  expect(workflow.activeRelease).toBeNull()
})

test('independent authoring and task artifacts mount in the authenticated host', async ({ sessions }) => {
  test.skip(MOCK, 'Requires the independently built artifacts packaged with the real application.')
  const designer = await sessions.as('dana.designer'); const ws = await workspaceId(designer)
  await designer.goto(`/embed-host/index.html?component=authoring&workspace=${ws}`)
  await expect(designer.getByRole('heading', { name: 'Workflows', exact: true })).toBeVisible()
  await expectNoSeriousViolations(designer, 'Embedded authoring host')
  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/embed-host/index.html?component=tasks&workspace=${ws}`)
  await expect(requester.getByRole('heading', { name: 'My tasks', exact: true })).toBeVisible()
  await expectNoSeriousViolations(requester, 'Embedded task host')
})
