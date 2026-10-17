import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { resetAdapterMocks } from '@/mocks/adapters'
import { AdaptersPanel, buildAdapterConfig, parseMapping, type AdapterFields } from './AdaptersPanel'

const permission = vi.hoisted(() => ({ manage: true }))
vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ workspace: { id: 'workspace' }, can: () => permission.manage }) }))
vi.mock('@/api/queries', () => ({
  useWorkflows: () => ({ data: { pages: [{ items: [{ id: '00000000-0000-7000-8000-0000000000aa', name: 'Purchase approval' }] }] } }),
}))

const empty: AdapterFields = { from: '', domains: '', baseUrl: '', statement: '', parameters: '', maxRows: '', topics: '' }
function mount() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><AdaptersPanel /></QueryClientProvider>)
}

describe('adapter configuration builders', () => {
  it('builds only the closed fields of each adapter kind and never a secret', () => {
    expect(buildAdapterConfig('email', { ...empty, from: ' a@example.com ', domains: 'example.com, partner.example' }))
      .toEqual({ from: 'a@example.com', allowedRecipientDomains: ['example.com', 'partner.example'] })
    expect(buildAdapterConfig('teams', { ...empty, baseUrl: ' https://teams.example ' })).toEqual({ baseUrl: 'https://teams.example' })
    expect(buildAdapterConfig('jdbc', { ...empty, statement: 'SELECT 1', parameters: 'a b', maxRows: '25' }))
      .toEqual({ statement: 'SELECT 1', parameters: ['a', 'b'], maxRows: 25 })
    expect(buildAdapterConfig('kafka', { ...empty, topics: 'orders.created\norders.updated' })).toEqual({ allowedTopics: ['orders.created', 'orders.updated'] })
  })
  it('parses field=/pointer lines and rejects malformed ones', () => {
    expect(parseMapping('title=/value/title\n amount = /value/amount ')).toEqual({ title: '/value/title', amount: '/value/amount' })
    expect(parseMapping('')).toBeNull()
    expect(parseMapping('no-separator')).toBeNull()
  })
})

describe('adapters and inbound triggers panel', () => {
  beforeEach(() => { permission.manage = true; resetAdapterMocks() })

  it('saves an adapter with a secret reference only and sends a test message to the local stub', async () => {
    const user = userEvent.setup(); const created: Record<string, unknown>[] = []; const sent: Record<string, unknown>[] = []
    server.use(
      http.post('*/api/v1/workspaces/:ws/adapters', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>; created.push(body)
        return HttpResponse.json({ id: 'a1', key: body.key, kind: body.kind, name: body.name, secretRef: body.secretRef, config: body.config, enabled: true, createdAt: '2026-10-17T00:00:00Z' }, { status: 201 })
      }),
      http.get('*/api/v1/workspaces/:ws/adapters', () => HttpResponse.json({ items: created.length ? [{ id: 'a1', key: 'orders-out', kind: 'kafka', name: 'Orders out', secretRef: 'kafka-creds', config: {}, enabled: true, createdAt: '2026-10-17T00:00:00Z' }] : [] })),
      http.post('*/api/v1/workspaces/:ws/adapters/:key/test-send', async ({ request }) => {
        sent.push(await request.json() as Record<string, unknown>)
        return HttpResponse.json({ adapter: 'orders-out', kind: 'kafka', outcome: 'stubbed', detail: { stub: true } })
      }),
    )
    mount()
    expect(await screen.findByText('No adapters are defined in this workspace.')).toBeVisible()
    expect(screen.queryByLabelText(/password/i)).not.toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Save adapter' })
    expect(save).toBeDisabled()
    await user.type(screen.getByLabelText('Adapter key'), 'orders-out')
    await user.type(screen.getByLabelText('Adapter name'), 'Orders out')
    await user.type(screen.getByLabelText('Secret reference name'), 'kafka-creds')
    await user.type(screen.getByLabelText('Approved topics to send to'), 'orders.created')
    await user.click(save)
    await screen.findByText(/only deliver to the local test stub/)
    expect(created).toEqual([{ key: 'orders-out', kind: 'kafka', name: 'Orders out', secretRef: 'kafka-creds', config: { allowedTopics: ['orders.created'] } }])
    await user.click(await screen.findByRole('button', { name: /Prepare test message for orders-out/ }))
    await user.click(screen.getByRole('button', { name: 'Send to local stub' }))
    await screen.findByText(/accepted by the local stub .*Nothing was sent to a remote system/)
    expect(sent).toHaveLength(1)
    expect(sent[0]).toMatchObject({ payload: { topic: 'topic.name' } })
  })

  it('shows a server refusal with its explanation and keeps the form', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/api/v1/workspaces/:ws/adapters', () => HttpResponse.json({
      type: 'about:blank', title: 'Some values are not valid', status: 422, code: 'VALIDATION_FAILED', correlationId: 'c1',
      detail: 'Use exact topic names; wildcards and patterns are not allowed.',
    }, { status: 422, headers: { 'Content-Type': 'application/problem+json' } })))
    mount()
    await user.type(await screen.findByLabelText('Adapter key'), 'orders-out')
    await user.type(screen.getByLabelText('Adapter name'), 'Orders out')
    await user.type(screen.getByLabelText('Secret reference name'), 'kafka-creds')
    await user.type(screen.getByLabelText('Approved topics to send to'), 'orders.*')
    await user.click(screen.getByRole('button', { name: 'Save adapter' }))
    expect(await screen.findByText(/wildcards and patterns are not allowed/)).toBeVisible()
    expect(screen.getByLabelText('Approved topics to send to')).toHaveValue('orders.*')
  })

  it('creates an inbound trigger with a mapping and shows the signed receive path to managers', async () => {
    const user = userEvent.setup(); const created: Record<string, unknown>[] = []
    server.use(http.post('*/api/v1/workspaces/:ws/inbound-triggers', async ({ request }) => {
      const body = await request.json() as Record<string, unknown>; created.push(body)
      return HttpResponse.json({ id: 't1', ...body, enabled: true, receivePath: '/api/v1/workspaces/workspace/inbound/t1', createdAt: '2026-10-17T00:00:00Z' }, { status: 201 })
    }), http.get('*/api/v1/workspaces/:ws/inbound-triggers', () => HttpResponse.json({ items: created.length ? [{ id: 't1', key: 'orders-in', source: 'kafka', name: 'Orders feed', workflowId: '00000000-0000-7000-8000-0000000000aa', secretRef: 'in-secret', filter: {}, mapping: {}, enabled: true, receivePath: '/api/v1/workspaces/workspace/inbound/t1', createdAt: '2026-10-17T00:00:00Z' }] : [] })))
    mount()
    await screen.findByText('No inbound triggers are defined in this workspace.')
    const save = screen.getByRole('button', { name: 'Save trigger' })
    await user.type(screen.getByLabelText('Trigger key'), 'orders-in')
    await user.type(screen.getByLabelText('Trigger name'), 'Orders feed')
    await user.selectOptions(screen.getByLabelText('Workflow to start'), 'Purchase approval')
    await user.type(screen.getByLabelText('Signing secret reference name'), 'in-secret')
    await user.type(screen.getByLabelText('Approved topics to receive from'), 'purchases')
    expect(save).toBeDisabled()
    await user.type(screen.getByLabelText(/Start form fields/), 'title=/value/title')
    await waitFor(() => expect(save).toBeEnabled())
    await user.click(save)
    await screen.findByText(/never start a request by themselves/)
    expect(created).toEqual([{ key: 'orders-in', source: 'kafka', name: 'Orders feed', workflowId: '00000000-0000-7000-8000-0000000000aa', secretRef: 'in-secret', filter: { allowedTopics: ['purchases'] }, mapping: { title: '/value/title' } }])
    expect(await screen.findByText(/inbound\/t1/)).toBeVisible()
  })

  it('offers read-only lists without forms or test actions to people who cannot manage connections', async () => {
    permission.manage = false
    server.use(http.get('*/api/v1/workspaces/:ws/adapters', () => HttpResponse.json({ items: [{ id: 'a1', key: 'orders-out', kind: 'kafka', name: 'Orders out', secretRef: null, config: {}, enabled: true, createdAt: '2026-10-17T00:00:00Z' }] })))
    mount()
    expect(await screen.findByText('Orders out')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Save adapter' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Prepare test message/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save trigger' })).not.toBeInTheDocument()
  })
})
