import { readFileSync } from 'node:fs'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { addCaseField, workspaceId } from '../support/app'
import { apiSend, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'
import { checkAndPublish, newCaseType, useCmmnFile } from '../support/caseDesigner'

test('a pinned case view controls start and correction layout without granting edit authority', async ({ sessions }) => {
  test.skip(MOCK, 'Requires real case definition snapshots and current case-data permissions.')
  test.setTimeout(180_000)
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page), key = uniq('case-view')
  const sourceXml = readFileSync(new URL('../../../backend/app/src/test/resources/cases/manual-required.cmmn', import.meta.url), 'utf8').replaceAll('lifecycleCase', key)
  const view = { schemaVersion: 1, kind: 'case', sections: [{ id: 'review', label: 'Shared evidence details', columns: 1, fields: [
    { id: 'summary', label: 'Review summary', type: 'text', binding: 'case.summary', required: true },
    { id: 'checked', label: 'Evidence checked', type: 'boolean', binding: 'case.checked' },
  ] }], actions: [{ id: 'save', type: 'saveData', label: 'Save reviewed details' }] }
  const created = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/views`, { name: uniq('Case view'), definition: view })
  expect(created.status).toBe(201)
  page = await sessions.as('paul.publisher')
  expect((await apiSend(page, 'POST', `/workspaces/${ws}/views/${created.body.id}/publish`, { expectedRevision: 1 })).status).toBe(200)
  page = await sessions.as('dana.designer')
  await newCaseType(page, ws, key)
  await useCmmnFile(page, `${key}.cmmn`, sourceXml)
  await addCaseField(page, 'summary', 'Summary', 'text', true)
  await addCaseField(page, 'checked', 'Checked', 'yesNo', false)
  await page.getByRole('button', { name: 'Choose case view' }).click()
  await page.getByLabel('Published case view').selectOption(created.body.id)
  await expectNoSeriousViolations(page, 'Case view pin authoring')
  await checkAndPublish(page)
  page = await sessions.as('rhea.requester')
  await page.goto(`/w/${ws}/cases`)
  await page.getByRole('row').filter({ has: page.getByRole('cell', { name: key, exact: true }) }).getByRole('button', { name: 'Start case', exact: true }).click()
  const start = page.getByRole('dialog', { name: 'Start case', exact: true })
  await expect(start.getByRole('group', { name: 'Shared evidence details' })).toBeVisible()
  await start.getByLabel(/^Review summary/).fill('Original evidence')
  await expectNoSeriousViolations(page, 'Pinned case start')
  await start.getByRole('button', { name: 'Start case with data' }).click()
  await expect(page).toHaveURL(new RegExp(`/w/${ws}/cases/[^/]+$`))
  const url = page.url()
  await page.getByRole('button', { name: 'Show case data' }).click()
  await expect(page.getByLabel(/^Review summary/)).toHaveAttribute('readonly')
  await expect(page.getByRole('button', { name: 'Save reviewed details' })).toHaveCount(0)
  page = await sessions.as('mani.manager')
  await page.goto(url)
  await page.getByRole('button', { name: 'Show case data' }).click()
  await page.getByLabel(/^Review summary/).fill('Reviewed evidence')
  await page.getByRole('checkbox', { name: 'Evidence checked' }).check()
  await expect(page.getByRole('button', { name: 'Save reviewed details' })).toBeDisabled()
  await page.getByLabel('Reason for data change').fill('Evidence reviewed')
  await page.getByRole('button', { name: 'Save reviewed details' }).click()
  await expect(page.getByText(/Case version 2/)).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: 'Show case data' }).click()
  await expect(page.getByLabel(/^Review summary/)).toHaveValue('Reviewed evidence')
  await expect(page.getByRole('checkbox', { name: 'Evidence checked' })).toBeChecked()
  await page.setViewportSize({ width: 390, height: 844 })
  await expectNoSeriousViolations(page, 'Pinned case view mobile correction')
})
