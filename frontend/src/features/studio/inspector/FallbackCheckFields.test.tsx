import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as queries from '@/api/queries'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import orderStatus from '../../../../../examples/connections/order-status.connection.json'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { MessageInspector } from './MessageInspector'

vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ can: () => true }) }))
vi.mock('@/api/queries', async (original) => ({ ...await original<typeof queries>(), useConnections: vi.fn(), useConnection: vi.fn() }))

describe('safety net of a message wait in Studio (docs/build/S129, CG-W03)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('turns on a slow check of a read-only operation and pins its connection', async () => {
    vi.mocked(queries.useConnections).mockReturnValue({ data: { items: [{
      key: 'order-status', name: 'Order tracking', kind: 'http', latestVersion: 1, binding: { configured: true, enabled: true }, operations: [],
    }] }, isPending: false } as never)
    vi.mocked(queries.useConnection).mockReturnValue({ data: { definitions: [{ version: 1, definition: orderStatus }] }, isSuccess: true } as never)
    const definition = normalizeDefinition(structuredClone(purchase))
    definition.nodes.push({ id: 'shipped', type: 'waitForMessage', label: 'Wait for shipping', config: {
      message: 'order-shipped', correlationField: '/steps/procure/procurementId', payload: [{ name: 'state', type: 'string', required: true }],
    } })
    const store = createStudioStore()
    store.getState().load({ workflowId: 'workflow', revision: 1, definition })
    const node = () => store.getState().definition.nodes.find((n) => n.id === 'shipped')!
    const view = () => <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>
      <MessageInspector node={node()} />
    </StudioProvider>
    const utils = render(view())
    const user = userEvent.setup()
    await user.click(screen.getByLabelText('Also check the other system now and then'))
    utils.rerender(view())
    await user.selectOptions(screen.getByLabelText(/^Connection/), 'order-status')
    utils.rerender(view())
    expect(store.getState().definition.dependencies.connections.map((c) => c.key)).toContain('order-status')
    const operations = Array.from((screen.getByLabelText(/^Read-only operation/) as HTMLSelectElement).options).map((o) => o.textContent)
    expect(operations).toEqual(['Choose an operation', 'Get order status'])
    await user.selectOptions(screen.getByLabelText(/^Read-only operation/), 'getStatus')
    utils.rerender(view())
    await user.selectOptions(screen.getByLabelText('State (required)'), '/state')
    utils.rerender(view())
    const fallback = node().config.fallbackCheck as Record<string, unknown>
    expect(fallback.operation).toBe('getStatus')
    expect(fallback.resultMapping).toEqual({ state: '/state' })
    expect(fallback.every).toBe('PT2H')
  })
})
