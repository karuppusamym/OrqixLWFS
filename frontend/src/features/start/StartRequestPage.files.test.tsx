import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import * as attachments from '@/api/attachments'
import { keys } from '@/api/queries'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

vi.mock('@/api/attachments', async (original) => ({ ...await original<typeof attachments>(), uploadFormFile: vi.fn(), discardFormFile: vi.fn().mockResolvedValue(undefined) }))

describe('request form attachment upload coordination', () => {
  it('resets a pending file field for a changed release and discards the previous late result', async () => {
    signInAs('rhea.requester')
    const workflow = db.workflows.get(PURCHASE_WORKFLOW_ID)!
    const release = workflow.releases.find((item) => item.id === workflow.activeReleaseId)!
    release.definition.inputSchema = { type: 'object', additionalProperties: false, properties: {
      quote: { type: 'string', title: 'Supplier quote', format: 'attachment', acceptTypes: ['txt'], maxFileBytes: 1024 },
    } }
    release.definition.inputUi = { order: ['quote'], widgets: { quote: 'file' } }
    let resolve!: (value: Awaited<ReturnType<typeof attachments.uploadFormFile>>) => void
    vi.mocked(attachments.uploadFormFile).mockReturnValue(new Promise((done) => { resolve = done }))
    const user = userEvent.setup()
    const { client } = renderApp(`/w/${WS}/start/${PURCHASE_WORKFLOW_ID}`)
    await user.upload(await screen.findByLabelText(/Supplier quote/), new File(['hello'], 'quote.txt', { type: 'text/plain' }))
    expect(screen.getByRole('button', { name: 'Review request' })).toBeDisabled()
    const current = client.getQueryData<Record<string, unknown>>(keys.startForm(WS, PURCHASE_WORKFLOW_ID))!
    await act(async () => { client.setQueryData(keys.startForm(WS, PURCHASE_WORKFLOW_ID), { ...current, releaseNumber: Number(current.releaseNumber) + 1 }) })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Review request' })).toBeEnabled())
    expect(screen.getByRole('button', { name: 'Choose a file' })).toBeEnabled()
    const id = '12345678-1234-1234-1234-123456789abc'
    await act(async () => { resolve({ id, name: 'quote.txt', contentType: 'text/plain', sizeBytes: 5, sha256: 'a'.repeat(64), scanStatus: 'clean', expiresAt: '2027-01-01T00:00:00Z' }) })
    expect(attachments.discardFormFile).toHaveBeenCalledWith(WS, id)
    expect(screen.queryByTestId('file-chosen-quote')).not.toBeInTheDocument()
  })

  it('blocks review of an otherwise valid optional-file form until the upload settles', async () => {
    signInAs('rhea.requester')
    const workflow = db.workflows.get(PURCHASE_WORKFLOW_ID)!
    const release = workflow.releases.find((item) => item.id === workflow.activeReleaseId)!
    release.definition.inputSchema = { type: 'object', additionalProperties: false, properties: {
      quote: { type: 'string', title: 'Supplier quote', format: 'attachment', acceptTypes: ['txt'], maxFileBytes: 1024 },
    } }
    release.definition.inputUi = { order: ['quote'], widgets: { quote: 'file' } }
    let resolve!: (value: Awaited<ReturnType<typeof attachments.uploadFormFile>>) => void
    vi.mocked(attachments.uploadFormFile).mockReturnValue(new Promise((done) => { resolve = done }))
    const user = userEvent.setup()
    renderApp(`/w/${WS}/start/${PURCHASE_WORKFLOW_ID}`)
    await user.upload(await screen.findByLabelText(/Supplier quote/), new File(['hello'], 'quote.txt', { type: 'text/plain' }))
    expect(screen.getByRole('button', { name: 'Review request' })).toBeDisabled()
    expect(screen.getByText(/Wait for your files to finish uploading/)).toBeInTheDocument()
    await act(async () => { resolve({ id: '12345678-1234-1234-1234-123456789abc', name: 'quote.txt', contentType: 'text/plain', sizeBytes: 5, sha256: 'a'.repeat(64), scanStatus: 'clean', expiresAt: '2027-01-01T00:00:00Z' }) })
    expect(screen.getByRole('button', { name: 'Review request' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Review request' }))
    expect(await screen.findByRole('heading', { name: 'Check your request' })).toBeInTheDocument()
  })
})
