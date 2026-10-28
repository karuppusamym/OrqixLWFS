import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockKafkaTriggers, resetCallbackMocks, seedKafkaMocks } from '@/mocks/callbacks'
import { KafkaTriggersPanel } from './KafkaTriggersPanel'

const permission = vi.hoisted(() => ({ manage: true, read: true, operate: true }))
vi.mock('@/app/workspace', () => ({
  useWorkspace: () => ({
    workspace: { id: 'workspace' },
    can: (p: string) => (p === 'connection.manage' ? permission.manage : p === 'connection.read' ? permission.read
      : p === 'run.operate' ? permission.operate : p === 'workflow.read'),
  }),
}))
vi.mock('@/api/queries', () => ({
  useWorkflows: () => ({ data: { pages: [{ items: [{ id: '00000000-0000-7000-8000-0000000000aa', name: 'Purchase approval' }] }] } }),
}))

function mount() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><KafkaTriggersPanel /></QueryClientProvider>)
}

describe('Kafka triggers panel', () => {
  beforeEach(() => { permission.manage = true; permission.read = true; permission.operate = true; resetCallbackMocks() })

  it('creates a start trigger from a named cluster and an allow-listed topic, then sends a stub test message', async () => {
    const user = userEvent.setup()
    mount()
    expect(await screen.findByTestId('kafka-mode')).toHaveTextContent('Stub mode')
    const save = screen.getByRole('button', { name: 'Save trigger' })
    expect(save).toBeDisabled()
    await user.type(screen.getByLabelText('Key'), 'orders-in')
    await user.type(screen.getByLabelText('Name'), 'Orders')
    await user.selectOptions(screen.getByLabelText('Cluster'), 'main')
    expect(within(screen.getByLabelText('Topic')).getAllByRole('option').map((o) => o.textContent)).toEqual(['Choose an allowed topic', 'orders.created', 'payments.received'])
    await user.selectOptions(screen.getByLabelText('Topic'), 'orders.created')
    await user.selectOptions(screen.getByLabelText('Workflow'), '00000000-0000-7000-8000-0000000000aa')
    await user.type(screen.getByLabelText(/Field mapping/), 'title=/title')
    await user.click(save)
    expect(await screen.findByText(/Trigger orders-in saved\. It acts as you/)).toBeVisible()
    expect(mockKafkaTriggers()[0]).toMatchObject({ key: 'orders-in', cluster: 'main', topic: 'orders.created', action: 'start', mapping: { title: '/title' }, secretRef: null })

    const row = await screen.findByTestId('kafka-trigger-orders-in')
    expect(row).toHaveTextContent('group orqix-orders-in')
    expect(row).toHaveTextContent('no replica consuming')
    await user.click(within(row).getByLabelText('Test value (JSON) for orders-in'))
    await user.paste('{"title":"Laptop"}')
    await user.click(within(row).getByRole('button', { name: 'Send test message to orders-in' }))
    expect(await screen.findByText('Test message: started.')).toBeVisible()
    await waitFor(() => expect(screen.getByTestId('kafka-trigger-orders-in')).toHaveTextContent('1 handled'))
  })

  it('shows status, last error and dead letters without any message content, and hides editing from readers', async () => {
    permission.manage = false
    seedKafkaMocks({
      mode: 'live',
      triggers: [{ id: 't1', key: 'orders-in', name: 'Orders', cluster: 'main', topic: 'orders.created', consumerGroup: 'orqix-orders-in', secretRef: null, action: 'start', workflowId: null, messageName: null, correlationPointer: null, mapping: { title: '/title' }, batchSize: 50, maxPerSecond: 20, startFrom: 'latest', runAs: 'u1', enabled: true, version: 1, createdAt: '2026-10-26T00:00:00Z', updatedAt: null,
        status: { lag: 12, handled: 40, duplicates: 2, dead: 1, lastError: 'unmapped', lastErrorAt: '2026-10-26T10:00:00Z', replicas: [{ instanceId: 'pod-a', partitions: [0], lag: 5, running: true, lastPollAt: null }, { instanceId: 'pod-b', partitions: [1], lag: 7, running: true, lastPollAt: null }] } }],
      dead: [{ id: 'd1', triggerId: 't1', triggerKey: 'orders-in', partition: 1, offset: 77, keyDigest: 'abcd', errorCode: 'unmapped', valueBytes: 120, valueSha256: null, attempts: 1, receivedAt: '2026-10-26T10:00:00Z' }],
    })
    mount()
    const row = await screen.findByTestId('kafka-trigger-orders-in')
    expect(row).toHaveTextContent('Lag 12 · 40 handled · 2 duplicates dropped · 1 dead letters')
    expect(row).toHaveTextContent('pod-a holds 0; pod-b holds 1')
    expect(row).toHaveTextContent('Last error: unmapped')
    expect(screen.getByTestId('kafka-mode')).toHaveTextContent('Live')
    expect(screen.queryByRole('form', { name: 'New Kafka trigger' })).toBeNull()
    expect(within(row).queryByRole('button')).toBeNull()
    const dead = screen.getByRole('list', { name: 'Kafka dead letters' })
    expect(dead).toHaveTextContent('orders-in · unmapped · partition 1, offset 77 · 120 bytes')
  })

  it('deletes only a paused trigger, after one more confirmation', async () => {
    const user = userEvent.setup()
    seedKafkaMocks({
      mode: 'live',
      triggers: [{ id: 't1', key: 'orders-in', name: 'Orders', cluster: 'main', topic: 'orders.created', consumerGroup: 'orqix-orders-in', secretRef: null, action: 'start', workflowId: null, messageName: null, correlationPointer: null, mapping: { title: '/title' }, batchSize: 50, maxPerSecond: 20, startFrom: 'latest', runAs: 'u1', enabled: true, version: 1, createdAt: '2026-10-26T00:00:00Z', updatedAt: null,
        status: { lag: 12, handled: 40, duplicates: 2, dead: 1, lastError: 'unmapped', lastErrorAt: '2026-10-26T10:00:00Z', replicas: [{ instanceId: 'pod-a', partitions: [0], lag: 5, running: true, lastPollAt: null }, { instanceId: 'pod-b', partitions: [1], lag: 7, running: true, lastPollAt: null }] } }],
      dead: [{ id: 'd1', triggerId: 't1', triggerKey: 'orders-in', partition: 1, offset: 77, keyDigest: 'abcd', errorCode: 'unmapped', valueBytes: 120, valueSha256: null, attempts: 1, receivedAt: '2026-10-26T10:00:00Z' }],
    })
    mount()
    await screen.findByTestId('kafka-trigger-orders-in')
    expect(screen.queryByRole('button', { name: 'Delete orders-in' })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Pause orders-in' }))
    await user.click(await screen.findByRole('button', { name: 'Delete orders-in' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: delete orders-in' }))
    expect(await screen.findByText(/Trigger orders-in deleted/)).toBeVisible()
    await waitFor(() => expect(screen.queryByTestId('kafka-trigger-orders-in')).toBeNull())
  })
})
