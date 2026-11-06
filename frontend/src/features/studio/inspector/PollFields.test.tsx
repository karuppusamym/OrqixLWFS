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
import { HttpInspector } from './HttpInspector'

vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ can: () => true }) }))
vi.mock('@/api/queries', async (original) => ({ ...await original<typeof queries>(), useConnections: vi.fn(), useConnection: vi.fn() }))

function inspector(operation: 'getStatus' | 'markShipped') {
  vi.mocked(queries.useConnections).mockReturnValue({ data: { items: [{
    key: 'order-status', name: 'Order tracking', kind: 'http', latestVersion: 1, binding: { configured: true, enabled: true, host: 'tracking.example' },
    operations: [],
  }] }, isPending: false } as never)
  vi.mocked(queries.useConnection).mockReturnValue({ data: { definitions: [{ version: 1, definition: orderStatus }] }, isSuccess: true } as never)
  const definition = normalizeDefinition(structuredClone(purchase))
  definition.dependencies.connections.push({ key: 'order-status', definitionVersion: 1 })
  definition.nodes.push({ id: 'track', type: 'http', label: 'Track the order', config: {
    connectionRef: 'order-status', operation, inputMapping: { orderId: '/steps/procure/procurementId' }, resultMapping: { state: '/state' }, timeoutSeconds: 10,
  } })
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  const view = () => store.getState().definition.nodes.find((n) => n.id === 'track')!
  const utils = render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>
    <HttpInspector node={view()} />
  </StudioProvider>)
  const rerender = () => utils.rerender(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>
    <HttpInspector node={view()} />
  </StudioProvider>)
  return { store, view, rerender }
}

describe('Keep checking until... in the Call a system inspector (docs/build/S129)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('turns on checking for a read-only operation and shows the computed summary', async () => {
    const user = userEvent.setup()
    const { view, rerender } = inspector('getStatus')
    await user.click(screen.getByLabelText('Keep checking until the work is done'))
    rerender()
    const poll = view().config.poll as Record<string, unknown>
    expect(poll.every).toBe('PT15M')
    expect(poll.until).toEqual({ fieldRef: '/steps/track/state', operator: 'eq', value: 'pending' })
    expect(screen.getByTestId('poll-summary')).toHaveTextContent('Checks up to 97 times over 1 day; every 15 minutes. With 500 requests waiting this is about 2000 calls an hour to Order tracking.')
    expect(screen.getByTestId('poll-routes')).toHaveTextContent('a path for when the checks give up')
    await user.selectOptions(screen.getByLabelText('Give up after'), 'P2D')
    rerender()
    expect((view().config.poll as Record<string, unknown>).giveUpAfter).toBe('P2D')
    expect(screen.getByTestId('poll-summary')).toHaveTextContent('Checks up to 193 times over 2 days')
  })

  it('offers no checking for an operation that changes something', () => {
    inspector('markShipped')
    expect(screen.getByLabelText('Keep checking until the work is done')).toBeDisabled()
    expect(screen.getByTestId('poll-needs-readonly')).toBeInTheDocument()
  })
})
