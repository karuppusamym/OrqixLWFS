import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as queries from '@/api/queries'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import serviceNow from '../../../../../examples/connections/servicenow-table.connection.json'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { HttpInspector } from './HttpInspector'

vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ can: () => true }) }))
vi.mock('@/api/queries', async (original) => ({ ...await original<typeof queries>(), useConnections: vi.fn(), useConnection: vi.fn() }))

function LiveInspector({ nodeId }: { nodeId: string }) {
  const node = useStudio((s) => s.definition.nodes.find((n) => n.id === nodeId))
  return node ? <HttpInspector node={node} /> : null
}

/** The ServiceNow example with attachFile declared asynchronous: 202 + Location of the incident (CG-W02). */
function definitionWithAsync() {
  const copy = JSON.parse(JSON.stringify(serviceNow)) as typeof serviceNow & { operations: Record<string, Record<string, unknown>> }
  copy.operations.attachFile!.async = { acceptedStatuses: [202], statusFrom: { header: 'Location' }, statusOperation: 'getIncident', pollIntervalSeconds: 60 }
  return copy
}

function inspector(operation: string) {
  const definitionDoc = definitionWithAsync()
  vi.mocked(queries.useConnections).mockReturnValue({ data: { items: [{
    key: 'servicenow-table', name: 'ServiceNow incidents', kind: 'http', latestVersion: 1, binding: { configured: true, enabled: true, host: 'acme.service-now.com' },
    operations: Object.entries(definitionDoc.operations).map(([name, entry]) => ({ name, label: entry.label, effectClass: entry.effectClass, testable: entry.testable })),
  }] }, isPending: false } as never)
  vi.mocked(queries.useConnection).mockReturnValue({ data: { definitions: [{ version: 1, definition: definitionDoc }] }, isSuccess: true } as never)
  const definition = normalizeDefinition(purchase)
  definition.dependencies.connections = [{ key: 'servicenow-table', definitionVersion: 1 }]
  const node = definition.nodes.find((entry) => entry.type === 'http')!
  node.config = { connectionRef: 'servicenow-table', operation, inputMapping: {}, resultMapping: {}, timeoutSeconds: 20 }
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>
    <LiveInspector nodeId={node.id} />
  </StudioProvider>)
  return { store, nodeId: node.id }
}

describe('Call and wait for completion in the call inspector (CG-W02, S133)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('adds a check on the status operation after the call, with the status reference mapped in', async () => {
    const { store, nodeId } = inspector('attachFile')
    expect(screen.getByText('Finishes later')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByTestId('call-and-wait'))
    const def = store.getState().definition
    expect(def.nodes.find((n) => n.id === nodeId)!.config.resultMapping).toEqual({ sysId: '/@async/sysId' })
    const next = def.transitions.find((t) => t.from === nodeId && t.on === 'next')!.to
    const check = def.nodes.find((n) => n.id === next)!
    expect(check.config).toMatchObject({ operation: 'getIncident', inputMapping: { sysId: `/steps/${nodeId}/sysId` }, poll: { every: 'PT1M' } })
    expect(store.getState().selection).toEqual({ type: 'node', id: next })
  })

  it('offers nothing extra for operations that answer at once', () => {
    inspector('getIncident')
    expect(screen.queryByTestId('call-and-wait')).not.toBeInTheDocument()
  })
})
