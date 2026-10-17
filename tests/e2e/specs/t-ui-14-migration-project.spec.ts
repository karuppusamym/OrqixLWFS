import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { expectNoSeriousViolations } from '../support/axe'
import { workspaceId } from '../support/app'

/**
 * T-UI-14: multi-file migration projects and the independent review of migrated workflows. Mock mode only: the mock API
 * analyzes a small BPMN subset and applies the same rules as the server. The real backend is covered by
 * MigrationProjectApiTest and MigrationGovernanceApiTest (a third publisher is not part of the dev realm).
 */

const bpmn = (processId: string, ...called: string[]) => {
  const calls = called.map((target, i) => `<callActivity id="call${i + 1}" calledElement="${target}"/>`).join('')
  const nodes = ['start', ...called.map((_, i) => `call${i + 1}`), 'end']
  const flows = nodes.slice(1).map((to, i) => `<sequenceFlow id="flow${i + 1}" sourceRef="${nodes[i]}" targetRef="${to}"/>`).join('')
  return `<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test"><process id="${processId}" isExecutable="true"><startEvent id="start"/>${calls}<endEvent id="end"/>${flows}</process></definitions>`
}
const file = (name: string, xml: string) => ({ name, mimeType: 'application/xml', buffer: Buffer.from(xml) })

test.describe('migration projects and independent review (mock API)', () => {
  test.skip(!MOCK, 'Mock API only; the backend suites cover the real rules')

  test('a project converts the child before the parent, one file at a time', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    await designer.goto(`/w/${ws}/migrations`)
    await designer.getByLabel('Project name').fill('Order handling')
    // Chosen parent first: the order must come from the call between the files.
    await designer.getByLabel(/BPMN files/).setInputFiles([file('parent.bpmn', bpmn('parent-process', 'child-process')), file('child.bpmn', bpmn('child-process'))])
    await designer.getByRole('button', { name: 'Analyze project' }).click()
    await expect(designer.getByRole('heading', { level: 1, name: 'Order handling' })).toBeVisible()
    const order = designer.getByRole('list', { name: 'Conversion order' }).getByRole('listitem')
    await expect(order).toHaveText(['child.bpmn (next)', 'parent.bpmn'])
    await expect(designer.getByRole('table', { name: 'Project files' })).toContainText('child-process')
    await expectNoSeriousViolations(designer, 'Migration project analysis')
    const projectUrl = designer.url()

    await designer.getByRole('button', { name: 'Convert next file' }).click()
    await expect(designer.getByText('Converted child.bpmn', { exact: true })).toBeVisible()
    await expect(designer.getByTestId('project-status')).toContainText('Converting')
    const publishPath = new URL(await designer.getByRole('link', { name: 'Review and publish' }).evaluate((a) => (a as HTMLAnchorElement).href)).pathname

    // The parent cannot name a child that nobody has published yet.
    await expect(designer.getByRole('heading', { level: 2, name: 'Next file: parent.bpmn' })).toBeVisible()
    await designer.getByLabel('Child workflow mappings (JSON)').fill(JSON.stringify({ call1: { workflowRef: 'child-process', inputMapping: {}, resultMapping: {} } }))
    await designer.getByRole('button', { name: 'Convert next file' }).click()
    await expect(designer.getByRole('list', { name: 'Unresolved conversion issues' })).toContainText("has no published release")

    const publisher = await sessions.as('paul.publisher')
    await publisher.goto(publishPath)
    await publisher.getByRole('button', { name: /Publish version 1/ }).click()
    await expect(publisher.getByTestId('publish-success')).toBeVisible()

    const again = await sessions.as('dana.designer')
    await again.goto(projectUrl)
    await expect(again.getByRole('heading', { level: 2, name: 'Next file: parent.bpmn' })).toBeVisible()
    await again.getByLabel('Child workflow mappings (JSON)').fill(JSON.stringify({ call1: { workflowRef: 'child-process', inputMapping: {}, resultMapping: {} } }))
    await again.getByRole('button', { name: 'Convert next file' }).click()
    await expect(again.getByText('Every file is converted')).toBeVisible()
    await expect(again.getByTestId('project-status')).toContainText('All files converted')
  })

  test('a project with a missing child is blocked before anything is converted', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const designer = await sessions.as('dana.designer')
    const ws = await workspaceId(designer)
    await designer.goto(`/w/${ws}/migrations`)
    await designer.getByLabel('Project name').fill('Incomplete')
    await designer.getByLabel(/BPMN files/).setInputFiles([file('parent.bpmn', bpmn('parent-process', 'missing-process'))])
    await designer.getByRole('button', { name: 'Analyze project' }).click()
    await expect(designer.getByRole('list', { name: 'Project findings' })).toContainText("Called process 'missing-process' is not included in this project.")
    await expect(designer.getByRole('button', { name: 'Convert next file' })).toHaveCount(0)
    await expectNoSeriousViolations(designer, 'Blocked migration project')
  })

  test('with the policy on, a migrated workflow needs a reviewer who is neither mapper nor publisher', async ({ sessions }) => {
    await sessions.mockCall('reset', { published: true, sampleRuns: false })
    const admin = await sessions.as('wade.admin')
    const ws = await workspaceId(admin)
    await admin.goto(`/w/${ws}/settings`)
    await admin.getByRole('tab', { name: /Migration review/ }).click()
    await admin.getByRole('checkbox', { name: /Require an independent review/ }).check()
    await admin.getByLabel('Reason for this setting').fill('Four eyes on every migration')
    await admin.getByRole('button', { name: 'Save migration review setting' }).click()
    await expect(admin.getByTestId('migration-policy-state')).toContainText('Independent review required')
    await expectNoSeriousViolations(admin, 'Migration review setting')

    const designer = await sessions.as('dana.designer')
    await designer.goto(`/w/${ws}/migrations`)
    await designer.getByLabel(/Source file/).setInputFiles(file('orders.bpmn', bpmn('orders-process')))
    await designer.getByRole('button', { name: 'Analyze source' }).click()
    await designer.getByRole('checkbox', { name: /I reviewed the assumptions/ }).check()
    await designer.getByRole('button', { name: 'Convert to draft' }).click()
    await expect(designer.getByTestId('studio')).toBeVisible()
    const workflowId = /\/workflows\/([0-9a-f-]+)\/edit/.exec(designer.url())![1]!
    const publishPath = `/w/${ws}/workflows/${workflowId}/publish`

    // Dana cannot publish at all; the page still explains the review.
    await designer.goto(publishPath)
    await expect(designer.getByTestId('approve-disabled-reason')).toContainText('You need permission to publish workflows')

    const reviewer = await sessions.as('paul.publisher')
    await reviewer.goto(publishPath)
    const panel = reviewer.getByTestId('migration-review')
    await expect(panel).toContainText('Mapped by')
    await expect(panel).toContainText('Dana Designer')
    await expect(panel.getByTestId('publish-blocked-reason')).toContainText('independent review')
    await expect(reviewer.getByRole('button', { name: /Publish version 1/ })).toBeDisabled()
    await panel.getByLabel('Review reason').fill('Mappings match the source report')
    await panel.getByRole('button', { name: 'Approve migration review' }).click()
    await expect(panel).toContainText('Independently reviewed')
    await expect(reviewer.getByRole('button', { name: /Publish version 1/ })).toBeDisabled()
    await expect(panel.getByTestId('publish-blocked-reason')).toContainText('You reviewed this migration, so a different person must publish it.')
    await expectNoSeriousViolations(reviewer, 'Migration review panel')

    const publisher = await sessions.as('pia.publisher')
    await publisher.goto(publishPath)
    await expect(publisher.getByTestId('migration-review')).toContainText('Independently reviewed')
    await publisher.getByRole('button', { name: /Publish version 1/ }).click()
    await expect(publisher.getByTestId('publish-success')).toBeVisible()
  })
})
