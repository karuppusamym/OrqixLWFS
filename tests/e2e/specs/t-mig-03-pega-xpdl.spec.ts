import path from 'node:path'
import { expect, test } from '../support/fixtures'
import { apiGet, workspaceId } from '../support/app'
import { uniq } from '../support/api'
import { MOCK } from '../support/env'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * Pega XPDL 2.0 flow-export conversion (docs/build/S50): upload, compatibility report, acknowledgement of retained
 * metadata, conversion to a draft that is not published, and refusal of an unsupported export. Real backend only; the
 * mock backend deliberately rejects Pega conversion.
 */
test.skip(MOCK, 'The mock backend rejects Pega conversion by design (docs/build/S50).')
const xpdl = path.resolve('../../examples/migration/pega-xpdl')

test('medium XPDL export is analysed, converted to an unpublished draft and keeps provenance', async ({ sessions }) => {
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/migrations`)
  await page.getByLabel('Source format').selectOption('pegaXpdl')
  await page.getByLabel(/Source file/).setInputFiles(path.join(xpdl, 'medium.xpdl'))
  await page.getByRole('button', { name: 'Analyze source' }).click()
  await expect(page.getByRole('region', { name: 'Compatibility report' })).toBeVisible()
  await expectNoSeriousViolations(page, 'XPDL compatibility report')
  await page.getByLabel('Workflow key', { exact: true }).fill(uniq('xpdl').toLowerCase().replace(/[^a-z0-9-]/g, ''))
  const acks = page.getByRole('checkbox')
  for (let i = 0; i < await acks.count(); i++) await acks.nth(i).check()
  await expectNoSeriousViolations(page, 'XPDL review form')
  const sourceId = page.url().split('/').pop()!
  await page.getByRole('button', { name: 'Convert to draft' }).click()
  await expect(page.getByTestId('studio')).toBeVisible({ timeout: 30_000 })
  const job = await apiGet<{ status: string; convertedWorkflowId: string; report: { adapterVersion: string } }>(page, `/workspaces/${ws}/migrations/${sourceId}`)
  expect(job.status).toBe('converted')
  expect(job.report.adapterVersion).toBe('pega-xpdl-0.1')
  const workflow = await apiGet<{ activeRelease: unknown }>(page, `/workspaces/${ws}/workflows/${job.convertedWorkflowId}`)
  expect(workflow.activeRelease).toBeNull()
})

test('a requester cannot import a Pega export', async ({ sessions }) => {
  const page = await sessions.as('rhea.requester')
  const ws = await workspaceId(page)
  sessions.allowHttpErrors()
  await page.goto(`/w/${ws}/migrations`)
  await expect(page.getByLabel('Source format')).toHaveCount(0)
})
