import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { StartInspector } from './StartInspector'

function setup() {
  const store = createStudioStore()
  const definition = normalizeDefinition(purchase)
  definition.inputSchema.properties.amount!.incidentEditable = true
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  function Inspector() {
    const node = useStudio(s => s.definition.nodes.find(n => n.type === 'start')!)
    return <StartInspector node={node} />
  }
  render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}><Inspector /></StudioProvider>)
  return store
}

describe('conditional form and row authoring', () => {
  it('authors a conditional field and section and preserves them across question edits', async () => {
    const store = setup(), user = userEvent.setup()
    const purpose = within(screen.getByTestId('field-card-purpose'))
    await user.click(purpose.getByRole('checkbox', { name: 'Show only when a condition matches' }))
    await user.selectOptions(purpose.getByLabelText('Value to look at'), '/input/amount')
    await user.click(purpose.getByRole('checkbox', { name: 'Require an answer when a condition matches' }))
    await user.click(screen.getByRole('button', { name: 'Add form section' }))
    await user.click(screen.getByRole('checkbox', { name: 'Include Business purpose' }))
    await user.selectOptions(screen.getByLabelText('Section columns'), '2')
    const title = purpose.getByLabelText(/^Label/)
    await user.clear(title); await user.type(title, 'Reason for purchase'); await user.tab()
    const def = store.getState().definition
    expect(def.inputUi?.visibleWhen?.purpose).toMatchObject({ fieldRef: '/input/amount' })
    expect(def.inputUi?.requiredWhen?.purpose).toBeDefined()
    expect(def.inputUi?.layout?.sections?.[0]).toMatchObject({ fields: ['purpose'], columns: 2 })
    expect(def.inputSchema.properties.amount!.incidentEditable).toBe(true)
  })
  it('authors bounded row columns with names, types and required flags', async () => {
    const store = setup(), user = userEvent.setup()
    const purpose = within(screen.getByTestId('field-card-purpose'))
    await user.selectOptions(purpose.getByLabelText('Type'), 'collection')
    await user.selectOptions(purpose.getByLabelText('Item type'), 'object')
    await user.click(purpose.getByRole('button', { name: 'Add row column' }))
    const columns = purpose.getAllByLabelText('Column type')
    await user.selectOptions(columns[1]!, 'integer')
    await user.click(purpose.getByRole('checkbox', { name: 'Required column Column 2' }))
    const item = store.getState().definition.inputSchema.properties.purpose!.items!
    expect(item).toMatchObject({ type: 'object', additionalProperties: false, required: ['name','column2'], properties: { column2: { type: 'integer' } } })
    expect(purpose.queryByRole('option', { name: 'File upload' })).toBeInTheDocument() // parent question may change kind; row column controls remain scalar.
    expect(purpose.getAllByLabelText('Column type').every(select => !within(select).queryByRole('option', { name: 'File upload' }))).toBe(true)
  })
})
