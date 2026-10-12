import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { DecisionInspector } from './DecisionInspector'

describe('conjunctive decision authoring', () => {
  it('offers bounded All conditions and a sample for every input', async () => {
    const store = createStudioStore()
    store.getState().load({ workflowId: 'workflow', revision: 1, definition: normalizeDefinition(purchase) })
    function Inspector() {
      const node = useStudio(s => s.definition.nodes.find(n => n.type === 'decision')!)
      return <DecisionInspector node={node} />
    }
    render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}><Inspector /></StudioProvider>)
    const user = userEvent.setup()
    expect(screen.queryByRole('option', { name: 'Any comparison (OR)' })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Condition group'), 'all')
    const fields = screen.getAllByLabelText('Value to look at')
    await user.selectOptions(fields[1]!, '/input/costCenter')
    expect(store.getState().definition.decisions[0]!.rows[0]!.when).toMatchObject({ all: [{ fieldRef: '/input/amount' }, { fieldRef: '/input/costCenter' }] })
    expect(screen.getByLabelText('Sample: /input/costCenter')).toBeInTheDocument()
  })
})
