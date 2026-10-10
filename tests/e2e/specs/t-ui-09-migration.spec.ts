import { readFileSync } from 'node:fs'
import path from 'node:path'
import { expect, test } from '../support/fixtures'
import { apiGet, workspaceId } from '../support/app'
import { MOCK } from '../support/env'

const examples = path.resolve('../../examples')
test.skip(MOCK, 'Migration persistence and compiler validation require the real backend.')

test('Camunda source analysis, guided connector mapping, draft conversion and provenance', async ({ sessions }) => {
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/workflows`)
  await page.getByRole('link', { name: 'Import workflow' }).click()
  await page.getByLabel(/Source file/).setInputFiles(path.join(examples, 'migration/camunda7-purchase.bpmn'))
  await page.getByRole('button', { name: 'Analyze source' }).click()
  await expect(page.getByRole('region', { name: 'Compatibility report' })).toBeVisible()
  await page.getByLabel('Map source assignment: source-managers').selectOption('managers')
  await page.getByLabel('Map source assignment: source-finance').selectOption('finance-approvers')
  await page.getByLabel('Target connection').selectOption('procurement')
  await page.getByLabel('Target operation').selectOption('createOrder')
  await page.getByRole('button', { name: 'Apply operation mapping' }).click()
  await expect(page.getByLabel('Service task mappings (JSON)')).toHaveValue(/definitionVersion/)
  await page.getByLabel('Workflow key', { exact: true }).fill('browser-import-' + Date.now())
  await page.getByRole('checkbox', { name: /I reviewed the assumptions/ }).check()
  const sourceId = page.url().split('/').pop()!
  await page.getByRole('button', { name: 'Convert to draft' }).click()
  await expect(page.getByTestId('studio')).toBeVisible()
  await expect(page.getByText(/Imported from camunda7/)).toBeVisible()
  const job = await apiGet<{ status: string; convertedWorkflowId: string; sourceMap: Record<string, string> }>(page, `/workspaces/${ws}/migrations/${sourceId}`)
  expect(job.status).toBe('converted')
  expect(job.sourceMap.procure).toBe('procure')
  const workflow = await apiGet<{ activeRelease: unknown }>(page, `/workspaces/${ws}/workflows/${job.convertedWorkflowId}`)
  expect(workflow.activeRelease).toBeNull()
})

test('Pega archive inventory and a reviewed checksum-linked manual mapping create a draft', async ({ sessions }, testInfo) => {
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/migrations`)
  await page.getByLabel('Source format').selectOption('pegaArchive')
  await page.getByLabel(/Source file/).setInputFiles(path.join(examples, 'migration/pega-synthetic-archive.zip'))
  await page.getByRole('button', { name: 'Analyze source' }).click()
  await expect(page.getByText('Manual Pega mapping required')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Convert to draft' })).toHaveCount(0)
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download mapping package template' }).click()
  const download = await downloadPromise
  const file = testInfo.outputPath('pega-mapping-template.json')
  await download.saveAs(file)
  const pkg = JSON.parse(readFileSync(file, 'utf8')) as Record<string, unknown>
  const definition = JSON.parse(readFileSync(path.join(examples, 'purchase-approval.json'), 'utf8')) as { nodes: Array<{ id: string }> }
  pkg.sourceVersion = 'synthetic-test-version'
  pkg.definition = definition
  pkg.fileMappings = { 'rules/process.xml': { disposition: 'mapped', targetNodeIds: definition.nodes.map(n => n.id),
    explanation: 'Hand-authored behavior fixture; does not claim native Pega conversion.' } }
  await page.getByLabel('Source format').selectOption('pegaMapping')
  await page.getByLabel(/Source file/).setInputFiles({ name: 'pega-reviewed-mapping.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(pkg)) })
  await page.getByRole('button', { name: 'Analyze source' }).click()
  await expect(page.getByRole('button', { name: 'Convert to draft' })).toBeVisible()
  await page.getByLabel('Workflow key', { exact: true }).fill('pega-review-' + Date.now())
  await page.getByRole('checkbox', { name: /I reviewed the assumptions/ }).check()
  await page.getByRole('button', { name: 'Convert to draft' }).click()
  await expect(page.getByTestId('studio')).toBeVisible()
  await expect(page.getByText(/Imported from pega/)).toBeVisible()
})
