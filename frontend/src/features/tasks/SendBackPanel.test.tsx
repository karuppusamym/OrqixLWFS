import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { TaskDetail } from '@/api/types'
import { server } from '@/test/server'
import { renderInWorkspace, signInAs, WS } from '@/test/utils'
import { SendBackAnswer } from '@/features/runs/SendBackAnswer'
import { SendBackPanel } from './SendBackPanel'

const axeOptions = { rules: { 'color-contrast': { enabled: false } } }
const fields = [{ key: 'purpose', label: 'Business purpose' }] as never

describe('send back to the requester (S116)', () => {
  it('lets the holder send the request back with a reason, then shows it waiting with a withdraw action', async () => {
    signInAs('mani.manager')
    let sent: unknown = null
    server.use(http.post('*/api/v1/workspaces/:ws/tasks/:id/send-back', async ({ request }) => { sent = await request.json(); return HttpResponse.json({}) }))
    const me = await (await fetch('/api/v1/me')).json().catch(() => null) as { subject?: { id: string } } | null
    const task = { id: 't1', version: 3, status: 'open', assignee: { id: me?.subject?.id ?? 'unknown', displayName: 'Mani' }, form: { rejectReasonRequired: true, correctableFields: ['purpose'], sendBack: null } } as unknown as TaskDetail
    const user = userEvent.setup()
    const { container } = renderInWorkspace(<SendBackPanel ws={WS} task={task} fields={fields} onDone={async () => undefined} />)
    await user.click(await screen.findByRole('button', { name: 'Send back to the requester' }))
    const send = screen.getByRole('button', { name: 'Send back' })
    expect(send).toBeDisabled()
    await user.type(screen.getByLabelText('What should the requester change?'), 'Explain the purpose')
    await user.click(send)
    await screen.findByRole('button', { name: 'Send back to the requester' })
    expect(sent).toEqual({ expectedVersion: 3, reason: 'Explain the purpose' })
    expect((await axe.run(container, axeOptions)).violations).toEqual([])
  })

  it('shows the requester the returned fields and sends only changed values with the task version', async () => {
    signInAs('rhea.requester')
    let answered: unknown = null
    server.use(
      http.get('*/api/v1/workspaces/:ws/runs/:id/send-backs', () => HttpResponse.json({ items: [{ id: 'sb1', taskId: 't1', nodeId: 'manager', stepLabel: 'Manager review', status: 'open', reason: 'Explain the purpose', fields: [{ key: 'purpose', title: 'Business purpose', schema: { type: 'string' }, value: 'Device failed' }, { key: 'amount', title: 'Amount', schema: { type: 'number' }, value: 120 }], sentBy: { id: 'm', displayName: 'Mani Manager' }, sentAt: '2026-10-29T10:00:00Z', closedAt: null, note: null, taskVersion: 4, canAnswer: true }] })),
      http.post('*/api/v1/workspaces/:ws/runs/:id/send-backs/:sb/answer', async ({ request }) => { answered = await request.json(); return HttpResponse.json({}) }),
    )
    const user = userEvent.setup()
    const { container } = renderInWorkspace(<SendBackAnswer runId="r1" />)
    const purpose = await screen.findByLabelText('Business purpose')
    expect(screen.getByText(/Explain the purpose/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send corrections' })).toBeDisabled()
    await user.clear(purpose)
    await user.type(purpose, 'Laptop for the new hire')
    await user.click(screen.getByRole('button', { name: 'Send corrections' }))
    await expect.poll(() => answered).toEqual({ expectedVersion: 4, changes: { purpose: 'Laptop for the new hire' } })
    expect((await axe.run(container, axeOptions)).violations).toEqual([])
  })
})
