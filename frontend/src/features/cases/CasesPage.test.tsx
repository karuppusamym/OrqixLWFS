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

  it('uploads a reviewed model and starts a case from the returned definition', async () => {
    signInAs('dana.designer')
    let submitted: unknown
    let definitions: typeof definition[] = []
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/definitions', () => HttpResponse.json(definitions)),
      http.get('*/api/v1/workspaces/:ws/cases/instances', () => HttpResponse.json([])),
      http.post('*/api/v1/workspaces/:ws/cases/definitions', async ({ request }) => {
        submitted = await request.json(); definitions = [definition]
        return HttpResponse.json(definition, { status: 201 })
      }),
      http.post('*/api/v1/workspaces/:ws/cases/definitions/:id/instances', ({ request }) => {
        expect(request.headers.get('Idempotency-Key')).toBeTruthy()
        return HttpResponse.json(active, { status: 201 })
      }),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(active)),
    )
    const user = userEvent.setup(); const { router } = await renderAppReady(`/w/${ws}/cases`)
    await screen.findByText('No case definitions are available in this workspace.')
    await user.type(screen.getByLabelText('Key'), 'review')
    await user.type(screen.getByLabelText('Name'), 'Evidence review')
    const file = new File(['<definitions/>'], 'review.cmmn', { type: 'application/xml' })
    Object.defineProperty(file, 'text', { value: async () => '<definitions/>' })
    await user.upload(screen.getByLabelText(/CMMN file/), file)
    await user.click(screen.getByRole('button', { name: 'Create definition' }))
    expect(submitted).toMatchObject({ key: 'review', name: 'Evidence review', sourceXml: '<definitions/>' })
    await user.click(await screen.findByRole('button', { name: 'Start case' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/w/${ws}/cases/${instanceId}`))
    expect(await screen.findByText('Collect evidence')).toBeInTheDocument()
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
})
