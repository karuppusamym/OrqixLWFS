import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderAppReady, signInAs } from '@/test/utils'
import { WS_SANDBOX } from '@/mocks/state'

const ws = WS_SANDBOX
const definitionId = '00000000-0000-7000-8000-000000000a11'
const instanceId = '00000000-0000-7000-8000-000000000a12'
const definition = { id: definitionId, key: 'review', name: 'Evidence review', version: 1, createdAt: '2026-10-17T12:00:00Z' }
const active = { id: instanceId, definitionId, definitionVersion: 1, status: 'active', version: 1,
  tasks: [{ id: 'task-1', name: 'Collect evidence', status: 'available', candidateGroups: ['case-workers'], eligible: true }], createdAt: '2026-10-17T12:00:00Z' }

describe('case workbench', () => {
  it('triggers only a server-authorized user event after a reason and stops reads if the role grant ends',async()=>{
    signInAs('fin.finance')
    let sent:unknown
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id',()=>HttpResponse.json({...active,tasks:[],canRead:true})),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id/plan',()=>HttpResponse.json({items:[
        {id:'release',elementId:'release',name:'Release review',type:'usereventlistener',state:'available',required:false,allowedActions:{occur:true},parentStageId:null,createdAt:active.createdAt,endedAt:null},
      ],milestones:[],events:[],canClose:false})),
      http.post('*/api/v1/workspaces/:ws/cases/instances/:id/plan-items/release/occur',async({request})=>{
        expect(request.headers.get('Idempotency-Key')).toBeTruthy();sent=await request.json()
        return HttpResponse.json({...active,tasks:[],version:2,canRead:false})
      }),
    )
    const user=userEvent.setup();await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await user.click(await screen.findByRole('button',{name:'Show case plan'}))
    await user.click(await screen.findByRole('button',{name:'Trigger event'}))
    expect(screen.getByRole('button',{name:'Confirm plan change'})).toBeDisabled()
    await user.type(within(screen.getByRole('dialog')).getByLabelText('Reason'),'Release verified evidence')
    await user.click(screen.getByRole('button',{name:'Confirm plan change'}))
    expect(await screen.findByText('Your work is complete. Further case details require current case read access.')).toBeInTheDocument()
    expect(sent).toEqual({expectedVersion:1,reason:'Release verified evidence'})
  })
  it('stops automatic reads when a successful command ends the worker read grant',async()=>{
    signInAs('mani.manager')
    let reads=0,planReads=0
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id',()=>{reads++;return HttpResponse.json(active)}),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id/plan',()=>{planReads++;return HttpResponse.json({items:[],milestones:[],events:[],canClose:false})}),
      http.post('*/api/v1/workspaces/:ws/cases/instances/:id/tasks/:taskId/complete',()=>HttpResponse.json({...active,status:'completed',version:2,tasks:[],canRead:false})),
    )
    const user=userEvent.setup();await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await user.click(await screen.findByRole('button',{name:'Show case plan'}))
    await screen.findByText('No plan items have started.')
    await user.click(screen.getByRole('button',{name:'Complete task'}))
    expect(await screen.findByText('Your work is complete. Further case details require current case read access.')).toBeInTheDocument()
    expect(screen.queryByRole('button',{name:'Show case plan'})).not.toBeInTheDocument()
    expect(screen.queryByRole('button',{name:'Hide case plan'})).not.toBeInTheDocument()
    expect(reads).toBe(1);expect(planReads).toBe(1)
  })

  it('requires an operator confirmation and reason before terminating case work', async () => {
    signInAs('olga.operator')
    let body: unknown
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(active)),
      http.post('*/api/v1/workspaces/:ws/cases/instances/:id/terminate', async ({ request }) => {
        expect(request.headers.get('Idempotency-Key')).toBeTruthy()
        body = await request.json()
        return HttpResponse.json({ ...active, status: 'terminated', version: 2, tasks: [] })
      }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await user.click(await screen.findByRole('button', { name: 'Terminate case' }))
    expect(screen.getByRole('button', { name: 'Confirm termination' })).toBeDisabled()
    expect(body).toBeUndefined()
    await user.type(screen.getByLabelText('Reason'), 'Duplicate request')
    await user.click(screen.getByRole('button', { name: 'Confirm termination' }))
    await waitFor(() => expect(body).toEqual({ expectedVersion: 1, reason: 'Duplicate request' }))
    expect(await screen.findByText('terminated')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Terminate case' })).not.toBeInTheDocument()
  })
  it('loads the nested case plan on demand and refreshes milestones after a task decision', async () => {
    signInAs('dana.designer')
    let completed = false, planReads = 0
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(active)),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id/plan', () => {
        planReads++
        return HttpResponse.json({ items: [
          { id: 'stage', elementId: 'evidence', name: 'Evidence phase', type: 'stage', state: completed ? 'completed' : 'active', parentStageId: null, createdAt: active.createdAt, endedAt: null },
          { id: 'child', elementId: 'collect', name: 'Collection plan item', type: 'humanTask', state: completed ? 'completed' : 'active', parentStageId: 'stage', createdAt: active.createdAt, endedAt: null },
        ], milestones: completed ? [{ id: 'milestone', elementId: 'ready', name: 'Evidence ready', achievedAt: active.createdAt }] : [] })
      }),
      http.post('*/api/v1/workspaces/:ws/cases/instances/:id/tasks/:taskId/complete', () => {
        completed = true
        return HttpResponse.json({ ...active, status: 'completed', version: 2, tasks: [] })
      }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await screen.findByRole('button', { name: 'Show case plan' })
    expect(planReads).toBe(0)
    await user.click(screen.getByRole('button', { name: 'Show case plan' }))
    expect(await screen.findByText('Evidence phase')).toBeInTheDocument()
    expect(screen.getByText(/In stage: Evidence phase/)).toBeInTheDocument()
    expect(screen.getByText('No milestones have been achieved.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Complete task' }))
    expect(await screen.findByText('Evidence ready')).toBeInTheDocument()
    expect(planReads).toBe(2)
    await user.click(screen.getByRole('button', { name: 'Hide case plan' }))
    expect(screen.getByText('Evidence ready')).not.toBeVisible()
  })

  it('requires reasons for operator holds and resumes and disables held work', async () => {
    signInAs('olga.operator')
    let current = active
    const calls: Array<{ action: string; body: unknown }> = []
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(current)),
      ...(['suspend', 'resume'] as const).map(action => http.post(`*/api/v1/workspaces/:ws/cases/instances/:id/${action}`, async ({ request }) => {
        const body = await request.json(); calls.push({ action, body })
        current = { ...current, status: action === 'suspend' ? 'suspended' : 'active', version: current.version + 1, tasks: current.tasks.map(task => ({ ...task, eligible: action === 'resume' })) }
        return HttpResponse.json(current)
      })),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await user.click(await screen.findByRole('button', { name: 'Put case on hold' }))
    expect(screen.getByRole('button', { name: 'Confirm change' })).toBeDisabled()
    await user.type(screen.getByLabelText('Reason'), 'Wait for evidence')
    await user.click(screen.getByRole('button', { name: 'Confirm change' }))
    expect(await screen.findByText('suspended')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Complete task' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Resume case' }))
    await user.type(screen.getByLabelText('Reason'), 'Evidence arrived')
    await user.click(screen.getByRole('button', { name: 'Confirm change' }))
    await screen.findByRole('button', { name: 'Put case on hold' })
    expect(calls).toEqual([{ action: 'suspend', body: { expectedVersion: 1, reason: 'Wait for evidence' } }, { action: 'resume', body: { expectedVersion: 2, reason: 'Evidence arrived' } }])
  })

  it('starts only a server-authorized manual plan item with its current case version', async () => {
    signInAs('dana.designer')
    let started = false, body: unknown
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json({ ...active, tasks: [] })),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id/plan', () => HttpResponse.json({ items: [
        { id: 'manual-item', elementId: 'manual', name: 'Manual review', type: 'humanTask', state: started ? 'active' : 'enabled', parentStageId: null, createdAt: active.createdAt, endedAt: null, allowedActions: { start: !started } },
      ], milestones: [], events: [], canClose: false })),
      http.post('*/api/v1/workspaces/:ws/cases/instances/:id/plan-items/:item/start', async ({ request }) => {
        expect(request.headers.get('Idempotency-Key')).toBeTruthy(); body = await request.json(); started = true
        return HttpResponse.json({ ...active, version: 2 })
      }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await user.click(await screen.findByRole('button', { name: 'Show case plan' }))
    const row = (await screen.findByText('Manual review')).closest('li')!
    await user.click(within(row).getByRole('button', { name: 'Start task' }))
    expect(screen.getByRole('button', { name: 'Confirm plan change' })).toBeDisabled()
    await user.type(screen.getByLabelText('Reason'), 'Begin manual review')
    await user.click(screen.getByRole('button', { name: 'Confirm plan change' }))
    expect(await screen.findByRole('button', { name: 'Complete task' })).toBeInTheDocument()
    expect(body).toEqual({ expectedVersion: 1, reason: 'Begin manual review' })
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Start task' })).not.toBeInTheDocument())
  })

  it('lists published case types to start, without a create form, and links authors to the case designer', async () => {
    signInAs('dana.designer')
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/definitions', () => HttpResponse.json([definition])),
      http.get('*/api/v1/workspaces/:ws/cases/instances', () => HttpResponse.json([])),
      http.post('*/api/v1/workspaces/:ws/cases/definitions/:id/instances', ({ request }) => {
        expect(request.headers.get('Idempotency-Key')).toBeTruthy()
        return HttpResponse.json(active, { status: 201 })
      }),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(active)),
    )
    const user = userEvent.setup(); const { router } = await renderAppReady(`/w/${ws}/cases`)
    const types = await screen.findByRole('region', { name: 'Case types' })
    expect(within(types).getByRole('heading', { name: 'Case types' })).toBeInTheDocument()
    expect(await within(types).findByRole('cell', { name: 'Evidence review' })).toBeInTheDocument()
    // Authoring moved to the case designer page (S126): no key, name, CMMN upload or create button here.
    expect(screen.queryByText('Create case definition')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Key')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/CMMN file/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create definition' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Export CMMN' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Design case types' })).toHaveAttribute('href', `/w/${ws}/case-designs`)
    await user.click(screen.getByRole('button', { name: 'Start case' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/w/${ws}/cases/${instanceId}`))
    expect(await screen.findByText('Collect evidence')).toBeInTheDocument()
  })

  it('does not offer the case designer to people who only work cases', async () => {
    signInAs('rhea.requester')
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/definitions', () => HttpResponse.json([])),
      http.get('*/api/v1/workspaces/:ws/cases/instances', () => HttpResponse.json([])),
    )
    await renderAppReady(`/w/${ws}/cases`)
    expect(await screen.findByText('No case types are published in this workspace.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Design case type/ })).not.toBeInTheDocument()
  })

  it('completes only eligible tasks with the current case version', async () => {
    signInAs('dana.designer')
    let body: unknown
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(active)),
      http.post('*/api/v1/workspaces/:ws/cases/instances/:id/tasks/:taskId/complete', async ({ request }) => {
        expect(request.headers.get('Idempotency-Key')).toBeTruthy()
        body = await request.json()
        return HttpResponse.json({ ...active, status: 'completed', version: 2, tasks: [] })
      }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await user.click(await screen.findByRole('button', { name: 'Complete task' }))
    await waitFor(() => expect(body).toEqual({ expectedVersion: 1 }))
    expect(await screen.findByText('No tasks are currently available.')).toBeInTheDocument()
  })
  it('collects typed initial case data before starting a pinned definition', async () => {
    signInAs('rhea.requester')
    let body: unknown
    const dataFields = [{ key: 'amount', label: 'Claim amount', type: 'number', required: true }, { key: 'confirmed', label: 'Confirmed', type: 'boolean' }]
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/definitions', () => HttpResponse.json([{ ...definition, dataFields }])),
      http.get('*/api/v1/workspaces/:ws/cases/instances', () => HttpResponse.json([])),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(active)),
      http.post('*/api/v1/workspaces/:ws/cases/definitions/:id/instances', async ({ request }) => { body = await request.json(); return HttpResponse.json(active, { status: 201 }) }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases`)
    await user.click(await screen.findByRole('button', { name: /^Start case$/ }))
    expect(body).toBeUndefined()
    const dialog = screen.getByRole('dialog', { name: /^Start case$/ })
    await user.type(within(dialog).getByLabelText(/^Claim amount/), '12.25')
    // Yes-or-no case fields are checkboxes, as on the request form (docs/build/S125).
    await user.click(within(dialog).getByLabelText('Confirmed'))
    await user.click(within(dialog).getByRole('button', { name: 'Start case with data' }))
    await waitFor(() => expect(body).toEqual({ data: { amount: 12.25, confirmed: true } }))
  })

  it('saves typed data with a reason and case version, then renders protected history', async () => {
    signInAs('olga.operator')
    let body: unknown, updated = false
    const fields = [{ key: 'amount', label: 'Claim amount', type: 'number', required: true }]
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json({ ...active, version: updated ? 2 : 1 })),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id/data', () => HttpResponse.json({ fields, values: { amount: updated ? 20 : 12.25 }, version: updated ? 2 : 1, canEdit: true, changes: updated ? [{ version: 2, before: { amount: 12.25 }, after: { amount: 20 } }] : [] })),
      http.post('*/api/v1/workspaces/:ws/cases/instances/:id/data', async ({ request }) => { expect(request.headers.get('Idempotency-Key')).toBeTruthy(); body = await request.json(); updated = true; return HttpResponse.json({ ...active, version: 2 }) }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await user.click(await screen.findByRole('button', { name: 'Show case data' }))
    const input = await screen.findByLabelText(/^Claim amount/)
    await user.clear(input); await user.type(input, '20')
    expect(screen.getByRole('button', { name: 'Save case data' })).toBeDisabled()
    await user.type(screen.getByLabelText('Reason for data change'), 'Correct evidence amount')
    await user.click(screen.getByRole('button', { name: 'Save case data' }))
    await waitFor(() => expect(body).toEqual({ expectedVersion: 1, reason: 'Correct evidence amount', values: { amount: 20 } }))
    expect(await screen.findByRole('heading', { name: 'Data changes' })).toBeInTheDocument()
    expect(screen.getByText('12.25 \u2192 20')).toBeInTheDocument()
  })

  it('renders held case data without edit controls', async () => {
    signInAs('mani.manager')
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json({ ...active, status: 'suspended', tasks: active.tasks.map(task => ({ ...task, eligible: false })) })),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id/data', () => HttpResponse.json({ fields: [{ key: 'summary', label: 'Summary', type: 'text' }], values: { summary: 'Awaiting evidence' }, version: 1, canEdit: false, changes: [] })),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await user.click(await screen.findByRole('button', { name: 'Show case data' }))
    expect(await screen.findByLabelText('Summary')).toHaveValue('Awaiting evidence')
    expect(screen.getByLabelText('Summary')).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Save case data' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Data changes' })).not.toBeInTheDocument()
  })

})
