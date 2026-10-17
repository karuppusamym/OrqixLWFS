import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderInWorkspace, signInAs, WS } from '@/test/utils'
import { CaseControlsPanel } from './CaseControlsPanel'

describe('stage mail operator status', () => {
  it('loads delivery evidence only on demand and distinguishes server acceptance from receipt', async () => {
    signInAs('olga.operator'); let reads = 0
    server.use(http.get('/api/v1/workspaces/:ws/runs/:id/mail', () => { reads++; return HttpResponse.json({ items: [{ id: 'notification', event: 'assignment', status: 'providerAccepted', attempts: 1, createdAt: '2026-10-17T10:00:00Z', updatedAt: '2026-10-17T10:00:01Z' }] }) }))
    renderInWorkspace(<CaseControlsPanel runId="run" />, `/w/${WS}/test`)
    const button = await screen.findByRole('button', { name: 'Inspect stage email' }); expect(reads).toBe(0)
    await userEvent.click(button)
    expect(await screen.findByText(/assignment: Accepted by mail server/)).toBeInTheDocument()
    expect(screen.getByText(/does not confirm receipt/)).toBeInTheDocument(); expect(reads).toBe(1)
  })
  it('does not expose controls to a requester', async () => {
    signInAs('rhea.requester'); renderInWorkspace(<><p>Case details</p><CaseControlsPanel runId="run" /></>)
    await screen.findByText('Case details')
    expect(screen.queryByRole('button', { name: 'Inspect stage email' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download JSON evidence' })).not.toBeInTheDocument()
  })
})
