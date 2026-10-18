import path from 'node:path'
import { expect, test } from '../support/fixtures'
import { apiGet, workspaceId } from '../support/app'
import { uniq } from '../support/api'
import { MOCK } from '../support/env'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * EX07 (docs/build/S46): a Camunda 8 export using the broader bounded FEEL conditions (literal on the left, ranges,
 * "in" lists, not()) converts to a draft; a source with unsupported expressions shows its blockers and offers no
 * conversion. Real backend only.
 */
test.skip(MOCK, 'Importer behaviour needs the real backend.')
const examples = path.resolve('../../examples/migration')

test('extended FEEL conditions convert; unsupported expressions are reported and block conversion', async ({ sessions }) => {
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/migrations`)
  await page.getByLabel('Source format').selectOption('bpmn')
  await page.getByLabel(/Source file/).setInputFiles(path.join(examples, 'expression-feel-extended.bpmn'))
  await page.getByRole('button', { name: 'Analyze source' }).click()
  await expect(page.getByRole('region', { name: 'Compatibility report' })).toBeVisible()
  await expectNoSeriousViolations(page, 'FEEL extended compatibility report')
  await page.getByLabel('Workflow key', { exact: true }).fill(uniq('feel').toLowerCase().replace(/[^a-z0-9-]/g, ''))
  const acks = page.getByRole('checkbox')
  for (let i = 0; i < await acks.count(); i++) await acks.nth(i).check()
  const sourceId = page.url().split('/').pop()!
  await page.getByRole('button', { name: 'Convert to draft' }).click()
  await expect(page.getByTestId('studio')).toBeVisible({ timeout: 30_000 })
  const job = await apiGet<{ status: string; convertedWorkflowId: string }>(page, `/workspaces/${ws}/migrations/${sourceId}`)
  expect(job.status).toBe('converted')
  const wf = await apiGet<{ activeRelease: unknown }>(page, `/workspaces/${ws}/workflows/${job.convertedWorkflowId}`)
  expect(wf.activeRelease).toBeNull()

  await page.goto(`/w/${ws}/migrations`)
  await page.getByLabel('Source format').selectOption('bpmn')
  await page.getByLabel(/Source file/).setInputFiles(path.join(examples, 'camunda8-unsupported.bpmn'))
  await page.getByRole('button', { name: 'Analyze source' }).click()
  const report = page.getByRole('region', { name: 'Compatibility report' })
  await expect(report).toBeVisible()
  await expect(report).toContainText(/not supported|blocker|cannot be converted/i)
  await page.getByLabel('Workflow key', { exact: true }).fill(uniq('feelbad').toLowerCase().replace(/[^a-z0-9-]/g, ''))
  const boxes = page.getByRole('checkbox')
  for (let i = 0; i < await boxes.count(); i++) await boxes.nth(i).check()
  sessions.allowHttpErrors() // the server refuses the conversion with 422 on purpose
  await page.getByRole('button', { name: 'Convert to draft' }).click()
  await expect(page.getByRole('alert').first()).toBeVisible()
  await expect(page.getByTestId('studio')).toHaveCount(0)
  await expectNoSeriousViolations(page, 'FEEL unsupported compatibility report')
})
