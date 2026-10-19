import { execFileSync } from 'node:child_process'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { ensureTemplatePublished, submitPurchase, workflowIdByKey, workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * EX09 (docs/build/S49): an operator compares a request with the workflow engine. A healthy request reports "Everything
 * matches" and offers no repair. On the real stack the approval projection of a waiting request is cleared directly in
 * the verification database (E2E_DB_CONTAINER names its PostgreSQL container); the dialog then offers a guarded rebuild
 * that needs a reason, restores the approval step and records the repair. A requester has no such control.
 */
const DB_CONTAINER = process.env.E2E_DB_CONTAINER

test('operator checks a request and rebuilds a lost approval projection', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  if (!MOCK) await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
  const workflowId = await workflowIdByKey(requester, ws)
  const { runUrl } = await submitPurchase(requester, ws, workflowId, { title: `Repair ${Date.now()}`, purpose: 'Consistency check', costCenter: 'OPS', amount: '1200' })
  const runId = /\/runs\/([^/?#]+)/.exec(runUrl)?.[1]
  expect(runId).toBeTruthy()

  await expect(requester.getByRole('button', { name: 'Check consistency' })).toHaveCount(0)

  const operator = await sessions.as('olga.operator')
  await operator.goto(`/w/${ws}/runs/${runId}`)
  await operator.getByRole('button', { name: 'Check consistency' }).click()
  const dialog = operator.getByRole('dialog', { name: 'Check this request' })
  await expect(dialog.getByText('Everything matches')).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'Rebuild steps' })).toHaveCount(0)
  await expectNoSeriousViolations(operator, 'Consistency check, in sync')
  await dialog.getByRole('button', { name: 'Close', exact: true }).click()

  if (MOCK || !DB_CONTAINER) return // drift needs direct database access on the real stack

  execFileSync('docker', ['exec', DB_CONTAINER, 'psql', '-U', 'postgres', '-d', 'orqix', '-v', 'ON_ERROR_STOP=1', '-c',
    `UPDATE orqix.workflow_run SET current_steps='[]'::jsonb, updated_at=now()-interval '10 minutes' WHERE id='${runId}'`])
  await operator.reload()
  await operator.getByRole('button', { name: 'Check consistency' }).click()
  const drift = operator.getByRole('dialog', { name: 'Check this request' })
  await expect(drift.getByText('Open steps are missing from this request')).toBeVisible()
  await expectNoSeriousViolations(operator, 'Consistency check, drift found')
  await drift.getByRole('button', { name: 'Rebuild steps' }).click()
  await expect(drift.getByText(/Add a reason/)).toBeVisible()
  await drift.getByLabel(/Reason/).fill('Projection lost in a test')
  await drift.getByRole('button', { name: 'Rebuild steps' }).click()
  await expect(drift).toHaveCount(0)
  await expect(operator.getByTestId('timeline')).toContainText(/rebuilt|repair/i)
  await operator.getByRole('button', { name: 'Check consistency' }).click()
  await expect(operator.getByRole('dialog', { name: 'Check this request' }).getByText('Everything matches')).toBeVisible()
})
