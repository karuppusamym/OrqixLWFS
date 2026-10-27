import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { mockWebhookDeliveries, resetCallbackMocks, seedCallbackMocks } from '@/mocks/callbacks'
import { WebhooksPanel } from './WebhooksPanel'

const permission = vi.hoisted(() => ({ manage: true, read: true, operate: true }))
vi.mock('@/app/workspace', () => ({
  useWorkspace: () => ({
    workspace: { id: 'workspace' },
    can: (p: string) => (p === 'connection.manage' ? permission.manage : p === 'connection.read' ? permission.read : p === 'run.operate' ? permission.operate : false),
  }),
}))

function mount() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><WebhooksPanel /></QueryClientProvider>)
}

const dead = {
  id: '00000000-0000-7000-8000-00000000d001', subscriptionId: '00000000-0000-7000-8000-00000000c001', subscriptionName: 'Ops feed',
  eventType: 'request.completed' as const, runId: null, status: 'dead' as const, attempts: 8, lastStatusCode: 503, lastError: 'http_status',
  nextAttemptAt: '2026-10-26T10:00:00Z', createdAt: '2026-10-26T09:00:00Z', deliveredAt: null, deadAt: '2026-10-26T10:00:00Z', replays: 0,
}

describe('outbound webhooks panel', () => {
  beforeEach(() => { permission.manage = true; permission.read = true; permission.operate = true; resetCallbackMocks() })

  it('creates a subscription with a secret reference and chosen events only', async () => {
    const user = userEvent.setup(); const bodies: Record<string, unknown>[] = []
    server.use(http.post('*/api/v1/workspaces/:ws/webhook-subscriptions', async ({ request }) => {
      const body = await request.json() as Record<string, unknown>; bodies.push(body)
      return HttpResponse.json({ id: 's1', name: body.name, url: body.url, secretRef: body.secretRef, events: body.events, enabled: true, version: 1, pending: 0, dead: 0, createdAt: '2026-10-26T00:00:00Z', updatedAt: null }, { status: 201 })
    }))
    mount()
    expect(await screen.findByText('No subscriptions yet.')).toBeVisible()
    const save = screen.getByRole('button', { name: 'Save subscription' })
    expect(save).toBeDisabled()
    await user.type(screen.getByLabelText('Name'), 'Ops feed')
    await user.type(screen.getByLabelText('Endpoint URL'), 'https://hooks.example.com/orqix')
    await user.type(screen.getByLabelText('Signing secret reference'), 'ops-hook')
    await user.click(screen.getByRole('checkbox', { name: 'A request needs attention' }))
    await user.click(save)
    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toEqual({ name: 'Ops feed', url: 'https://hooks.example.com/orqix', secretRef: 'ops-hook', events: ['request.completed', 'request.needs-attention'] })
    expect(await screen.findByText('Subscription Ops feed saved.')).toBeVisible()
  })

  it('shows dead letters to operators and replays one', async () => {
    seedCallbackMocks({ deliveries: [dead] })
    const user = userEvent.setup()
    mount()
    const list = await screen.findByRole('list', { name: 'Dead letters' })
    expect(within(list).getByText(/8 attempts · last answer 503/)).toBeVisible()
    await user.click(within(list).getByRole('button', { name: 'Replay the delivery to Ops feed' }))
    expect(await screen.findByText('Delivery queued again for Ops feed.')).toBeVisible()
    expect(mockWebhookDeliveries()[0]?.status).toBe('pending')
    await waitFor(() => expect(screen.getByText('No failed deliveries.')).toBeVisible())
  })

  it('keeps the form and the secret reference away from people who cannot manage connections', async () => {
    permission.manage = false; permission.operate = false
    seedCallbackMocks({ subscriptions: [{ id: 's1', name: 'Ops feed', url: 'https://hooks.example.com/orqix', secretRef: null, events: ['request.started'], enabled: true, version: 1, pending: 2, dead: 0, createdAt: '2026-10-26T00:00:00Z', updatedAt: null }] })
    mount()
    expect(await screen.findByText('Ops feed')).toBeVisible()
    expect(screen.getByText('2 waiting to be sent · 0 dead letters')).toBeVisible()
    expect(screen.queryByRole('form', { name: 'New webhook subscription' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Pause/ })).toBeNull()
    expect(screen.queryByText('Dead letters')).toBeNull()
  })
})
