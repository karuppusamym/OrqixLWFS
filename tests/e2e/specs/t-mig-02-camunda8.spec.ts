import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { expectNoSeriousViolations } from '../support/axe'
import { workspaceId } from '../support/app'

/**
 * T-MIG-02: Camunda 8 import (mock API). The mock inventory reads the hand-authored fixtures of examples/migration and shows the
 * same reason codes as the real adapter; the real rules are covered by Camunda8ImportTest and Camunda8MigrationApiTest.
 */
const fixture = (name: string) => ({ name, mimeType: 'application/octet-stream', buffer: readFileSync(fileURLToPath(new URL(`../../../examples/migration/${name}`, import.meta.url))) })

test.describe('Camunda 8 import (mock API)', () => {
  test.skip(!MOCK, 'Mock API only; the backend suites cover the real rules')

  test('shows the source version, the connector call as provenance and plain-language findings', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    await designer.goto(`/w/${ws}/migrations`)
    await designer.getByLabel('Source file (maximum 1 MiB)').setInputFiles(fixture('camunda8-connector-http.bpmn'))
    await designer.getByRole('button', { name: 'Analyze source' }).click()
    await expect(designer.getByText('Source: Camunda 8, version 8.5.0')).toBeVisible()
    await expect(designer.getByText('Choose the system call that replaces this connector')).toBeVisible()
    const original = designer.getByLabel('Original call')
    await expect(original).toContainText('POST procurement.example.com/orders')
    await expect(original).toContainText('Suggested operation name: createPurchaseOrder')
    await expect(designer.getByText(/not a claim of general Camunda 8 compatibility/)).toBeVisible()
    await expectNoSeriousViolations(designer, 'Camunda 8 connector review')
  })

  test('blocked Camunda 8 constructs carry their reason codes and nothing converts', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    await designer.goto(`/w/${ws}/migrations`)
    await designer.getByLabel('Source file (maximum 1 MiB)').setInputFiles(fixture('camunda8-unsupported.bpmn'))
    await designer.getByRole('button', { name: 'Analyze source' }).click()
    for (const code of ['MESSAGE_EVENT_NOT_SUPPORTED', 'CONNECTOR_NOT_SUPPORTED', 'EXPRESSION_NOT_SUPPORTED', 'FORM_COMPONENT_NOT_SUPPORTED'])
      await expect(designer.getByText(new RegExp(code)).first()).toBeVisible()
    await designer.getByRole('button', { name: 'Convert to draft' }).click()
    await expect(designer.getByRole('alert').first()).toBeVisible()
    await expect(designer.getByRole('list', { name: 'Unresolved conversion issues' })).toContainText('cannot')
  })

  test('a form file is previewed, attached to the start step and becomes the request form of the draft', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    await designer.goto(`/w/${ws}/migrations`)
    await designer.getByLabel('Source file (maximum 1 MiB)').setInputFiles(fixture('camunda8-user-task-form.bpmn'))
    await designer.getByRole('button', { name: 'Analyze source' }).click()
    await expect(designer.getByText('Source: Camunda 8, version 8.5.0')).toBeVisible()
    // The task form is embedded in the file and shown as a note on the approval.
    await expect(designer.getByRole('region', { name: /Preview of Form reviewForm on managerReview/ })).toBeVisible()
    await designer.getByLabel(/Form file for start/).setInputFiles(fixture('camunda8-user-task-form.form'))
    const preview = designer.getByRole('region', { name: /Preview of Form expense-request as a request form/ })
    await expect(preview.getByText('Employee name')).toBeVisible()
    await expect(preview.getByText('Receipt attached')).toBeVisible()
    await expect(preview.getByText('Not imported')).toBeVisible()
    await expectNoSeriousViolations(designer, 'Camunda form preview')
    await designer.getByLabel('Map source assignment: source-managers').selectOption('managers')
    const review = designer.getByRole('checkbox', { name: /I reviewed the \d+ converted items/ })
    if (await review.count()) await review.check()
    await designer.getByRole('checkbox', { name: /I reviewed the assumptions/ }).check()
    await designer.getByRole('button', { name: 'Convert to draft' }).click()
    await expect(designer).toHaveURL(/\/workflows\/.+\/edit/)
    await expect(designer.getByText(/Imported from camunda8/).first()).toBeVisible()
  })

  test('a form file with another id is refused for the step that links a different form', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    await designer.goto(`/w/${ws}/migrations`)
    await designer.getByLabel('Source file (maximum 1 MiB)').setInputFiles(fixture('camunda8-user-task-form.bpmn'))
    await designer.getByRole('button', { name: 'Analyze source' }).click()
    const other = JSON.stringify({ id: 'other-form', type: 'default', components: [{ type: 'textfield', id: 'a', key: 'x', label: 'X' }] })
    await designer.getByLabel(/Form file for start/).setInputFiles({ name: 'other.form', mimeType: 'application/json', buffer: Buffer.from(other) })
    await expect(designer.getByText(/uses the form 'expense-request'/)).toBeVisible()
  })
})
