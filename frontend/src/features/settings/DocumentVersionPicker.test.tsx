import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as endpoints from '@/api/endpoints'
import type { ConnectionDetail, ConnectionSummary, ConnectionTestResult } from '@/api/types'
import { TestConnection } from './ConnectionsPanel'
import { DocumentVersionPicker, parseDocumentVersions } from './DocumentVersionPicker'

vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ workspace: { id: 'workspace' } }) }))
vi.mock('@/api/endpoints', async (original) => ({ ...await original<typeof endpoints>(), testConnection: vi.fn() }))
const testConnection = vi.mocked(endpoints.testConnection)
const entries = [
  { versionId: '3.0', size: 1200, lastModifiedDateTime: '2026-10-10T10:00:00Z', newestListed: true },
  { versionId: '2.0', size: 1100, lastModifiedDateTime: '2026-10-09T10:00:00Z', newestListed: false },
]
const output = () => ({ driveId: 'drive', itemId: 'item', hasMore: false, versionCount: entries.length, versionsJson: JSON.stringify(entries) })
const success = (value = output()) => ({ outcome: 'succeeded', httpStatus: 200, durationMs: 1, output: value }) as unknown as ConnectionTestResult
const props = {
  workspaceId: 'workspace', connectionKey: 'documents', connectionVersion: 3, operation: 'history',
  driveId: 'drive', itemId: 'item', approvedDrives: ['drive'], enabled: true, selectedVersion: '', onSelect: vi.fn(),
}

describe('approved document historical version picker', () => {
  beforeEach(() => { vi.clearAllMocks(); testConnection.mockResolvedValue(success()) })

  it('loads through the versioned connection operation and requires an explicit historical choice', async () => {
    const user = userEvent.setup()
    render(<DocumentVersionPicker {...props} />)
    expect(testConnection).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Load document versions' }))
    const select = await screen.findByLabelText('Choose historical version')
    expect(testConnection).toHaveBeenCalledWith('workspace', 'documents', 3, 'history', { driveId: 'drive', itemId: 'item' })
    expect(select).toHaveValue('')
    expect(props.onSelect).not.toHaveBeenCalled()
    await user.selectOptions(select, '2.0')
    expect(props.onSelect).toHaveBeenCalledWith('2.0')
    expect(screen.getByText(/Microsoft may list the current version/)).toBeInTheDocument()
  })

  it('requires approved drive policy, safe IDs and an enabled binding before querying', () => {
    const view = render(<DocumentVersionPicker {...props} approvedDrives={[]} />)
    expect(screen.getByRole('button')).toBeDisabled()
    view.rerender(<DocumentVersionPicker {...props} itemId="../other" />)
    expect(screen.getByRole('button')).toBeDisabled()
    view.rerender(<DocumentVersionPicker {...props} enabled={false} />)
    expect(screen.getByRole('button')).toBeDisabled()
    expect(testConnection).not.toHaveBeenCalled()
  })

  it('shows failure without choosing a version or fetching current content', async () => {
    testConnection.mockResolvedValue({ outcome: 'failed', errorCode: 'DOCUMENT_UNAVAILABLE', durationMs: 1 } as ConnectionTestResult)
    render(<DocumentVersionPicker {...props} />)
    await userEvent.setup().click(screen.getByRole('button'))
    expect(await screen.findByRole('alert')).toHaveTextContent('DOCUMENT_UNAVAILABLE')
    expect(screen.queryByLabelText('Choose historical version')).not.toBeInTheDocument()
    expect(props.onSelect).not.toHaveBeenCalled()
    expect(testConnection).toHaveBeenCalledTimes(1)
  })

  it('ignores a late response after switching documents', async () => {
    let resolve!: (value: ConnectionTestResult) => void
    testConnection.mockReturnValue(new Promise((done) => { resolve = done }))
    const view = render(<DocumentVersionPicker key="item" {...props} />)
    await userEvent.setup().click(screen.getByRole('button'))
    view.rerender(<DocumentVersionPicker key="other" {...props} itemId="other" />)
    await act(async () => { resolve(success()) })
    expect(screen.queryByLabelText('Choose historical version')).not.toBeInTheDocument()
    expect(props.onSelect).not.toHaveBeenCalled()
    expect(screen.getByRole('button')).toBeEnabled()
  })

  it('rejects incomplete, mismatched, oversized, duplicate or malformed listings and drops arbitrary fields', () => {
    expect(() => parseDocumentVersions({ ...output(), hasMore: true }, 'drive', 'item')).toThrow()
    expect(() => parseDocumentVersions(output(), 'drive', 'other')).toThrow()
    expect(() => parseDocumentVersions({ ...output(), versionCount: 1 }, 'drive', 'item')).toThrow()
    expect(() => parseDocumentVersions({ ...output(), versionsJson: 'bad json' }, 'drive', 'item')).toThrow()
    expect(() => parseDocumentVersions({ ...output(), versionCount: 101, versionsJson: JSON.stringify(Array(101).fill(entries[0])) }, 'drive', 'item')).toThrow()
    expect(() => parseDocumentVersions({ ...output(), versionsJson: JSON.stringify([entries[0], entries[0]]) }, 'drive', 'item')).toThrow()
    expect(() => parseDocumentVersions({ ...output(), versionCount: 1, versionsJson: JSON.stringify([{ ...entries[0], versionId: '../unsafe' }]) }, 'drive', 'item')).toThrow()
    expect(parseDocumentVersions({ ...output(), versionCount: 1, versionsJson: JSON.stringify([{ ...entries[0], signedUrl: 'secret' }]) }, 'drive', 'item')[0]).not.toHaveProperty('signedUrl')
  })

  it('passes the selected historical version to extraction, clears it on document changes and never falls back', async () => {
    const schema = { required: ['driveId', 'itemId', 'versionId'], properties: { driveId: { enum: ['drive'] }, itemId: {}, versionId: {} } }
    const connection = {
      key: 'documents', name: 'Documents', kind: 'microsoftGraph', latestVersion: 3,
      binding: { configured: true, enabled: true },
      operations: [{ name: 'extractDocument', label: 'Extract historical document', testable: true }, { name: 'history', label: 'List versions', testable: true }],
    } as unknown as ConnectionSummary
    const detail = { definitions: [{ version: 3, definition: { operations: {
      extractDocument: { path: '/extract', requestSchema: schema }, history: { path: '/versions', requestSchema: { ...schema, required: ['driveId', 'itemId'], properties: { driveId: { enum: ['drive'] }, itemId: {} } } },
    } } }] } as unknown as ConnectionDetail
    const user = userEvent.setup()
    render(<TestConnection connection={connection} detail={detail} />)
    await user.type(screen.getByLabelText(/Drive ID/), 'drive')
    await user.type(screen.getByLabelText(/Document item ID/), 'item')
    expect(screen.getByRole('button', { name: 'Test connection' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Load document versions' }))
    await user.selectOptions(await screen.findByLabelText('Choose historical version'), '2.0')
    expect(screen.getByLabelText(/Document version ID/)).toHaveValue('2.0')
    testConnection.mockResolvedValue({ outcome: 'failed', errorCode: 'DOCUMENT_UNAVAILABLE', durationMs: 1 } as ConnectionTestResult)
    await user.click(screen.getByRole('button', { name: 'Test connection' }))
    expect(testConnection).toHaveBeenLastCalledWith('workspace', 'documents', 3, 'extractDocument', { driveId: 'drive', itemId: 'item', versionId: '2.0' })
    expect(await screen.findByText(/DOCUMENT_UNAVAILABLE after/)).toBeInTheDocument()
    expect(testConnection).toHaveBeenCalledTimes(2)
    await user.type(screen.getByLabelText(/Document item ID/), '2')
    expect(screen.getByLabelText(/Document version ID/)).toHaveValue('')
    expect(screen.queryByLabelText('Choose historical version')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Test connection' })).toBeDisabled()
  })
})
