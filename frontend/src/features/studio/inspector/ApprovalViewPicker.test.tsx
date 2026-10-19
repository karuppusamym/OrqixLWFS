import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import { createStudioStore } from '@/stores/studioStore'
import type { DraftSaver } from '@/stores/draftSaver'
import { normalizeDefinition } from '../model/types'
import { StudioProvider, useStudio } from '../StudioContext'
import { ApprovalViewPicker } from './ApprovalViewPicker'
import * as views from '@/api/views'
import type { ViewDefinition } from '@/features/views/model'

vi.mock('@/api/views', () => ({ listViews: vi.fn(), getPublishedView: vi.fn() }))

function setup(readOnly = false) {
  const definition = normalizeDefinition(purchase)
  const view: ViewDefinition = { schemaVersion: 1, kind: 'approval', sections: [{ id: 'review', label: 'Review context', columns: 2, fields: Object.entries(definition.inputSchema.properties).map(([key, property]) => ({ id: key, label: key, binding: `request.${key}`, required: true, type: property.enum ? 'choice' : property.type === 'number' ? 'number' : 'text', ...(property.enum ? { options: property.enum.map(value => ({ value, label: value })) } : {}) })) }], actions: [{ id: 'yes', type: 'approve', label: 'Authorize' }, { id: 'no', type: 'reject', label: 'Decline' }] }
  vi.mocked(views.listViews).mockResolvedValue({ items: [{ id: 'view-1', kind: 'approval', name: 'Shared review', publishedVersion: 2 }] } as never)
  vi.mocked(views.getPublishedView).mockImplementation(async (_ws, id, version) => ({ viewId: id, version, definition: view }) as never)
  const store = createStudioStore(); store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  function Picker() { const node = useStudio(state => state.definition.nodes.find(node => node.id === 'manager')!); return <ApprovalViewPicker node={node} /> }
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}><Picker /></StudioProvider></QueryClientProvider>)
  return store
}

beforeEach(() => vi.clearAllMocks())
describe('approval view selection', () => {
  it('loads only on demand, applies the selected version and removes it with undo available', async () => {
    const store = setup(), user = userEvent.setup()
    expect(views.listViews).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Choose approval view' }))
    await screen.findByRole('option', { name: 'Shared review (versions 1–2)' })
    await user.selectOptions(screen.getByLabelText('Published approval view'), 'view-1')
    await user.clear(screen.getByLabelText('Approval view version')); await user.type(screen.getByLabelText('Approval view version'), '2')
    await user.click(screen.getByRole('button', { name: 'Apply approval view' }))
    expect(await screen.findByText('Pinned approval view view-1, version 2')).toBeInTheDocument()
    expect(views.getPublishedView).toHaveBeenCalledWith('workspace', 'view-1', 2)
    await user.click(screen.getByRole('button', { name: 'Remove approval view' }))
    expect(store.getState().definition.nodes.find(node => node.id === 'manager')!.config.viewBinding).toBeUndefined()
    store.temporal.getState().undo()
    await waitFor(() => expect(store.getState().definition.nodes.find(node => node.id === 'manager')!.config.viewBinding).toBeDefined())
  })
  it('keeps the draft intact when the published version cannot be read', async () => {
    const store = setup(), user = userEvent.setup()
    vi.mocked(views.getPublishedView).mockRejectedValue(new Error('Version unavailable'))
    await user.click(screen.getByRole('button', { name: 'Choose approval view' }))
    await screen.findByRole('option', { name: 'Shared review (versions 1–2)' })
    await user.selectOptions(screen.getByLabelText('Published approval view'), 'view-1')
    await user.click(screen.getByRole('button', { name: 'Apply approval view' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Version unavailable')
    expect(store.getState().definition.nodes.find(node => node.id === 'manager')!.config.viewBinding).toBeUndefined()
  })
  it('offers no selection controls or library fetch for a read-only workflow', () => {
    setup(true)
    expect(screen.queryByRole('button', { name: 'Choose approval view' })).not.toBeInTheDocument()
    expect(views.listViews).not.toHaveBeenCalled()
  })
})
