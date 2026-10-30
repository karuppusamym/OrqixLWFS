import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { renameNodeId, removeNode } from '../model/ops'
import { StagesInspector } from './StagesInspector'

function renderStages(readOnly = false) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition: normalizeDefinition(purchase) })
  render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}><StagesInspector /></StudioProvider>)
  return store
}

describe('progress stage authoring', () => {
  it('edits names, SLA and exclusive memberships, reorders and removes stages without changing process routes', async () => {
    const store = renderStages()
    const originalRoutes = structuredClone(store.getState().definition.transitions)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Add a progress stage' }))
    const first = screen.getByRole('group', { name: 'Stage 1: Stage 1' })
    await user.clear(within(first).getByLabelText(/^Stage name/))
    await user.type(within(first).getByLabelText(/^Stage name/), 'Review')
    await user.tab()
    await user.type(within(first).getByLabelText(/^Stage time limit/), 'PT8H')
    await user.tab()
    const manager = store.getState().definition.nodes.find((node) => node.id === 'manager')!
    await user.click(within(first).getByRole('checkbox', { name: manager.label }))
    await user.click(screen.getByRole('button', { name: 'Add a progress stage' }))
    const second = screen.getByRole('group', { name: 'Stage 2: Stage 2' })
    expect(within(second).getByRole('checkbox', { name: `${manager.label} (in Review)` })).toBeDisabled()
    expect(store.getState().definition.stages?.[0]).toEqual({ id: 'stage1', label: 'Review', nodes: ['manager'], sla: 'PT8H' })
    await user.click(screen.getByRole('button', { name: 'Move stage Stage 2 up' }))
    expect(store.getState().definition.stages?.[0]?.id).toBe('stage2')
    await user.click(screen.getByRole('button', { name: 'Remove stage Stage 2' }))
    expect(store.getState().definition.stages).toHaveLength(1)
    expect(store.getState().definition.transitions).toEqual(originalRoutes)
  })

  it('shows invalid time limits and preserves stage references when a step is renamed or deleted', async () => {
    const store = renderStages()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Add a progress stage' }))
    await user.type(screen.getByLabelText(/^Stage time limit/), 'P100D')
    await user.tab()
    expect(screen.getByText('Use a duration from PT1M to P90D.')).toBeInTheDocument()
    const definition = structuredClone(store.getState().definition)
    definition.stages![0]!.nodes = ['manager']
    const renamed = renameNodeId(definition, 'manager', 'reviewManager')
    expect(renamed.ok).toBe(true)
    if (renamed.ok) {
      expect(renamed.def.stages?.[0]?.nodes).toEqual(['reviewManager'])
      expect(removeNode(renamed.def, 'reviewManager').stages?.[0]?.nodes).toEqual([])
    }
  })

  it('disables authoring for read-only workflows', () => {
    renderStages(true)
    expect(screen.getByRole('button', { name: 'Add a progress stage' })).toBeDisabled()
  })
})
