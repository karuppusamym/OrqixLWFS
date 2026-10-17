import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as attachments from '@/api/attachments'
import { buildFields } from '@/lib/form/schema'
import { FileField } from './FileField'

vi.mock('@/app/workspace', () => ({ useWsId: () => 'workspace' }))
vi.mock('@/api/attachments', async (original) => ({ ...await original<typeof attachments>(), uploadFormFile: vi.fn(), discardFormFile: vi.fn().mockResolvedValue(undefined) }))
const field = buildFields({ type: 'object', additionalProperties: false, required: ['quote'], properties: {
  quote: { type: 'string', title: 'Supplier quote', format: 'attachment', acceptTypes: ['txt'], maxFileBytes: 1024 },
} }, {})[0]!
const staged = { id: '12345678-1234-1234-1234-123456789abc', name: 'quote.txt', sizeBytes: 5, contentType: 'text/plain', sha256: 'a'.repeat(64), scanStatus: 'notScanned', expiresAt: '2027-01-01T00:00:00Z' } as const
const props = { field, value: '', onChange: vi.fn(), error: undefined, id: 'quote', disabled: false, onBusyChange: vi.fn() }
const file = () => new File(['hello'], 'quote.txt', { type: 'text/plain' })

describe('request file field lifecycle', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(attachments.uploadFormFile).mockResolvedValue(staged) })

  it('announces upload activity and explicitly shows when scanning is not configured', async () => {
    const view = render(<FileField {...props} />)
    await userEvent.setup().upload(screen.getByLabelText(/Supplier quote/), file())
    expect(props.onBusyChange.mock.calls).toEqual([[true], [false]])
    expect(props.onChange).toHaveBeenCalledWith(staged.id)
    // Parent-controlled value normally updates with onChange.
    view.rerender(<FileField {...props} value={staged.id} />)
    expect(screen.getByText('File uploaded. Virus scanning is not configured.')).toBeInTheDocument()
  })

  it('rejects local file limits before sending bytes', async () => {
    render(<FileField {...props} />)
    await userEvent.setup().upload(screen.getByLabelText(/Supplier quote/), new File(['x'.repeat(1025)], 'quote.txt', { type: 'text/plain' }))
    expect(attachments.uploadFormFile).not.toHaveBeenCalled()
    expect(props.onBusyChange).not.toHaveBeenCalled()
    expect(screen.getAllByText(/too large|at most|Largest file|larger/i).length).toBeGreaterThan(0)
  })

  it('blocks scan errors before assigning the request value and discards staged bytes', async () => {
    vi.mocked(attachments.uploadFormFile).mockResolvedValue({ ...staged, scanStatus: 'error' })
    render(<FileField {...props} />)
    await userEvent.setup().upload(screen.getByLabelText(/Supplier quote/), file())
    expect(screen.getByText(/blocked by the virus scan/)).toBeInTheDocument()
    expect(props.onChange).not.toHaveBeenCalled()
    expect(attachments.discardFormFile).toHaveBeenCalledWith('workspace', staged.id)
  })

  it('ignores a late result after unmount and discards its unattached upload', async () => {
    let resolve!: (value: typeof staged) => void
    vi.mocked(attachments.uploadFormFile).mockReturnValue(new Promise((done) => { resolve = done }))
    const view = render(<FileField {...props} />)
    await userEvent.setup().upload(screen.getByLabelText(/Supplier quote/), file())
    expect(props.onBusyChange).toHaveBeenCalledWith(true)
    view.unmount()
    expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
    await act(async () => { resolve(staged) })
    expect(props.onChange).not.toHaveBeenCalled()
    expect(attachments.discardFormFile).toHaveBeenCalledWith('workspace', staged.id)
  })
})
