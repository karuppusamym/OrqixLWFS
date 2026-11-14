import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as queries from '@/api/queries'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import serviceNow from '../../../../../examples/connections/servicenow-table.connection.json'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { operationInputs, resultCandidates, resultPointerType, type V1Operation } from '../connectionSchema'
import { normalizeDefinition } from '../model/types'
import { HttpInspector } from './HttpInspector'

vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ can: () => true }) }))
vi.mock('@/api/queries', async (original) => ({ ...await original<typeof queries>(), useConnections: vi.fn(), useConnection: vi.fn() }))

/** Re-renders with the node from the store, as the Studio's inspector does. */
function LiveInspector({ nodeId }: { nodeId: string }) {
  const node = useStudio((s) => s.definition.nodes.find((n) => n.id === nodeId))
  return node ? <HttpInspector node={node} /> : null
}

const operations = serviceNow.operations as unknown as Record<string, V1Operation>

function inspector(operation: string) {
  vi.mocked(queries.useConnections).mockReturnValue({ data: { items: [{
    key: 'servicenow-table', name: 'ServiceNow incidents', kind: 'http', latestVersion: 1, binding: { configured: true, enabled: true, host: 'acme.service-now.com' },
    operations: Object.entries(serviceNow.operations).map(([name, entry]) => ({ name, label: entry.label, effectClass: entry.effectClass, testable: entry.testable })),
  }] }, isPending: false } as never)
  vi.mocked(queries.useConnection).mockReturnValue({ data: { definitions: [{ version: 1, definition: serviceNow }] }, isSuccess: true } as never)
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

describe('connection definition v1 in the call inspector (S130)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('offers a path parameter as a required input in the address', async () => {
    const { store, nodeId } = inspector('getIncident')
    const inputs = screen.getByRole('group', { name: 'What to send' })
    expect(within(inputs).getByText(/Sys id/i)).toBeInTheDocument()
    expect(within(inputs).getByText(/in the address/)).toBeInTheDocument()
    expect(within(inputs).getByText('required')).toBeInTheDocument()
    await userEvent.setup().selectOptions(within(inputs).getByRole('combobox'), '/input/title')
    expect(store.getState().definition.nodes.find((n) => n.id === nodeId)!.config.inputMapping).toEqual({ sysId: '/input/title' })
  })

  it('keeps nested values, a list of rows and a response header with their pointers', async () => {
    const user = userEvent.setup()
    const { store, nodeId } = inspector('listOpenIncidents')
    const results = screen.getByRole('group', { name: 'Result fields to keep' })
    await user.click(within(results).getByLabelText('Keep “result (list of rows)”'))
    await user.click(within(results).getByLabelText('Keep “result › first item › number”'))
    await user.click(within(results).getByLabelText('Keep “Header X-Total-Count”'))
    expect(within(results).getByText('list of rows')).toBeInTheDocument()
    expect(store.getState().definition.nodes.find((n) => n.id === nodeId)!.config.resultMapping).toEqual({
      result: '/result/[*]', number: '/result/0/number', xTotalCount: '/@headers/X-Total-Count',
    })
  })

  it('hides the reconcile key and explains the lookup', () => {
    inspector('createIncident')
    expect(screen.getByTestId('reconcile-note')).toHaveTextContent('Correlation id')
    expect(screen.getByTestId('reconcile-note')).toHaveTextContent('Find incident by correlation')
    expect(screen.queryByLabelText(/^Correlation id/)).not.toBeInTheDocument()
    expect(screen.getByTestId('effect-class')).toHaveTextContent('looks the record up before sending again')
  })

  it('lists results, inputs and types for v1 and flat v0 answers', () => {
    const list = resultCandidates(operations.listOpenIncidents)
    expect(list.map((c) => c.pointer)).toEqual(expect.arrayContaining(['/result/[*]', '/result/[*]/number', '/result/0/number', '/@headers/X-Total-Count']))
    expect(resultPointerType(operations.listOpenIncidents, '/result/[*]')).toBe('array')
    expect(resultPointerType(operations.listOpenIncidents, '/@headers/X-Total-Count')).toBe('integer')
    expect(resultPointerType(operations.getIncident, '/result/number')).toBe('string')
    expect(operationInputs(operations.createIncident).map((i) => i.name)).not.toContain('correlationId')
    expect(operationInputs(operations.attachFile).find((i) => i.name === 'document')?.schema.format).toBe('attachment')
    const flat = resultCandidates({ responseSchema: { type: 'object', properties: { id: { type: 'string' }, status: { type: 'string' } } } })
    expect(flat.map((c) => [c.pointer, c.name])).toEqual([['/id', 'id'], ['/status', 'status']])
  })
})
