import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { testQueryClient } from '@/test/utils'
import { DecisionInspector } from './DecisionInspector'

describe('conjunctive decision authoring', () => {
  it('offers bounded All conditions and a sample for every input', async () => {
    const store = createStudioStore()
    store.getState().load({ workflowId: 'workflow', revision: 1, definition: normalizeDefinition(purchase) })
    function Inspector() {
      const node = useStudio(s => s.definition.nodes.find(n => n.type === 'decision')!)
      return <DecisionInspector node={node} />
    }
    render(<QueryClientProvider client={testQueryClient()}><StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}><Inspector /></StudioProvider></QueryClientProvider>)
    const user = userEvent.setup()
    expect(screen.getByRole('option', { name: 'Any comparison (OR)' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Condition group'), 'all')
    const fields = screen.getAllByLabelText('Value to look at')
    await user.selectOptions(fields[1]!, '/input/costCenter')
    expect(store.getState().definition.decisions[0]!.rows[0]!.when).toMatchObject({ all: [{ fieldRef: '/input/amount' }, { fieldRef: '/input/costCenter' }] })
    expect(screen.getByLabelText('Sample: /input/costCenter')).toBeInTheDocument()
  })

  function setup() {
    const store = createStudioStore()
    store.getState().load({ workflowId: 'workflow', revision: 1, definition: normalizeDefinition(purchase) })
    function Inspector() {
      const node = useStudio(s => s.definition.nodes.find(n => n.type === 'decision')!)
      return <DecisionInspector node={node} />
    }
    render(<QueryClientProvider client={testQueryClient()}><StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}><Inspector /></StudioProvider></QueryClientProvider>)
    return store
  }

  it('authors an OR row that may repeat a field', async () => {
    const store = setup()
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText('Condition group'), 'any')
    const when = store.getState().definition.decisions[0]!.rows[0]!.when
    expect(when).toHaveProperty('any')
    const terms = (when as { any: Array<{ fieldRef: string }> }).any
    expect(terms).toHaveLength(2)
    expect(terms[0]!.fieldRef).toBe('/input/amount')
    // A second alternative may test the same field again, which an AND row does not allow.
    await user.selectOptions(screen.getAllByLabelText('Value to look at')[1]!, '/input/amount')
    expect((store.getState().definition.decisions[0]!.rows[0]!.when as { any: Array<{ fieldRef: string }> }).any[1]!.fieldRef).toBe('/input/amount')
  })

  it('switches to first-match-wins and lets the author reorder rows', async () => {
    const store = setup()
    const user = userEvent.setup()
    expect(screen.queryByRole('button', { name: 'Move row 1 down' })).not.toBeInTheDocument() // only for FIRST
    await user.selectOptions(screen.getByLabelText('Which row applies'), 'FIRST')
    expect(store.getState().definition.decisions[0]!.hitPolicy).toBe('FIRST')
    await user.click(screen.getByRole('button', { name: 'Add a row' }))
    const [first, second] = store.getState().definition.decisions[0]!.rows
    expect(store.getState().definition.decisions[0]!.rows).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'Move row 1 up' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Move row 1 down' }))
    expect(store.getState().definition.decisions[0]!.rows).toEqual([second, first])
    await user.selectOptions(screen.getByLabelText('Which row applies'), 'UNIQUE')
    expect(screen.queryByRole('button', { name: 'Move row 1 down' })).not.toBeInTheDocument()
  })
})
