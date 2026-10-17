import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as endpoints from '@/api/endpoints'
import type { ConnectionTestResult } from '@/api/types'
import { DocumentFolderPicker, parseDocumentFiles } from './DocumentFolderPicker'

vi.mock('@/api/endpoints', async (original) => ({ ...await original<typeof endpoints>(), testConnection: vi.fn() }))
const files = [{ itemId: 'file', name: 'Policy.docx', size: 123, contentType: 'application/docx' }]
const output = { driveId: 'drive', folderId: 'folder', filesJson: JSON.stringify(files), fileCount: 1, hasMore: false }
const props = { workspaceId: 'ws', connectionKey: 'documents', connectionVersion: 2, operation: 'browse', driveId: 'drive', approvedDrives: ['drive'], approvedFolders: ['folder'], enabled: true, onSelect: vi.fn() }
describe('approved folder browsing', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(endpoints.testConnection).mockResolvedValue({ outcome: 'succeeded', output } as unknown as ConnectionTestResult) })
  it('uses the exact approved grant and requires explicit file selection', async () => {
    const user = userEvent.setup(); render(<DocumentFolderPicker {...props} />)
    expect(screen.getByRole('button', { name: 'Browse approved folder' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Approved folder'), 'folder')
    await user.click(screen.getByRole('button', { name: 'Browse approved folder' }))
    expect(endpoints.testConnection).toHaveBeenCalledWith('ws', 'documents', 2, 'browse', { driveId: 'drive', folderId: 'folder' })
    await screen.findByLabelText('Choose document'); expect(props.onSelect).not.toHaveBeenCalled()
    await user.selectOptions(screen.getByLabelText('Choose document'), 'file'); expect(props.onSelect).toHaveBeenCalledWith('file')
  })
  it('never calls a browser for an unapproved drive', async () => {
    const user = userEvent.setup(); render(<DocumentFolderPicker {...props} driveId="private" />)
    await user.selectOptions(screen.getByLabelText('Approved folder'), 'folder')
    expect(screen.getByRole('button', { name: 'Browse approved folder' })).toBeDisabled(); expect(endpoints.testConnection).not.toHaveBeenCalled()
  })
  it('rejects incomplete listings and unexpected identities', () => {
    expect(() => parseDocumentFiles({ ...output, hasMore: true }, 'drive', 'folder')).toThrow()
    expect(() => parseDocumentFiles(output, 'drive', 'private')).toThrow()
    expect(() => parseDocumentFiles({ ...output, filesJson: JSON.stringify([{ ...files[0], itemId: '../secret' }]) }, 'drive', 'folder')).toThrow()
    expect(parseDocumentFiles(output, 'drive', 'folder')).toEqual(files)
  })
})
