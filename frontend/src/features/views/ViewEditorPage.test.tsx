import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getView, publishView, saveView } from '@/api/views'
import { setCurrentUser } from '@/mocks/handlers'
import { renderAppReady, signInAs, WS } from '@/test/utils'
import { blankView } from './model'

vi.mock('@/api/views', () => ({
  listViews: vi.fn(), getView: vi.fn(), createView: vi.fn(), copyView: vi.fn(), saveView: vi.fn(), publishView: vi.fn(), getPublishedView: vi.fn(),
}))

const viewId = '11111111-1111-4111-8111-111111111111'
const detail = () => ({ id: viewId, name: 'Expense request', description: 'Shared screen', draftRevision: 1, publishedVersion: null, definition: blankView(), versions: [] })
let currentDetail = detail()

describe('reusable view editor', () => {
  beforeEach(() => {
    signInAs('dana.designer')
    currentDetail = detail()
    vi.mocked(getView).mockReset().mockImplementation(async () => currentDetail)
    vi.mocked(saveView).mockReset().mockImplementation(async (_ws, _id, body) => {
      currentDetail = { ...currentDetail, ...body, draftRevision: 2 }
      return currentDetail
    })
    vi.mocked(publishView).mockReset().mockImplementation(async () => ({ ...detail(), draftRevision: 2, publishedVersion: 1, versions: [{ version: 1, publishedAt: '2026-10-17T12:00:00Z' }] }))
  })

  it('edits a reusable field, previews the binding, saves at the expected revision, and publishes', async () => {
    const user = userEvent.setup()
    const designer = await renderAppReady(`/w/${WS}/views/${viewId}`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Expense request' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Question label'), { target: { value: 'Cost center' } })
    fireEvent.change(screen.getByLabelText('Data binding'), { target: { value: 'request.costCenter' } })
    expect(within(screen.getByLabelText('View preview')).getByLabelText('Cost center')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(saveView).toHaveBeenCalledWith(WS, viewId, expect.objectContaining({ expectedRevision: 1, definition: expect.objectContaining({ sections: [expect.objectContaining({ fields: [expect.objectContaining({ binding: 'request.costCenter' })] })] }) })))
    designer.unmount()
    setCurrentUser('paul.publisher')
    await renderAppReady(`/w/${WS}/views/${viewId}`)
    await screen.findByRole('heading', { level: 1, name: 'Expense request' })
    await user.click(screen.getByRole('button', { name: 'Review and publish' }))
    const dialog = await screen.findByRole('dialog', { name: 'Review publication' })
    await user.click(within(dialog).getByRole('button', { name: 'Publish version 1' }))
    await waitFor(() => expect(publishView).toHaveBeenCalledWith(WS, viewId, 2))
  })

  it('keeps invalid bindings from being saved and restores prior editing with Undo', async () => {
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/views/${viewId}`)
    await screen.findByRole('heading', { level: 1, name: 'Expense request' })
    fireEvent.change(screen.getByLabelText('Data binding'), { target: { value: 'external.secret' } })
    expect(screen.getByText('Field field1 needs a binding matching this view kind.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(screen.getByLabelText('Data binding')).not.toHaveValue('external.secret')
  })

  it('authors case controls and the Save data action with case-scoped bindings', async () => {
    currentDetail.definition = blankView('case')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/views/${viewId}`)
    await screen.findByRole('heading', { level: 1, name: 'Expense request' })
    await user.click(screen.getByRole('button', { name: 'boolean' }))
    expect(screen.getAllByLabelText('Data binding')[1]).toHaveValue('case.field2')
    await user.click(screen.getByRole('button', { name: 'Add action' }))
    expect(screen.getByLabelText('Action type')).toHaveValue('saveData')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(saveView).toHaveBeenCalled())
    expect(vi.mocked(saveView).mock.calls[0]![2].definition).toMatchObject({ kind: 'case', actions: [{ type: 'saveData' }] })
  })
})
