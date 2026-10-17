import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { ensureTemplatePublished, submitPurchase, workflowIdByKey, workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * EX04 (docs/build/S37): an operator suspends a waiting request with a reason, the requester sees it is paused without
 * the internal reason, and the operator resumes it. Keyboard-reachable dialogs and no serious accessibility findings.
 */
test('operator suspends and resumes a waiting request; the requester sees it is paused', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  if (!MOCK) await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
  const workflowId = await workflowIdByKey(requester, ws)
  const { runUrl } = await submitPurchase(requester, ws, workflowId, { title: `Suspend ${Date.now()}`, purpose: 'Hold while policy is reviewed', costCenter: 'OPS', amount: '1800' })
  const runId = /\/runs\/([^/?#]+)/.exec(runUrl)?.[1]
  expect(runId, `run id in ${runUrl}`).toBeTruthy()

  const operator = await sessions.as('olga.operator')
  await operator.goto(`/w/${ws}/runs/${runId}`)
  await operator.getByRole('button', { name: 'Suspend request', exact: true }).click()
  const dialog = operator.getByRole('dialog', { name: 'Suspend this request' })
  await dialog.getByRole('button', { name: 'Suspend request', exact: true }).click()
  await expect(dialog.getByText(/Add a reason/)).toBeVisible()
  await dialog.getByLabel(/Reason/).fill('Internal policy review')
  await expectNoSeriousViolations(operator, 'Suspend dialog')
  await dialog.getByRole('button', { name: 'Suspend request', exact: true }).click()
  await expect(operator.getByTestId('suspension-message')).toContainText('Internal policy review')
  await expect(operator.getByTestId('timeline')).toContainText('suspended the request: Internal policy review')
  await expect(operator.getByRole('button', { name: 'Suspend request', exact: true })).toHaveCount(0)

  // In mock mode one page serves every user, so each step names the user it acts as.
  await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/runs/${runId}`)
  await expect(requester.getByTestId('suspension-message')).toBeVisible()
  await expect(requester.getByTestId('suspension-message')).not.toContainText('Internal policy review')
  await expectNoSeriousViolations(requester, 'Suspended request, requester view')

  await sessions.as('olga.operator')
  await operator.goto(`/w/${ws}/runs/${runId}`)
  await operator.getByRole('button', { name: 'Resume request', exact: true }).click()
  const resume = operator.getByRole('dialog', { name: 'Resume this request' })
  await resume.getByLabel(/Reason/).fill('Review finished')
  await resume.getByRole('button', { name: 'Resume request', exact: true }).click()
  await expect(operator.getByTestId('suspension-message')).toHaveCount(0)
  await expect(operator.getByTestId('timeline')).toContainText('resumed the request: Review finished')
  await expect(operator.getByRole('button', { name: 'Suspend request', exact: true })).toBeVisible()
})
