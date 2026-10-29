import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { TaskDetail } from '@/api/types'
import type { ViewDefinition } from '@/features/views/model'
import { server } from '@/test/server'
import { renderInWorkspace, signInAs, WS } from '@/test/utils'
import type { PageDefinition } from './model'
import { PageCanvas, type RuntimeData } from './PageCanvas'

const axeOptions = { rules: { 'color-contrast': { enabled: false } } }
const runtime = (overrides: Partial<RuntimeData> = {}): RuntimeData => ({ ws: WS, slug: 'files', revision: 1, contextData: {}, drafts: {}, grants: { startWorkflows: ['wf-files'], taskAct: true, readCases: false }, ...overrides })

describe('page runtime controls (S114)', () => {
  it('renders file and row controls with the request form renderer and stops a chain on invalid input before the server', async () => {
    signInAs('rhea.requester')
    let runs = 0
    server.use(
      http.get('*/api/v1/workspaces/:ws/workflows/wf-files/start-form', () => HttpResponse.json({
        workflowId: 'wf-files', workflowName: 'Files', releaseId: 'r1', releaseNumber: 1, isTest: false, inputUi: {},
        inputSchema: { type: 'object', required: ['title'], properties: {
          title: { type: 'string', title: 'Title', maxLength: 80 },
          quote: { type: 'string', title: 'Quote', format: 'attachment' },
          lines: { type: 'array', title: 'Lines', minItems: 1, maxItems: 5, items: { type: 'object', properties: { item: { type: 'string', title: 'Item' } } } },
        } },
      })),
      http.post('*/api/v1/workspaces/:ws/app-pages/:slug/chains/:chain/run', () => { runs++; return HttpResponse.json({ completed: true, navigateTo: null, steps: [] }) }),
    )
    const view: ViewDefinition = { schemaVersion: 1, kind: 'request', sections: [{ id: 's', label: 'Order', columns: 1, fields: [
      { id: 'title', label: 'Title', type: 'text', binding: 'request.title', required: true },
      { id: 'quote', label: 'Quote', type: 'file', binding: 'request.quote' },
      { id: 'lines', label: 'Lines', type: 'row', binding: 'request.lines' },
    ] }], actions: [{ id: 'submit', label: 'Submit', type: 'submit' }] }
    const page: PageDefinition = { schemaVersion: 1, kind: 'page', title: 'Files', context: 'none', layout: 'single', breakpoint: 'md', regions: [{ id: 'main', label: 'Main', components: [
      { id: 'form', type: 'view', view: { id: 'v', version: 1 }, workflowId: 'wf-files' },
      { id: 'bar', type: 'actions', label: 'Send', chains: ['send'] },
    ] }], chains: [{ id: 'send', label: 'Send it', stopOnFailure: true, steps: [{ action: 'submit', component: 'form' }] }] }
    const user = userEvent.setup()
    const { container } = renderInWorkspace(<PageCanvas page={page} views={new Map([['v:1', view]])} extensions={new Map()} runtime={runtime()} />)
    expect(await screen.findByRole('button', { name: /Choose a file/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add Lines row' })).toBeInTheDocument()
    await user.click(within(screen.getByRole('region', { name: 'Send' })).getByRole('button', { name: 'Send it' }))
    expect(await screen.findByText(/Correct the highlighted fields/)).toBeInTheDocument()
    expect(runs).toBe(0)
    await user.type(screen.getByLabelText(/^Title/), 'Chairs')
    await user.click(screen.getByRole('button', { name: 'Add Lines row' }))
    await user.click(within(screen.getByRole('region', { name: 'Send' })).getByRole('button', { name: 'Send it' }))
    await screen.findByText('Send it: completed')
    expect(runs).toBe(1)
    expect((await axe.run(container, axeOptions)).violations).toEqual([])
  }, 30_000)

  it('offers corrections only for the fields the server says are correctable, and shows rows read-only', async () => {
    signInAs('mani.manager')
    let body: unknown = null
    server.use(http.post('*/api/v1/workspaces/:ws/app-pages/:slug/chains/:chain/run', async ({ request }) => { body = await request.json(); return HttpResponse.json({ completed: true, navigateTo: null, steps: [] }) }))
    const view: ViewDefinition = { schemaVersion: 1, kind: 'approval', sections: [{ id: 's', label: 'Request', columns: 1, fields: [
      { id: 'title', label: 'Title', type: 'text', binding: 'request.title' },
      { id: 'purpose', label: 'Purpose', type: 'text', binding: 'request.purpose' },
      { id: 'lines', label: 'Lines', type: 'row', binding: 'request.lines' },
    ] }], actions: [{ id: 'approve', label: 'Approve', type: 'approve' }, { id: 'reject', label: 'Reject', type: 'reject' }, { id: 'fix', label: 'Correct', type: 'requestCorrection' }] }
    const page: PageDefinition = { schemaVersion: 1, kind: 'page', title: 'Fix', context: 'task', layout: 'single', breakpoint: 'md', regions: [{ id: 'main', label: 'Main', components: [
      { id: 'review', type: 'view', view: { id: 'v', version: 1 } },
      { id: 'bar', type: 'actions', label: 'Decide', chains: ['fix'] },
    ] }], chains: [{ id: 'fix', label: 'Correct and approve', stopOnFailure: true, steps: [{ action: 'requestCorrection', component: 'review' }, { action: 'approve', component: 'review' }] }] }
    const task = { id: 't1', version: 4, request: { input: { title: 'Laptop', purpose: 'Old', lines: [{ item: 'Dock', qty: 2 }] } }, form: { rejectReasonRequired: true, correctableFields: ['purpose'] } } as unknown as TaskDetail
    const user = userEvent.setup()
    const { container } = renderInWorkspace(<PageCanvas page={page} views={new Map([['v:1', view]])} extensions={new Map()} runtime={runtime({ context: 't1', task })} />)
    const corrections = await screen.findByRole('group', { name: 'Corrections' })
    expect(within(corrections).queryByLabelText('Title')).not.toBeInTheDocument()
    const purpose = within(corrections).getByLabelText('Purpose')
    expect(purpose).toHaveValue('Old')
    expect(screen.getByRole('table', { name: 'Lines' })).toHaveTextContent('Dock')
    await user.clear(purpose)
    await user.type(purpose, 'Replacement')
    await user.type(screen.getByLabelText('Reason (needed to reject or correct)'), 'Typo')
    await user.click(screen.getByRole('button', { name: 'Correct and approve' }))
    await screen.findByText('Correct and approve: completed')
    expect(body).toMatchObject({ context: 't1', inputs: { review: { expectedVersion: 4, reason: 'Typo', values: { purpose: 'Replacement' } } } })
    expect((await axe.run(container, axeOptions)).violations).toEqual([])
  }, 30_000)

  it('lets the person choose a plan-item occurrence and shows which steps were undone after a failure (S116)', async () => {
    signInAs('mani.manager')
    let body: { inputs?: Record<string, { planItems?: Record<string, string> }> } | null = null
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id/plan', () => HttpResponse.json({ items: [
        { id: 'p1', elementId: 'review', name: 'Review evidence', type: 'humantask', state: 'enabled', parentStageId: null, createdAt: '2026-10-29T09:00:00Z', endedAt: null, required: true, allowedActions: { start: true }, workflow: null },
        { id: 'p2', elementId: 'review', name: 'Review evidence', type: 'humantask', state: 'enabled', parentStageId: null, createdAt: '2026-10-29T10:00:00Z', endedAt: null, required: true, allowedActions: { start: true }, workflow: null },
      ], milestones: [], events: [], canClose: false })),
      http.post('*/api/v1/workspaces/:ws/app-pages/:slug/chains/:chain/run', async ({ request }) => {
        body = await request.json() as typeof body
        return HttpResponse.json({ completed: false, navigateTo: null, steps: [
          { index: 0, action: 'suspendCase', component: 'data', status: 'succeeded', detail: null, problemCode: null, runId: null, compensation: { action: 'resumeCase', status: 'succeeded', detail: null, problemCode: null } },
          { index: 1, action: 'startPlanItem', component: 'data', status: 'succeeded', detail: null, problemCode: null, runId: null, compensation: null },
          { index: 2, action: 'closeCase', component: 'data', status: 'failed', detail: 'Required work is open.', problemCode: 'VALIDATION_FAILED', runId: null, compensation: null },
        ] })
      }),
    )
    const view: ViewDefinition = { schemaVersion: 1, kind: 'case', sections: [{ id: 's', label: 'Case', columns: 1, fields: [{ id: 'summary', label: 'Summary', type: 'text', binding: 'case.summary' }] }], actions: [{ id: 'save', label: 'Save', type: 'saveData' }] }
    const page: PageDefinition = { schemaVersion: 1, kind: 'page', title: 'Case desk', context: 'case', layout: 'single', breakpoint: 'md', regions: [{ id: 'main', label: 'Main', components: [
      { id: 'data', type: 'view', view: { id: 'v', version: 1 } },
      { id: 'bar', type: 'actions', label: 'Work', chains: ['work'] },
    ] }], chains: [{ id: 'work', label: 'Start review', stopOnFailure: true, steps: [{ action: 'suspendCase', component: 'data', compensate: 'resumeCase' }, { action: 'startPlanItem', component: 'data', planItem: 'review', occurrence: 'choose' }, { action: 'closeCase', component: 'data' }] }] }
    const caseData = { fields: [], values: { summary: 'x' }, version: 3, canEdit: true, changes: [], viewBinding: null } as never
    const user = userEvent.setup()
    const { container } = renderInWorkspace(<PageCanvas page={page} views={new Map([['v:1', view]])} extensions={new Map()} runtime={runtime({ context: 'c1', caseData })} />)
    const pick = await screen.findByLabelText('Which review')
    await waitFor(() => expect(within(pick).getAllByRole('option')).toHaveLength(3))
    await user.click(screen.getByRole('button', { name: 'Start review' }))
    expect(await screen.findByText(/Choose which review to act on/)).toBeInTheDocument()
    await user.selectOptions(pick, 'p2')
    await user.type(screen.getByLabelText('Reason for the change or case action'), 'Second review')
    await user.click(screen.getByRole('button', { name: 'Start review' }))
    const outcome = await screen.findByText('Start review: stopped')
    expect(body!.inputs!.data!.planItems).toEqual({ review: 'p2' })
    const steps = within(outcome.closest('[role="status"]') as HTMLElement).getAllByRole('listitem')
    expect(steps[0]).toHaveTextContent('Resume the case: undone')
    expect(steps[1]).toHaveTextContent('not undone')
    expect((await axe.run(container, axeOptions)).violations).toEqual([])
  }, 30_000)
})
