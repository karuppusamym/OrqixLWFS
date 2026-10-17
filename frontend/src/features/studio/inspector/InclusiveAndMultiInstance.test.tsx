import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { canConnect, setSplitMode } from '../model/ops'
import { normalizeDefinition, type Definition } from '../model/types'
import { ApprovalInspector } from './SimpleInspectors'
import { ParallelInspector } from './PatternInspectors'

function provider(store: ReturnType<typeof createStudioStore>, children: React.ReactNode) {
  return (
    <QueryClientProvider client={new QueryClient()}>
      <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>{children}</StudioProvider>
    </QueryClientProvider>
  )
}

/** manager -> split (two next paths) -> a / b -> join, built on the purchase template's nodes. */
function splitDefinition(): Definition {
  const def = normalizeDefinition(structuredClone(purchase))
  def.nodes.push(
    { id: 'split', type: 'parallelSplit', label: 'Together', config: { joinRef: 'join' } },
    { id: 'join', type: 'parallelJoin', label: 'Done', config: {} },
  )
  def.transitions.push(
    { id: 's1', from: 'split', to: 'procure', on: 'next' },
    { id: 's2', from: 'split', to: 'notify', on: 'next' },
  )
  return def
}

describe('inspector: several approvers', () => {
  it('sets, clamps and clears the number of approvers', async () => {
    const definition = normalizeDefinition(structuredClone(purchase))
    const store = createStudioStore()
    store.getState().load({ workflowId: 'workflow', revision: 1, definition })
    function Inspector() {
      const node = useStudio((s) => s.definition.nodes.find((n) => n.id === 'finance')!)
      return <ApprovalInspector node={node} />
    }
    render(provider(store, <Inspector />))
    const user = userEvent.setup()
    const field = screen.getByLabelText(/^Number of approvers/)
    const config = () => store.getState().definition.nodes.find((n) => n.id === 'finance')!.config
    await user.type(field, '3')
    await user.tab()
    expect(config().multiInstance).toEqual({ approvers: 3 })
    await user.clear(field)
    await user.type(field, '9')
    await user.tab()
    expect(config().multiInstance).toEqual({ approvers: 5 })
    await user.clear(field)
    await user.type(field, '1')
    await user.tab()
    expect(config().multiInstance).toBeUndefined()
  })
})

describe('inclusive split', () => {
  it('switching to inclusive makes the last path Otherwise and the others conditions, and back again', () => {
    const inclusive = setSplitMode(splitDefinition(), 'split', 'inclusive', '/input/amount')
    expect(inclusive.nodes.find((n) => n.id === 'split')!.config.mode).toBe('inclusive')
    const paths = inclusive.transitions.filter((t) => t.from === 'split')
    expect(paths.map((t) => t.on)).toEqual(['condition', 'default'])
    expect(paths[0]?.condition?.fieldRef).toBe('/input/amount')
    const back = setSplitMode(inclusive, 'split', 'all')
    expect(back.nodes.find((n) => n.id === 'split')!.config.mode).toBeUndefined()
    expect(back.transitions.filter((t) => t.from === 'split').map((t) => [t.on, t.condition])).toEqual([['next', undefined], ['next', undefined]])
  })

  it('only an inclusive split accepts condition and Otherwise paths', () => {
    const plain = splitDefinition()
    expect(canConnect(plain, { from: 'split', to: 'finance', on: 'condition' }).ok).toBe(false)
    const inclusive = setSplitMode(plain, 'split', 'inclusive', '/input/amount')
    expect(canConnect(inclusive, { from: 'split', to: 'finance', on: 'condition' }).ok).toBe(true)
    expect(canConnect(inclusive, { from: 'split', to: 'finance', on: 'default' }).ok).toBe(false) // already has Otherwise
    expect(canConnect(inclusive, { from: 'split', to: 'finance', on: 'next' }).ok).toBe(false)
  })

  it('the inspector switches the mode and explains the inclusive behavior', async () => {
    const store = createStudioStore()
    store.getState().load({ workflowId: 'workflow', revision: 1, definition: splitDefinition() })
    function Inspector() {
      const node = useStudio((s) => s.definition.nodes.find((n) => n.id === 'split')!)
      return <ParallelInspector node={node} />
    }
    render(provider(store, <Inspector />))
    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: 'Every path whose condition applies' }))
    expect(store.getState().definition.nodes.find((n) => n.id === 'split')!.config.mode).toBe('inclusive')
    expect(screen.getByText(/When none does, the Otherwise path runs/)).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Every path' }))
    expect(store.getState().definition.nodes.find((n) => n.id === 'split')!.config.mode).toBeUndefined()
  })
})
