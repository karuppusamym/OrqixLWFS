import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { createWorkflow, publish, touchDraft, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * PL02 (docs/build/S54): a publisher starts and stops a gradual rollout on the workflow overview and the per-version
 * request figures are shown to people who can read runs; the environment-stage API is read by members. (Promotion has
 * no screen by design; it is covered through the API in the real-stack spec.)
 */
test('publisher starts and stops a canary rollout; readers see the status', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const wf = await createWorkflow(designer, ws, uniq('Canary flow'))
  const publisher = await sessions.as('paul.publisher')
  await publish(publisher, ws, wf.id)
  await sessions.as('dana.designer')
  await touchDraft(designer, ws, wf.id, 'second release')
  await sessions.as('paul.publisher')
  await publish(publisher, ws, wf.id)

  await publisher.goto(`/w/${ws}/workflows/${wf.id}`)
  const section = publisher.getByRole('region', { name: /Gradual rollout/ })
  await expect(section).toBeVisible()
  await expect(section.getByText('All new requests use the active version')).toBeVisible()
  await section.getByLabel('Version to try').selectOption({ index: 1 })
  await section.getByLabel('Share of new requests (%)').fill('60')
  await expect(section.getByRole('button', { name: 'Start rollout' })).toBeDisabled()
  await section.getByLabel('Share of new requests (%)').fill('25')
  await expectNoSeriousViolations(publisher, 'Rollout form')
  await section.getByRole('button', { name: 'Start rollout' }).click()
  await expect(section.getByTestId('canary-status')).toContainText('25%')
  await expect(section.getByTestId('canary-status')).toContainText('Existing requests are unchanged')
  await expectNoSeriousViolations(publisher, 'Rollout running with request figures')
  await publisher.reload()
  await expect(publisher.getByTestId('canary-status')).toContainText('25%')

  // Reader sees status without the stop control.
  const viewer = await sessions.as('vic.viewer')
  await viewer.goto(`/w/${ws}/workflows/${wf.id}`)
  await expect(viewer.getByTestId('canary-status')).toContainText('25%')
  await expect(viewer.getByRole('button', { name: 'Stop the rollout' })).toHaveCount(0)

  await sessions.as('paul.publisher')
  await publisher.goto(`/w/${ws}/workflows/${wf.id}`)
  await publisher.getByRole('button', { name: 'Stop the rollout' }).click()
  await expect(publisher.getByText('All new requests use the active version')).toBeVisible()
})
