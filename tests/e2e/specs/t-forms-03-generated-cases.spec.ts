import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { createWorkflow, editDraft } from '../support/api'
import { workspaceId } from '../support/app'

/**
 * "Generate cases for every path" (docs/build/S122) on the IT equipment request of t-forms-02: under USD 2,000 the
 * manager decides alone, from USD 2,000 finance signs off too; "Other" asks for a description, a replacement asks for
 * an asset tag in the pattern IT-12345. The generated cases cover every route, are kept, and all pass in one run.
 * Mock mode only: the dry runs use the mock-side port of the simulator (the real one is covered by SimulationApiTest).
 */

const rule = (fieldRef: string, operator: string, value: string | number | boolean) => ({ fieldRef, operator, value })

test.describe('generated test cases (mock API)', () => {
  test.skip(!MOCK, 'Mock API only')

  test('IT equipment request: one case per path, kept, and all of them pass', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    const { id } = await createWorkflow(designer, ws, `IT equipment request ${Date.now().toString(36)}`)
    await editDraft(designer, ws, id, (d) => {
      const start = d.nodes.find((n: { type: string }) => n.type === 'start')
      start.label = 'Equipment requested'
      d.inputSchema = {
        type: 'object',
        additionalProperties: false,
        required: ['title', 'item', 'estimatedCost'],
        properties: {
          title: { type: 'string', title: 'Short title', minLength: 1, maxLength: 120 },
          item: { type: 'string', title: 'What do you need?', enum: ['Laptop', 'Monitor', 'Phone', 'Headset', 'Other'] },
          otherItem: { type: 'string', title: 'Describe the item', maxLength: 200 },
          estimatedCost: { type: 'number', title: 'Estimated cost (USD)', minimum: 0.01, maximum: 1000000, multipleOf: 0.01 },
          justification: { type: 'string', title: 'Business justification', maxLength: 2000 },
          replacement: { type: 'boolean', title: 'Replacing a broken device?' },
          assetTag: { type: 'string', title: 'Asset tag of the old device', pattern: '^IT-\\d{5}$', patternMessage: 'Enter the tag on the device, for example IT-12345.' },
        },
      }
      const rules = { otherItem: rule('/input/item', 'eq', 'Other'), justification: rule('/input/estimatedCost', 'gte', 1000), assetTag: rule('/input/replacement', 'eq', true) }
      d.inputUi = {
        widgets: { item: 'select', estimatedCost: 'money', justification: 'textarea' },
        currency: 'USD',
        visibleWhen: rules,
        requiredWhen: rules,
        layout: {
          sections: [
            { id: 'need', title: 'What you need', fields: ['title', 'item', 'otherItem'] },
            { id: 'cost', title: 'Cost', fields: ['estimatedCost', 'justification'] },
            { id: 'replacement', title: 'Replacement', fields: ['replacement', 'assetTag'] },
          ],
        },
      }
      d.nodes = [
        start,
        { id: 'manager', type: 'approval', label: 'Manager review', config: { candidateGroupRef: 'managers', rejectReasonRequired: true, dueAfterHours: 24 } },
        { id: 'costCheck', type: 'branch', label: 'Finance sign-off needed?', config: {} },
        { id: 'finance', type: 'approval', label: 'Finance sign-off', config: { candidateGroupRef: 'finance-approvers', rejectReasonRequired: true, dueAfterHours: 48 } },
        { id: 'notifyApproved', type: 'notification', label: 'Tell the requester it is approved', config: { channel: 'inApp', recipient: 'initiator', message: 'Your IT equipment request was approved.' } },
        { id: 'approved', type: 'end', label: 'Approved', config: { businessOutcome: 'Approved', outcomeKind: 'success' } },
        { id: 'notifyRejected', type: 'notification', label: 'Tell the requester it was rejected', config: { channel: 'inApp', recipient: 'initiator', message: 'Your IT equipment request was rejected.' } },
        { id: 'rejected', type: 'end', label: 'Rejected', config: { businessOutcome: 'Rejected', outcomeKind: 'rejected' } },
      ]
      d.transitions = [
        { id: 't1', from: start.id, to: 'manager', on: 'next' },
        { id: 't2', from: 'manager', to: 'costCheck', on: 'approved' },
        { id: 't3', from: 'manager', to: 'notifyRejected', on: 'rejected' },
        { id: 't4', from: 'costCheck', to: 'finance', on: 'condition', label: 'USD 2,000 or more', condition: rule('/input/estimatedCost', 'gte', 2000) },
        { id: 't5', from: 'costCheck', to: 'notifyApproved', on: 'default', label: 'Under USD 2,000' },
        { id: 't6', from: 'finance', to: 'notifyApproved', on: 'approved' },
        { id: 't7', from: 'finance', to: 'notifyRejected', on: 'rejected' },
        { id: 't8', from: 'notifyApproved', to: 'approved', on: 'next' },
        { id: 't9', from: 'notifyRejected', to: 'rejected', on: 'next' },
      ]
      d.decisions = []
      d.outputSchema = { type: 'object', additionalProperties: false, properties: {} }
      d.outputMapping = {}
      d.dependencies = { connections: [] }
      delete d.uiLayout
      delete d.stages
    })

    await designer.goto(`/w/${ws}/workflows/${id}/edit`)
    await expect(designer.getByTestId('studio')).toBeVisible()
    await designer.getByRole('button', { name: 'Test workflow' }).click()
    const panel = designer.getByTestId('test-panel')
    await expect(panel).toBeVisible()

    await panel.getByTestId('generate-cases').click()
    await expect(panel.getByTestId('generated-summary')).toHaveText('7 cases to review. Together they take 6 of 6 routes.', { timeout: 30_000 })
    const proposed = panel.getByTestId('generated-case')
    await expect(proposed.locator('span.font-semibold')).toHaveText([
      'Estimated cost (USD) is 2000',
      'Estimated cost (USD) is 1999.99',
      'What do you need? is “Other”',
      'Estimated cost (USD) is 999.99',
      'Replacing a broken device? is Yes',
      'Manager review rejects (Estimated cost (USD) is 2000)',
      'Finance sign-off rejects (Estimated cost (USD) is 2000)',
    ])
    // Under USD 2,000 the manager decides alone; from USD 2,000 finance signs off too.
    await expect(proposed.nth(0)).toContainText('Steps: Equipment requested → Manager review → Finance sign-off needed? → Finance sign-off → Tell the requester it is approved → Approved')
    await expect(proposed.nth(1)).toContainText('Steps: Equipment requested → Manager review → Finance sign-off needed? → Tell the requester it is approved → Approved')
    await expect(proposed.nth(5)).toContainText('Expect: Rejected')
    await expect(panel.getByRole('heading', { name: /Saved test cases \(0 of 20\)/ })).toBeVisible()

    await panel.getByRole('button', { name: 'Keep all 7' }).click()
    await expect(panel.getByTestId('generated-cases')).toHaveCount(0)
    await expect(panel.getByRole('heading', { name: /Saved test cases \(7 of 20\)/ })).toBeVisible()

    // The kept "replacement" case answers the asset tag in the required pattern; loading it shows the question.
    await panel.getByRole('button', { name: 'Load the test case Replacing a broken device? is Yes' }).click()
    await expect(panel.getByLabel(/Asset tag of the old device/)).toHaveValue('IT-00000')
    await expect(panel.getByLabel(/Describe the item/)).toHaveCount(0)

    await panel.getByRole('button', { name: 'Run all saved cases' }).click()
    await expect(panel.getByTestId('case-summary')).toHaveText('7 of 7 passed', { timeout: 30_000 })
  })
})
