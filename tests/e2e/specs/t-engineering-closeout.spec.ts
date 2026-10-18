import { readFileSync } from 'node:fs'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, selectStep, workspaceId } from '../support/app'
import { apiSend, createWorkflow, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

test('published request view is pinned in Studio and renders its submit action at runtime', async ({ sessions }) => {
  test.setTimeout(180_000)
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const workflow = await createWorkflow(page, ws, uniq('Pinned view'))
  const draft = await apiGet<{ definition: { inputSchema: { required: string[]; properties: Record<string, { type: string; enum?: string[] }> } } }>(page, `/workspaces/${ws}/workflows/${workflow.id}/draft`)
  const name = uniq('Reusable request')
  const view = { schemaVersion: 1, kind: 'request', sections: [{ id: 'details', label: 'Shared purchase details', columns: 2,
    fields: Object.entries(draft.definition.inputSchema.properties).map(([key, property]) => ({ id: key, label: `Shared ${key}`, binding: `request.${key}`, type: property.enum ? 'choice' : property.type === 'number' ? 'number' : 'text', required: draft.definition.inputSchema.required.includes(key), ...(property.enum ? { options: property.enum.map(value => ({ value, label: value })) } : {}) })) }], actions: [{ id: 'submit', type: 'submit', label: 'Send for review' }] }
  const created = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/views`, { name, definition: view })
  expect(created.status).toBe(201)
  page = await sessions.as('paul.publisher')
  expect((await apiSend(page, 'POST', `/workspaces/${ws}/views/${created.body.id}/publish`, { expectedRevision: 1 })).status).toBe(200)
  page = await sessions.as('dana.designer')
  await page.goto(`/w/${ws}/workflows/${workflow.id}/edit`)
  await page.getByRole('tab', { name: 'Steps', exact: true }).click()
  await selectStep(page, 'start')
  await page.getByRole('button', { name: 'Choose reusable view' }).click()
  await page.getByLabel('Published request view').selectOption(created.body.id)
  await page.getByRole('button', { name: 'Apply selected version' }).click()
  await expect(page.getByText(`Pinned view ${created.body.id}, version 1`)).toBeVisible()
  await expect(page.getByTestId('save-state')).toContainText('Saved')
  await expectNoSeriousViolations(page, 'Pinned request view authoring')
  const saved = await apiGet<{ definition: { inputUi: { viewRef: { id: string; version: number } } } }>(page, `/workspaces/${ws}/workflows/${workflow.id}/draft`)
  expect(saved.definition.inputUi.viewRef).toEqual({ id: created.body.id, version: 1 })
  page = await sessions.as('paul.publisher')
  await publish(page, ws, workflow.id)
  page = await sessions.as('rhea.requester')
  await page.goto(`/w/${ws}/start/${workflow.id}`)
  await page.getByLabel('Shared title', { exact: false }).fill(uniq('View request'))
  await page.getByLabel('Shared purpose', { exact: false }).fill('Verify the pinned view')
  await page.getByLabel('Shared costCenter', { exact: false }).selectOption('OPS')
  await page.getByLabel('Shared amount', { exact: false }).fill('100')
  await expectNoSeriousViolations(page, 'Pinned request form')
  await page.getByRole('button', { name: 'Review request' }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await expectNoSeriousViolations(page, 'Pinned request mobile review')
  await page.getByRole('button', { name: 'Send for review', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Request submitted' })).toBeVisible()
})

test('operator confirms a case termination with a reason and open tasks disappear', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  let instanceId = '77777777-7777-4777-8777-777777777702'
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  if (!MOCK) {
    const key = uniq('case')
    const sourceXml = readFileSync(new URL('../../../backend/app/src/test/resources/cases/cmmn-compatibility.cmmn', import.meta.url), 'utf8').replaceAll('compatibilityCase', key).replaceAll('case-workers', 'managers')
    const definition = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/cases/definitions`, { key, name: 'Termination case', sourceXml })
    expect(definition.status).toBe(201)
    page = await sessions.as('rhea.requester')
    const started = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/cases/definitions/${definition.body.id}/instances`)
    expect(started.status).toBe(201); instanceId = started.body.id
  }
  page = await sessions.as('olga.operator')
  await page.goto(`/w/${ws}/cases/${instanceId}`)
  await page.getByRole('button', { name: 'Terminate case', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Terminate this case?' })
  await expect(dialog.getByRole('button', { name: 'Confirm termination' })).toBeDisabled()
  await dialog.getByLabel('Reason').fill('Duplicate synthetic case')
  await expectNoSeriousViolations(page, 'Case termination confirmation')
  await dialog.getByRole('button', { name: 'Confirm termination' }).click()
  await expect(page.getByText('terminated', { exact: true })).toBeVisible()
  await expect(page.getByText('No tasks are currently available.')).toBeVisible()
  await page.reload()
  await expect(page.getByText('terminated', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Terminate case', exact: true })).toHaveCount(0)
  await expectNoSeriousViolations(page, 'Terminated case detail')
})
