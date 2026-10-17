import { readFile } from 'node:fs/promises'
import { expect, test } from '../support/fixtures'
import { workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'
import { MOCK } from '../support/env'

test('a multi-decision DMN project requires an explicit selection and retains the caller mapping', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const suffix = Date.now()
  const dmn = await readFile('../../examples/migration/dmn-multi-review.dmn')
  const caller = await readFile('../../examples/migration/business-rule-task.bpmn')
  await page.goto(`/w/${ws}/migrations`)
  await page.getByLabel('Project name', { exact: true }).fill(`Multi-decision project ${suffix}`)
  await page.getByLabel(/BPMN files/).setInputFiles([
    { name: 'caller.bpmn', mimeType: 'application/xml', buffer: caller },
    { name: 'multi.dmn', mimeType: 'application/xml', buffer: dmn },
  ])
  await page.getByRole('button', { name: 'Analyze project', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Next file: multi.dmn', exact: true })).toBeVisible()
  const choice = page.getByLabel('Decision to convert (required)', { exact: true })
  await expect(choice).toHaveAttribute('required', '')
  await expect(choice).toHaveValue('')
  await page.getByRole('button', { name: 'Convert next file', exact: true }).click()
  expect(await choice.evaluate(element => (element as HTMLSelectElement).validity.valueMissing)).toBe(true)
  await expect(page.getByRole('heading', { name: 'Next file: multi.dmn', exact: true })).toBeVisible()
  await choice.selectOption('alternateRoute')
  await page.getByLabel('Workflow key', { exact: true }).fill(`multi-policy-${suffix}`)
  const review = page.getByRole('checkbox', { name: /I reviewed the .* converted items marked review/ })
  if (await review.count()) await review.check()
  await page.getByRole('checkbox', { name: /I reviewed the assumptions/ }).check()
  await expectNoSeriousViolations(page, 'Multi-decision project selection')
  await page.getByRole('button', { name: 'Convert next file', exact: true }).click()
  await expect(page.getByText('Converted multi.dmn', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Next file: caller.bpmn', exact: true })).toBeVisible()
  const mappings = JSON.parse(await page.getByLabel('Decision mappings (JSON)', { exact: true }).inputValue())
  expect(mappings.routeRule.decisionId).toBe('reviewRoute')
  expect(mappings.routeRule.migrationId).toMatch(/^[0-9a-f-]{36}$/)
  await page.getByLabel('Workflow key', { exact: true }).fill(`multi-caller-${suffix}`)
  if (await review.count()) await review.check()
  await page.getByRole('checkbox', { name: /I reviewed the assumptions/ }).check()
  await page.getByRole('button', { name: 'Convert next file', exact: true }).click()
  await expect(page.getByText('Every file is converted', { exact: true })).toBeVisible()
  await expect(page.getByText(/Converting every file does not create a workflow for every decision/)).toBeVisible()
  await expectNoSeriousViolations(page, 'Completed multi-decision project')
})
