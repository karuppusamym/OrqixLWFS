import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { api, call } from '@/api/client'
import type { TaskDetail } from '@/api/types'
import { claim } from '@/mocks/engine'
import { db, userByName } from '@/mocks/state'
import { server } from '@/test/server'
import { renderInWorkspace, signInAs, WS } from '@/test/utils'
import { TaskDelegation } from './TaskDelegation'

const ID = 'delegate-task'
const task = { id: ID, version: 7 } as TaskDetail
const target = userByName('max.manager')
const GET = '/api/v1/workspaces/:ws/tasks/:id/delegates'
const POST = '/api/v1/workspaces/:ws/tasks/:id/delegate'

async function open(onBusy = vi.fn()) {
  signInAs('mani.manager')
  const user = userEvent.setup()
  renderInWorkspace(<TaskDelegation ws={WS} task={task} busy={false} onBusy={onBusy} />)
  await user.click(await screen.findByRole('button', { name: 'Delegate to a colleague' }))
  return { user, onBusy }
}

describe('task delegation', () => {
  it('uses eligible named colleagues and requires a reason plus explicit confirmation', async () => {
    const bodies: unknown[] = []
    server.use(http.get(GET, () => HttpResponse.json([{ id: target.id, displayName: target.displayName }])), http.post(POST, async ({ request }) => {
      bodies.push(await request.json()); await delay(50)
      return HttpResponse.json({ ...task, version: 8, assignee: { id: target.id, displayName: target.displayName } })
    }))
    const { user, onBusy } = await open()
    await screen.findByRole('option', { name: 'Max Manager' })
    const confirm = screen.getByRole('button', { name: 'Confirm delegation' })
    expect(confirm).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Colleague'), target.id)
    expect(confirm).toBeDisabled()
    await user.type(screen.getByLabelText('Reason for delegation'), '  Covering my leave  ')
    expect(bodies).toHaveLength(0)
    await user.click(confirm)
    await waitFor(() => expect(onBusy).toHaveBeenCalledWith(true))
    await screen.findByRole('button', { name: 'Delegate to a colleague' })
    expect(bodies).toEqual([{ expectedVersion: 7, assigneeSubjectId: target.id, reason: 'Covering my leave' }])
    expect(onBusy.mock.calls.map(([value]) => value)).toEqual([true, false])
  })

  it('preserves the selected colleague and reason after a version conflict', async () => {
    server.use(http.get(GET, () => HttpResponse.json([{ id: target.id, displayName: target.displayName }])), http.post(POST, () => HttpResponse.json({ type: 'about:blank', title: 'Changed', status: 409, code: 'TASK_VERSION_CONFLICT' }, { status: 409 })))
    const { user, onBusy } = await open()
    await screen.findByRole('option', { name: 'Max Manager' })
    await user.selectOptions(screen.getByLabelText('Colleague'), target.id)
    await user.type(screen.getByLabelText('Reason for delegation'), 'Cover my leave')
    await user.click(screen.getByRole('button', { name: 'Confirm delegation' }))
    await screen.findByText(/Delegation could not be completed/)
    expect(screen.getByLabelText('Colleague')).toHaveValue(target.id)
    expect(screen.getByLabelText('Reason for delegation')).toHaveValue('Cover my leave')
    expect(onBusy.mock.calls.map(([value]) => value)).toEqual([true, false])
  })

  it('offers a retry when the eligible directory cannot load', async () => {
    let calls = 0
    server.use(http.get(GET, () => ++calls === 1 ? HttpResponse.json({ type: 'about:blank', title: 'Unavailable', status: 503 }, { status: 503 }) : HttpResponse.json([{ id: target.id, displayName: target.displayName }])))
    const { user } = await open()
    await user.click(await screen.findByRole('button', { name: /^Retry$/ }))
    expect(await screen.findByRole('option', { name: 'Max Manager' })).toBeInTheDocument()
  })

  it('mock API enforces eligibility, ownership, version and delegation history', async () => {
    signInAs('mani.manager')
    const run = [...db.runs.values()].find((r) => r.input.title === 'Replacement laptop')!
    const openTask = run.tasks.find((t) => t.status === 'open')!
    claim(run, openTask, userByName('mani.manager'))
    const params = { path: { workspaceId: WS, taskId: openTask.id } }
    const colleagues = await call(api.GET('/workspaces/{workspaceId}/tasks/{taskId}/delegates', { params }))
    expect(colleagues.map((c) => c.displayName)).toContain('Max Manager')
    expect(colleagues.map((c) => c.id)).not.toContain(userByName('mani.manager').id)
    const stale = await api.POST('/workspaces/{workspaceId}/tasks/{taskId}/delegate', { params, body: { expectedVersion: openTask.version - 1, assigneeSubjectId: target.id, reason: 'Cover my leave' } })
    expect(stale.response.status).toBe(409)
    const invalid = await api.POST('/workspaces/{workspaceId}/tasks/{taskId}/delegate', { params, body: { expectedVersion: openTask.version, assigneeSubjectId: userByName('fin.finance').id, reason: 'Cover my leave' } })
    expect(invalid.response.status).toBe(422)
    const delegated = await call(api.POST('/workspaces/{workspaceId}/tasks/{taskId}/delegate', { params, body: { expectedVersion: openTask.version, assigneeSubjectId: target.id, reason: 'Cover my leave' } }))
    expect(delegated.assignee?.id).toBe(target.id)
    expect(run.events.at(-1)).toMatchObject({ type: 'TASK_REASSIGNED', data: { from: 'Mani Manager', to: 'Max Manager', reason: 'Cover my leave' } })
    const again = await api.GET('/workspaces/{workspaceId}/tasks/{taskId}/delegates', { params })
    expect(again.response.status).toBe(403)
  })

  it('mock API excludes and rejects the requester when self-approval is denied', async () => {
    signInAs('mani.manager')
    const run = [...db.runs.values()].find((r) => r.input.title === 'Replacement laptop')!
    run.initiatorId = target.id
    const openTask = run.tasks.find((t) => t.status === 'open')!
    claim(run, openTask, userByName('mani.manager'))
    const params = { path: { workspaceId: WS, taskId: openTask.id } }
    const colleagues = await call(api.GET('/workspaces/{workspaceId}/tasks/{taskId}/delegates', { params }))
    expect(colleagues.map((c) => c.id)).not.toContain(target.id)
    const refused = await api.POST('/workspaces/{workspaceId}/tasks/{taskId}/delegate', { params, body: { expectedVersion: openTask.version, assigneeSubjectId: target.id, reason: 'Please take over' } })
    expect(refused.response.status).toBe(422)
    expect(openTask.assigneeId).toBe(userByName('mani.manager').id)
  })
})
