import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { mockInboundEvents, resetAdapterMocks, seedAdapterMocks } from '@/mocks/adapters'
import { eventSummary, InboundEventsPanel } from './InboundEventsPanel'

const permission = vi.hoisted(() => ({ start: true }))
vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ workspace: { id: 'workspace' }, can: () => permission.start }) }))
vi.mock('@/api/queries', () => ({ useWorkflows: () => ({ data: { pages: [{ items: [{ id: 'wf-1', name: 'Purchase approval' }] }] } }) }))

const event = (eventId: string, status: 'received' | 'started') => ({ id: `id-${eventId}`, triggerId: 't1', triggerKey: 'orders-in', workflowId: 'wf-1', eventId, status,
  input: { title: 'Replacement laptop', amount: 1200 }, runId: status === 'started' ? 'run-9' : null, receivedAt: '2026-10-18T10:00:00Z' })

function mount() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><InboundEventsPanel /></MemoryRouter></QueryClientProvider>)
}

describe('received events', () => {
  beforeEach(() => { permission.start = true; resetAdapterMocks() })

  it('summarizes mapped values', () => {
    expect(eventSummary({ title: 'Laptop', amount: 5 })).toBe('Title: Laptop · Amount: 5')
  })

  it('lists events and starts a request from one as the signed-in person', async () => {
    seedAdapterMocks({ events: [event('evt-1', 'received'), event('evt-0', 'started')] })
    const user = userEvent.setup()
    mount()
    expect(await screen.findByText('Received events')).toBeVisible()
    expect(screen.getAllByText('Purchase approval')).toHaveLength(2)
    expect(screen.getByRole('link', { name: /Started/ })).toHaveAttribute('href', '/w/workspace/runs/run-9')
    await user.click(screen.getByRole('button', { name: 'Start a request from event evt-1' }))
    expect(await screen.findByText('The request was started.')).toBeVisible()
    expect(mockInboundEvents().find((e) => e.eventId === 'evt-1')?.status).toBe('started')
  })

  it('shows a refusal from the server', async () => {
    seedAdapterMocks({ events: [event('evt-2', 'received')] })
    server.use(http.post('*/api/v1/workspaces/:ws/inbound-events/:id/start', () => HttpResponse.json(
      { type: 'about:blank', title: 'You are not allowed to do this', status: 403, code: 'FORBIDDEN', correlationId: 'c1' },
      { status: 403, headers: { 'Content-Type': 'application/problem+json' } })))
    const user = userEvent.setup()
    mount()
    await user.click(await screen.findByRole('button', { name: 'Start a request from event evt-2' }))
    expect(await screen.findByRole('alert')).toBeVisible()
  })

  it('stays hidden without events or without start rights', async () => {
    mount()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByText('Received events')).not.toBeInTheDocument()
    permission.start = false
    seedAdapterMocks({ events: [event('evt-3', 'received')] })
    mount()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByText('Received events')).not.toBeInTheDocument()
  })
})
