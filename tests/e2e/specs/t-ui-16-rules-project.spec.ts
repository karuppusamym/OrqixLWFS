import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, decideOpenTask, openTask, submitPurchase, workflowIdByKey, workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

test.skip(!MOCK, 'Mock browser flows; real dependency conversion and decision execution are verified by backend integration tests')

const file = (name: string, xml: string) => ({ name, mimeType: 'application/xml', buffer: Buffer.from(xml) })
const dmn = `<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="rules" name="Routing rules"><decision id="risk"><decisionTable hitPolicy="FIRST"><input id="i"><inputExpression typeRef="number"><text>amount</text></inputExpression></input><output id="o" name="route" typeRef="string"/><rule id="r"><inputEntry><text>&gt; 10</text></inputEntry><outputEntry><text>"review"</text></outputEntry></rule></decisionTable></decision></definitions>`
const bpmn = `<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="urn:test"><process id="routing-process" isExecutable="true"><startEvent id="start"/><businessRuleTask id="rule" camunda:decisionRef="risk"/><endEvent id="end"/><sequenceFlow id="f1" sourceRef="start" targetRef="rule"/><sequenceFlow id="f2" sourceRef="rule" targetRef="end"/></process></definitions>`

test('mixed XML project orders its DMN dependency and offers an explicit mapping for review', async ({ sessions }) => {
  await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  await designer.goto(`/w/${ws}/migrations`)
  await designer.getByLabel('Project name').fill('Routing application')
  await designer.getByLabel(/BPMN files/).setInputFiles([file('routing.xml', bpmn), file('rules.xml', dmn)])
  await designer.getByRole('button', { name: 'Analyze project' }).click()
  await expect(designer.getByRole('heading', { level: 1, name: 'Routing application' })).toBeVisible()
  await expect(designer.getByRole('list', { name: 'Conversion order' }).getByRole('listitem')).toHaveText(['rules.xml (next)', 'routing.xml'])
  await expect(designer.getByRole('table', { name: 'Project files' })).toContainText('risk')
  await designer.getByRole('button', { name: 'Convert next file' }).click()
  await expect(designer.getByRole('heading', { level: 2, name: 'Next file: routing.xml' })).toBeVisible()
  const mapping = designer.getByLabel('Decision mappings (JSON)')
  await expect(mapping).toHaveValue(/"decisionId": "risk"/)
  const parsed = JSON.parse(await mapping.inputValue()) as { rule: { migrationId: string; decisionId: string } }
  expect(parsed.rule.migrationId).toMatch(/^[0-9a-f-]{36}$/)
  await expectNoSeriousViolations(designer, 'DMN project mapping review')
  await designer.getByRole('button', { name: 'Convert next file' }).click()
  await expect(designer.getByText('Every file is converted')).toBeVisible()
})

test('decision evidence is visible to its requester and removed from metadata-reader API responses', async ({ sessions }) => {
  await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  const workflowId = await workflowIdByKey(requester, ws)
  const { runUrl } = await submitPurchase(requester, ws, workflowId, { title: 'Evidence laptop', purpose: 'Rule evidence test', costCenter: 'OPS', amount: '7500' })
  const manager = await sessions.as('mani.manager')
  await openTask(manager, ws, 'Evidence laptop')
  await decideOpenTask(manager, 'approve')
  const owner = await sessions.as('rhea.requester')
  await owner.goto(runUrl)
  await owner.getByText('Decision evidence', { exact: true }).click()
  const evidence = owner.locator('details').filter({ hasText: 'Decision evidence' })
  await expect(evidence).toContainText('7500')
  await expect(evidence.getByRole('table', { name: 'Evaluated candidate rules' })).toContainText('Amount is at least 5000')
  await expect(evidence).toContainText('Default rule not selected')
  await expectNoSeriousViolations(owner, 'Decision evidence timeline')

  const viewer = await sessions.as('vic.viewer')
  await viewer.goto(runUrl)
  await expect(viewer.getByText('Decision evidence is restricted to request participants and readers with data permission.')).toBeVisible()
  await expect(viewer.getByText('7500', { exact: true })).toHaveCount(0)
  const runId = runUrl.split('/').at(-1)!
  const events = await apiGet<{ items: Array<{ type: string; data: Record<string, unknown> }> }>(viewer, `/workspaces/${ws}/runs/${runId}/events`)
  const decision = events.items.find(e => e.type === 'DECISION_EVALUATED')!
  expect(decision.data.evidenceRedacted).toBe(true)
  expect(decision.data).not.toHaveProperty('evidence')
  expect(decision.data).not.toHaveProperty('outputs')
  expect(decision.data).not.toHaveProperty('matched')
})
