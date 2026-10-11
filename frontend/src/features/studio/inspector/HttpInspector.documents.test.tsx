import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as endpoints from '@/api/endpoints'
import * as queries from '@/api/queries'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import connectionExample from '../../../../../examples/connections/microsoft-documents-v3.json'
import documentExample from '../../../../../examples/document-review.json'
import { StudioProvider } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { HttpInspector } from './HttpInspector'

vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ can: () => true }) }))
vi.mock('@/api/queries', async (original) => ({ ...await original<typeof queries>(), useConnections: vi.fn(), useConnection: vi.fn() }))
vi.mock('@/api/endpoints', async (original) => ({ ...await original<typeof endpoints>(), testConnection: vi.fn() }))

function inspector(operation: 'readText' | 'extractSnapshot', pinnedTestable: boolean, latestTestable: boolean, missingPin = false) {
  const pinned = structuredClone(connectionExample)
  pinned.operations.listVersions.testable = pinnedTestable
  pinned.operations.pinLatest.testable = pinnedTestable
  const latest = structuredClone(pinned)
  latest.version = 4
  latest.operations.listVersions.testable = latestTestable
  latest.operations.pinLatest.testable = latestTestable
  vi.mocked(queries.useConnections).mockReturnValue({ data: { items: [{
    key: 'microsoft-documents', kind: 'microsoftGraph', latestVersion: 4, binding: { configured: true, enabled: true },
    operations: Object.entries(latest.operations).map(([name, entry]) => ({ name, testable: entry.testable })),
  }] }, isPending: false } as never)
  vi.mocked(queries.useConnection).mockReturnValue({ data: { definitions: [
    ...(missingPin ? [] : [{ version: 3, definition: pinned }]), { version: 4, definition: latest },
  ] }, isSuccess: true } as never)
  const definition = normalizeDefinition(documentExample)
  definition.dependencies.connections[0]!.definitionVersion = 3
  const node = definition.nodes.find((entry) => entry.type === 'http')!
  node.config.operation = operation
  node.config.inputMapping = {}
  node.config.resultMapping = {}
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>
    <HttpInspector node={node} />
  </StudioProvider>)
}

describe('document authoring through the pinned connection inspector', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it.each(['readText', 'extractSnapshot'] as const)('keeps pinned %s admin reads available when latest disables them', async (operation) => {
    const user = userEvent.setup()
    inspector(operation, true, false)
    await user.click(screen.getByRole('button', { name: 'Configure document reference' }))
    await user.selectOptions(screen.getByLabelText('Approved document drive'), 'drive')
    await user.type(screen.getByLabelText('Known document item ID'), 'item')
    const expectedOperation = operation === 'readText' ? 'listVersions' : 'pinLatest'
    const button = operation === 'readText' ? 'Load document versions' : 'Capture current document as snapshot'
    vi.mocked(endpoints.testConnection).mockResolvedValue({ outcome: 'failed', errorCode: 'DOCUMENT_UNAVAILABLE', durationMs: 1 } as never)
    await user.click(screen.getByRole('button', { name: button }))
    expect(await screen.findByRole('alert')).toHaveTextContent('DOCUMENT_UNAVAILABLE')
    expect(endpoints.testConnection).toHaveBeenCalledWith('workspace', 'microsoft-documents', 3, expectedOperation, { driveId: 'drive', itemId: 'item' })
  })

  it.each(['readText', 'extractSnapshot'] as const)('does not expose %s admin reads newly enabled only in latest', async (operation) => {
    inspector(operation, false, true)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Configure document reference' }))
    expect(screen.queryByRole('button', { name: 'Load document versions' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Capture current document as snapshot' })).not.toBeInTheDocument()
    expect(endpoints.testConnection).not.toHaveBeenCalled()
  })

  it('refuses an unavailable pin instead of using the latest definition for authoring', () => {
    inspector('readText', true, true, true)
    expect(screen.getByText(/Connection version 3 is unavailable/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Configure document reference' })).not.toBeInTheDocument()
    expect(endpoints.testConnection).not.toHaveBeenCalled()
  })
})
