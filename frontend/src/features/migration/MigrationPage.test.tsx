import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'
import type { components } from '@/api/schema'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'

const id = '00000000-0000-7000-8000-000000000901'
const report = (): components['schemas']['CompatibilityReport'] => ({ sourceSystem: 'camunda7', adapterVersion: 'test', sourceHash: 'a'.repeat(64),
  processId: 'import-example', processName: 'Imported example', elementCounts: { total: 1, action: 1 },
  issues: [{ sourceElementId: 'manager', sourceKind: 'userTask', severity: 'action', reasonCode: 'GROUP_MAPPING_REQUIRED',
    explanation: 'Choose a workspace group.', resolutionStatus: 'open' }], assumptions: ['Review target approval policies.'],
  requiredMappings: { groups: ['source-managers'], approvalTasks: [], serviceTasks: [] }, publishable: false })
const job = (): components['schemas']['MigrationJob'] => ({ id, fileName: 'approval.bpmn', status: 'analyzed', report: report(), createdAt: '2026-10-09T00:00:00Z' })

describe('migration workbench', () => {
  it('analyzes an uploaded file, maps a source group and opens the created draft with provenance', async () => {
    signInAs('dana.designer')
    let saved = job()
    let body: Record<string, unknown> | null = null
    server.use(
      http.post('*/api/v1/workspaces/:ws/migrations', async ({ request }) => {
        expect(await request.json()).toMatchObject({ fileName: 'approval.bpmn', sourceFormat: 'bpmn', content: '<fixture />' })
        return HttpResponse.json(saved, { status: 201 })
      }),
      http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(saved)),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/convert', async ({ request }) => {
        body = await request.json() as Record<string, unknown>
        saved = { ...saved, status: 'converted', convertedWorkflowId: PURCHASE_WORKFLOW_ID }
        return HttpResponse.json({ workflow: { id: PURCHASE_WORKFLOW_ID }, draft: {}, report: saved.report }, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/migrations`)
    const file = new File(['<fixture />'], 'approval.bpmn', { type: 'application/xml' })
    Object.defineProperty(file, 'text', { value: async () => '<fixture />' })
    await user.upload(await screen.findByLabelText(/Source file/), file)
    await user.click(screen.getByRole('button', { name: 'Analyze source' }))
    await screen.findByRole('option', { name: /^Managers$/ })
    await user.selectOptions(await screen.findByLabelText('Map source assignment: source-managers'), 'managers')
    await user.click(screen.getByRole('checkbox', { name: /I reviewed the assumptions/ }))
    await user.click(screen.getByRole('button', { name: 'Convert to draft' }))
    await waitFor(() => expect(router.state.location.pathname).toContain(`/workflows/${PURCHASE_WORKFLOW_ID}/edit`))
    expect(body).toMatchObject({ groupMappings: { 'source-managers': 'managers' }, acknowledgeInfo: true })
    expect(await screen.findByText(/Imported from camunda7/)).toBeInTheDocument()
  })
  it('collects explicit child mappings before converting imported calls', async () => {
    signInAs('dana.designer')
    const source = job(); source.report.requiredMappings = { groups: [], approvalTasks: [], serviceTasks: [], childWorkflows: ['childCall'] }
    let body: Record<string, unknown> | null = null
    server.use(http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(source)),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/convert', async ({ request }) => {
        body = await request.json() as Record<string, unknown>
        return HttpResponse.json({ code: 'MIGRATION_BLOCKED', detail: 'Review mapping', status: 422 }, { status: 422 })
      }))
    const user = userEvent.setup()
    renderApp(`/w/${WS}/migrations/${id}`)
    const field = await screen.findByLabelText('Child workflow mappings (JSON)')
    await user.clear(field)
    await user.paste(JSON.stringify({ childCall: { workflowRef: 'purchase-approval', inputMapping: {}, resultMapping: {} } }))
    await user.click(screen.getByRole('checkbox', { name: /I reviewed the assumptions/ }))
    await user.click(screen.getByRole('button', { name: 'Convert to draft' }))
    await waitFor(() => expect(body).toMatchObject({ childWorkflowMappings: { childCall: { workflowRef: 'purchase-approval', inputMapping: {}, resultMapping: {} } } }))
  })
  it('shows review items, requires their acknowledgement flag in the request and offers grouped service and decision mappings', async () => {
    signInAs('dana.designer')
    const source = job(); source.report.elementCounts = { total: 3, action: 1, review: 2 }
    source.report.issues.push({ sourceElementId: 'flow1', sourceKind: 'sequenceFlow', severity: 'review', reasonCode: 'CONDITION_CONVERTED', explanation: 'Condition converted; original text kept.', resolutionStatus: 'open' })
    source.report.requiredMappings = { groups: [], approvalTasks: [], serviceTasks: ['a', 'b'], serviceGroups: { 'topic:send-mail': ['a', 'b'] }, decisionTasks: ['rule1'] }
    let body: Record<string, unknown> | null = null
    server.use(http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(source)),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/convert', async ({ request }) => {
        body = await request.json() as Record<string, unknown>
        return HttpResponse.json({ code: 'MIGRATION_BLOCKED', detail: 'Review mapping', status: 422 }, { status: 422 })
      }))
    const user = userEvent.setup()
    renderApp(`/w/${WS}/migrations/${id}`)
    expect(await screen.findByText('review: 2')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Severity'), 'review')
    expect(screen.getByText('CONDITION_CONVERTED', { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/topic:send-mail \(2 tasks\)/)).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: /I reviewed the 2 converted items marked review/ }))
    await user.click(screen.getByRole('checkbox', { name: /I reviewed the assumptions/ }))
    await user.click(screen.getByRole('button', { name: 'Convert to draft' }))
    await waitFor(() => expect(body).toMatchObject({ acknowledgeReview: true, acknowledgeInfo: true, serviceGroupMappings: {}, decisionMappings: {} }))
  })
  it('lists generated decision checks for a DMN source and hides the request schema field', async () => {
    signInAs('dana.designer')
    const source = job(); source.report.sourceSystem = 'dmn'
    source.report.requiredMappings = { groups: [], approvalTasks: [], serviceTasks: [] }
    source.report.testCases = [{ decision: 'tier', name: 'at 5000', input: { amount: 5000 }, expected: { tier: 'finance' }, actual: { tier: 'finance' }, matchedRow: 1, status: 'passed' }]
    server.use(http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(source)))
    renderApp(`/w/${WS}/migrations/${id}`)
    expect(await screen.findByRole('region', { name: 'Generated decision checks' })).toBeInTheDocument()
    expect(screen.getByText('at 5000')).toBeInTheDocument()
    expect(screen.getByText('passed')).toBeInTheDocument()
    expect(screen.queryByLabelText('Request schema (optional JSON)')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Decision to convert (optional)')).toBeInTheDocument()
  })
  it('groups and filters findings without changing the complete report', async () => {
    signInAs('dana.designer'); const user = userEvent.setup(); const source = job()
    source.report.issues.push({ sourceElementId: 'timer', sourceKind: 'boundaryEvent', severity: 'blocker', reasonCode: 'UNSUPPORTED', explanation: 'Boundary timers require manual review.', resolutionStatus: 'open' })
    server.use(http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(source)))
    renderApp(`/w/${WS}/migrations/${id}`)
    expect(await screen.findByRole('region', { name: 'boundaryEvent findings' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Severity'), 'blocker')
    expect(screen.getByText('Showing 1 of 2 findings. Exports and conversion use the complete report.')).toBeInTheDocument()
    expect(screen.queryByText('Choose a workspace group.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download findings CSV' })).toBeInTheDocument()
  })
  it('shows native Pega inventory as manual mapping work, without a conversion button', async () => {
    signInAs('dana.designer')
    const archive = job(); archive.report.sourceSystem = 'pega'
    archive.report.issues[0]!.reasonCode = 'PEGA_RULE_REQUIRES_MAPPING'
    server.use(http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(archive)))
    renderApp(`/w/${WS}/migrations/${id}`)
    expect(await screen.findByText('Manual Pega mapping required')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Convert to draft' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download mapping package template' })).toBeInTheDocument()
  })
  it('opens a committed draft even when the source-report refresh fails', async () => {
    signInAs('dana.designer')
    let committed = false
    server.use(
      http.get('*/api/v1/workspaces/:ws/migrations/:id', () => committed
        ? HttpResponse.json({ code: 'INTERNAL_ERROR', status: 500 }, { status: 500 }) : HttpResponse.json(job())),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/convert', () => {
        committed = true
        return HttpResponse.json({ workflow: { id: PURCHASE_WORKFLOW_ID }, draft: {}, report: report() }, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/migrations/${id}`)
    await user.click(await screen.findByRole('button', { name: 'Convert to draft' }))
    await waitFor(() => expect(router.state.location.pathname).toContain(`/workflows/${PURCHASE_WORKFLOW_ID}/edit`))
    expect(await screen.findByRole('link', { name: 'View compatibility report' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
  it('lets a viewer read the report without upload or conversion controls', async () => {
    signInAs('vic.viewer')
    server.use(http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(job())))
    renderApp(`/w/${WS}/migrations/${id}`)
    await screen.findByText('approval.bpmn')
    expect(screen.queryByRole('button', { name: 'Analyze source' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Convert to draft' })).not.toBeInTheDocument()
  })
  it('shows the server’s unresolved items when conversion is blocked', async () => {
    signInAs('dana.designer')
    server.use(http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(job())),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/convert', () => HttpResponse.json({ code: 'MIGRATION_BLOCKED', status: 422,
        title: 'Blocked', detail: 'No draft was created.', openItems: report().issues }, { status: 422 })))
    const user = userEvent.setup()
    renderApp(`/w/${WS}/migrations/${id}`)
    await user.click(await screen.findByRole('button', { name: 'Convert to draft' }))
    expect(await screen.findByRole('list', { name: 'Unresolved conversion issues' })).toHaveTextContent('manager: Choose a workspace group.')
  })
})
