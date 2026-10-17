import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { api, call } from '@/api/client'
import type { TaskDetail } from '@/api/types'
import { claim } from '@/mocks/engine'
import { db, userByName } from '@/mocks/state'
import { server } from '@/test/server'
import { renderInWorkspace, signInAs, WS } from '@/test/utils'
import { TaskPriority } from './TaskPriority'
import { taskAge } from './taskAge'

const task = { id: 'priority-task', runId: 'priority-run', version: 7, priority: 20 } as TaskDetail
const URL = '/api/v1/workspaces/:ws/tasks/:id/priority'

describe('task priority', () => {
  it('binds confirmation to the version reviewed when the form opened despite background refresh', async () => {
    signInAs('mani.manager')
    const bodies: unknown[] = []
    server.use(http.put(URL, async ({ request }) => { bodies.push(await request.json()); return HttpResponse.json({ ...task, priority: 80, version: 9 }) }))
    function RefreshingTask() {
      const [current, setCurrent] = useState(task)
      return <><button onClick={() => setCurrent({ ...task, version: 8, priority: 90 })}>Simulate background refresh</button><TaskPriority ws={WS} task={current} busy={false} onBusy={() => undefined} /></>
    }
    const user = userEvent.setup()
    renderInWorkspace(<RefreshingTask />)
    await user.click(await screen.findByRole('button', { name: 'Change priority (20)' }))
    await user.type(screen.getByLabelText('Reason for priority change'), 'Reviewed prior priority')
    await user.click(screen.getByRole('button', { name: 'Simulate background refresh' }))
    await user.click(screen.getByRole('button', { name: 'Confirm priority change' }))
    await waitFor(() => expect(bodies).toEqual([{ priority: 20, expectedVersion: 7, reason: 'Reviewed prior priority' }]))
    await user.click(await screen.findByRole('button', { name: 'Change priority (90)' }))
    expect(screen.getByLabelText('Task priority (0 to 100)')).toHaveValue(90)
    expect(screen.getByLabelText('Reason for priority change')).toHaveValue('')
  })
  it('requires a bounded priority and reason and sends the current version on confirmation', async () => {
    signInAs('mani.manager')
    const bodies: unknown[] = []
    server.use(http.put(URL, async ({request}) => { bodies.push(await request.json()); return HttpResponse.json({...task, priority:80, version:8}) }))
    const busy = vi.fn()
    const user = userEvent.setup()
    renderInWorkspace(<TaskPriority ws={WS} task={task} busy={false} onBusy={busy} />)
    await user.click(await screen.findByRole('button', {name:'Change priority (20)'}))
    const confirm = screen.getByRole('button', {name:'Confirm priority change'})
    expect(confirm).toBeDisabled()
    await user.clear(screen.getByLabelText('Task priority (0 to 100)'))
    await user.type(screen.getByLabelText('Task priority (0 to 100)'), '101')
    expect(confirm).toBeDisabled()
    await user.clear(screen.getByLabelText('Task priority (0 to 100)'))
    await user.type(screen.getByLabelText('Task priority (0 to 100)'), '80')
    await user.type(screen.getByLabelText('Reason for priority change'), '  Customer waiting  ')
    expect(bodies).toHaveLength(0)
    await user.click(confirm)
    await waitFor(() => expect(bodies).toEqual([{priority:80, expectedVersion:7, reason:'Customer waiting'}]))
    await waitFor(() => expect(busy.mock.calls.map(([value]) => value)).toEqual([true,false]))
  })
  it('keeps the form after a conflict', async () => {
    signInAs('mani.manager')
    server.use(http.put(URL, () => HttpResponse.json({type:'about:blank',title:'Changed',status:409,code:'TASK_VERSION_CONFLICT'}, {status:409})))
    const user = userEvent.setup()
    renderInWorkspace(<TaskPriority ws={WS} task={task} busy={false} onBusy={vi.fn()} />)
    await user.click(await screen.findByRole('button', {name:'Change priority (20)'}))
    await user.type(screen.getByLabelText('Reason for priority change'),'Urgent')
    await user.click(screen.getByRole('button',{name:'Confirm priority change'}))
    await screen.findByText(/Priority could not be changed/)
    expect(screen.getByLabelText('Reason for priority change')).toHaveValue('Urgent')
    expect(screen.getByLabelText('Task priority (0 to 100)')).toHaveValue(20)
  })
  it('mock enforces owner/version, retains event reason and binds pagination filters', async () => {
    signInAs('mani.manager')
    const run = [...db.runs.values()].find(r => r.input.title === 'Replacement laptop')!
    const open = run.tasks.find(t => t.status === 'open')!
    claim(run,open,userByName('mani.manager'))
    const params={path:{workspaceId:WS,taskId:open.id}}
    const body={priority:80,expectedVersion:open.version,reason:'Urgent request'}
    const changed=await call(api.PUT('/workspaces/{workspaceId}/tasks/{taskId}/priority',{params,body}))
    expect(changed.priority).toBe(80)
    expect(run.events.at(-1)?.data.reason).toBe('Urgent request')
    await expect(call(api.PUT('/workspaces/{workspaceId}/tasks/{taskId}/priority',{params,body}))).rejects.toMatchObject({code:'TASK_VERSION_CONFLICT'})
    const duplicate = {...open, id:'priority-second', priority:90}
    run.tasks.push(duplicate)
    const first = await call(api.GET('/workspaces/{workspaceId}/tasks',{params:{path:{workspaceId:WS},query:{view:'assigned',sort:'priority',minPriority:80,limit:1}}}))
    expect(first.items[0]?.id).toBe(duplicate.id)
    const next = await call(api.GET('/workspaces/{workspaceId}/tasks',{params:{path:{workspaceId:WS},query:{view:'assigned',sort:'priority',minPriority:80,limit:1,cursor:first.nextCursor!}}}))
    expect(next.items[0]?.id).toBe(open.id)
    await expect(call(api.GET('/workspaces/{workspaceId}/tasks',{params:{path:{workspaceId:WS},query:{view:'assigned',sort:'oldest',minPriority:80,cursor:first.nextCursor!}}}))).rejects.toMatchObject({code:'INVALID_CURSOR'})
    signInAs('max.manager')
    await expect(call(api.PUT('/workspaces/{workspaceId}/tasks/{taskId}/priority',{params,body:{...body,expectedVersion:open.version}}))).rejects.toMatchObject({code:'FORBIDDEN'})
  })
  it('calculates elapsed creation time without implying a due date', () => {
    expect(taskAge('2026-01-01T00:00:00Z',Date.parse('2026-01-02T03:00:00Z'))).toBe('Open for 1d 3h')
    expect(taskAge('2026-01-03T00:00:00Z',Date.parse('2026-01-02T00:00:00Z'))).toBe('Open for 0h')
    expect(taskAge('bad')).toBe('Age unavailable')
  })
})
