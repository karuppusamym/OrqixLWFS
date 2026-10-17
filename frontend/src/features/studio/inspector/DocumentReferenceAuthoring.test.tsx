import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as endpoints from '@/api/endpoints'
import type { ConnectionTestResult } from '@/api/types'
import documentExample from '../../../../../examples/document-review.json'
import { normalizeDefinition } from '../model/types'
import { applyDocumentReference } from '../model/documentReference'
import { DocumentReferenceAuthoring } from './DocumentReferenceAuthoring'

vi.mock('@/api/endpoints', async (original) => ({ ...await original<typeof endpoints>(), testConnection: vi.fn() }))
const testConnection = vi.mocked(endpoints.testConnection)
const snapshotId = '12345678-1234-1234-1234-123456789abc'
const props = {
  workspaceId: 'workspace', connectionKey: 'documents', connectionVersion: 3, path: '/extract',
  operations: {
    history: { path: '/versions', requestSchema: { properties: { driveId: { enum: ['drive'] } } } },
    capture: { path: '/pinLatest', requestSchema: { properties: { driveId: { enum: ['drive'] } } } },
  },
  testableOperations: ['history', 'capture'], approvedDrives: ['drive'], canManage: true,
  enabled: true, readOnly: false, onApply: vi.fn((): string | undefined => undefined),
}
const captured = (overrides = {}) => ({ outcome: 'succeeded', durationMs: 1, output: {
  driveId: 'drive', itemId: 'item', snapshotId, pinMode: 'snapshot', contentSha256: 'a'.repeat(64),
  eTag: 'etag', snapshotExpiresAt: '2027-01-01T00:00:00Z', ...overrides,
} }) as unknown as ConnectionTestResult

async function openKnownDocument(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Configure document reference' }))
  await user.selectOptions(screen.getByLabelText('Approved document drive'), 'drive')
  await user.type(screen.getByLabelText('Known document item ID'), 'item')
}

describe('Studio document reference authoring', () => {
  beforeEach(() => { vi.clearAllMocks(); testConnection.mockResolvedValue(captured()) })

  it('applies exact historical request choices and mappings without connector permission or network calls', async () => {
    let definition = normalizeDefinition(documentExample)
    const nodeId = definition.nodes.find((node) => node.type === 'http')!.id
    definition.nodes.find((node) => node.id === nodeId)!.config.inputMapping = {}
    const user = userEvent.setup()
    render(<DocumentReferenceAuthoring {...props} canManage={false} onApply={(reference) => {
      const result = applyDocumentReference(definition, nodeId, reference, ['versionId', 'name', 'contentSha256'])
      if (!result.ok) return result.reason
      definition = result.definition
    }} />)
    await openKnownDocument(user)
    expect(screen.queryByRole('button', { name: 'Load document versions' })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Exact historical version ID'), '1.0')
    await user.click(screen.getByRole('button', { name: 'Apply approved reference to request form' }))
    expect(definition.inputSchema.properties.documentVersionId?.enum).toEqual(['1.0'])
    expect(definition.nodes.find((node) => node.id === nodeId)!.config.inputMapping).toEqual({ driveId: '/input/documentDriveId', itemId: '/input/documentItemId', versionId: '/input/documentVersionId' })
    expect(testConnection).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Known document item ID')).not.toBeInTheDocument()
  })

  it('loads bounded historical choices explicitly without applying a default and clears a pin on document changes', async () => {
    testConnection.mockResolvedValue({ outcome: 'succeeded', durationMs: 1, output: {
      driveId: 'drive', itemId: 'item', versionCount: 1, hasMore: false,
      versionsJson: JSON.stringify([{ versionId: '1.0', size: 12, lastModifiedDateTime: '', newestListed: false }]),
    } } as unknown as ConnectionTestResult)
    const user = userEvent.setup()
    render(<DocumentReferenceAuthoring {...props} />)
    await openKnownDocument(user)
    await user.click(screen.getByRole('button', { name: 'Load document versions' }))
    const choose = await screen.findByLabelText('Choose historical version')
    expect(choose).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Apply approved reference to request form' })).toBeDisabled()
    await user.selectOptions(choose, '1.0')
    await user.type(screen.getByLabelText('Known document item ID'), '2')
    expect(screen.getByLabelText('Exact historical version ID')).toHaveValue('')
    expect(screen.queryByLabelText('Choose historical version')).not.toBeInTheDocument()
  })

  it('captures only on explicit administrator action and displays immutable hash and retention provenance', async () => {
    const user = userEvent.setup()
    render(<DocumentReferenceAuthoring {...props} path="/extractSnapshot" />)
    await openKnownDocument(user)
    expect(testConnection).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Capture current document as snapshot' }))
    expect(await screen.findByText('a'.repeat(64))).toBeInTheDocument()
    expect(screen.getByText('2027-01-01T00:00:00Z')).toBeInTheDocument()
    expect(testConnection).toHaveBeenCalledWith('workspace', 'documents', 3, 'capture', { driveId: 'drive', itemId: 'item' })
    await user.click(screen.getByRole('button', { name: 'Apply approved reference to request form' }))
    expect(props.onApply).toHaveBeenCalledWith({ driveId: 'drive', itemId: 'item', snapshotId })
    expect(testConnection).toHaveBeenCalledTimes(1)
  })

  it('rejects mismatched capture provenance and never silently recaptures', async () => {
    testConnection.mockResolvedValue(captured({ itemId: 'other', signedUrl: 'must-not-leak' }))
    const user = userEvent.setup()
    render(<DocumentReferenceAuthoring {...props} path="/extractSnapshot" />)
    await openKnownDocument(user)
    await user.click(screen.getByRole('button', { name: 'Capture current document as snapshot' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('incomplete or mismatched')
    expect(screen.getByLabelText('Retained snapshot ID')).toHaveValue('')
    expect(screen.queryByText(/must-not-leak/)).not.toBeInTheDocument()
    expect(props.onApply).not.toHaveBeenCalled()
    expect(testConnection).toHaveBeenCalledTimes(1)
  })

  it('cancels draft selection and ignores pending capture completion when reopened', async () => {
    let resolve!: (value: ConnectionTestResult) => void
    testConnection.mockReturnValue(new Promise((done) => { resolve = done }))
    const user = userEvent.setup()
    render(<DocumentReferenceAuthoring {...props} path="/extractSnapshot" />)
    await openKnownDocument(user)
    await user.click(screen.getByRole('button', { name: 'Capture current document as snapshot' }))
    await user.click(screen.getByRole('button', { name: 'Cancel document reference' }))
    await user.click(screen.getByRole('button', { name: 'Configure document reference' }))
    await act(async () => { resolve(captured()) })
    expect(screen.getByLabelText('Known document item ID')).toHaveValue('')
    expect(screen.getByLabelText('Retained snapshot ID')).toHaveValue('')
    expect(screen.queryByText('a'.repeat(64))).not.toBeInTheDocument()
    expect(props.onApply).not.toHaveBeenCalled()
  })

  it('preserves the editor after incompatible mapping failure and disables changes for read-only authors', async () => {
    const user = userEvent.setup()
    const view = render(<DocumentReferenceAuthoring {...props} onApply={() => 'Clear the existing mapping first.'} />)
    await openKnownDocument(user)
    await user.type(screen.getByLabelText('Exact historical version ID'), '1.0')
    await user.click(screen.getByRole('button', { name: 'Apply approved reference to request form' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Clear the existing mapping first.')
    expect(screen.getByLabelText('Exact historical version ID')).toHaveValue('1.0')
    view.rerender(<DocumentReferenceAuthoring {...props} readOnly />)
    expect(screen.getByRole('button', { name: 'Apply approved reference to request form' })).toBeDisabled()
  })
})
