import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { buildFields, parseFormSchema, parseFormUi } from '@/lib/form/schema'
import { FormView } from '../FormView'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

function setup() {
  const store = createStudioStore()
  const definition = normalizeDefinition(purchase)
  definition.inputSchema.properties.amount!.incidentEditable = true
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}><FormView /></StudioProvider></QueryClientProvider>)
  return store
}

/** Questions are compact rows on the Form tab (S120); open one to edit it. */
async function openQuestion(user: ReturnType<typeof userEvent.setup>, key: string) {
  await user.click(within(screen.getByTestId(`question-${key}`)).getByRole('button', { expanded: false }))
  return within(screen.getByTestId(`field-card-${key}`))
}

describe('conditional form and row authoring', () => {
  it('reorders sections and questions without changing their field rules', async () => {
    const store = setup(), user = userEvent.setup()
    act(() => store.getState().apply(d => ({ ...d, inputUi: { ...d.inputUi, layout: { sections: [
      { id: 'review', title: 'Review', fields: ['purpose', 'amount'], columns: 2 },
      { id: 'identity', title: 'Identity', fields: ['title'], columns: 1 },
    ] } } })))
    const sections = store.getState().definition.inputUi?.layout?.sections
    expect(sections).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: 'Move amount up in Review' }))
    expect(store.getState().definition.inputUi?.layout?.sections?.[0]?.fields).toEqual(['amount', 'purpose'])
    await user.click(screen.getByRole('button', { name: 'Move group Review down' }))
    expect(store.getState().definition.inputUi?.layout?.sections?.map(s => s.id)).toEqual(['identity', 'review'])
    const current = store.getState().definition
    expect(buildFields(parseFormSchema(current.inputSchema), parseFormUi(current.inputUi)).slice(0, 3).map(f => f.key)).toEqual(['title', 'amount', 'purpose'])
    expect(store.getState().definition.inputSchema.properties.amount?.title).toBe('Amount (USD)')
    expect(store.getState().definition.inputSchema.properties.amount?.incidentEditable).toBe(true)
    act(() => store.getState().undo())
    expect(store.getState().definition.inputUi?.layout?.sections?.[0]?.id).toBe('review')
  })
  it('authors a conditional field and section and preserves them across question edits', async () => {
    const store = setup(), user = userEvent.setup()
    const purpose = await openQuestion(user, 'purpose')
    await user.click(purpose.getByRole('checkbox', { name: 'Show only when a condition matches' }))
    await user.selectOptions(purpose.getByLabelText('Value to look at'), '/input/amount')
    await user.click(purpose.getByRole('checkbox', { name: 'Require an answer when a condition matches' }))
    await user.click(screen.getByRole('button', { name: 'Add a group' }))
    await user.click(screen.getByRole('checkbox', { name: 'Include Business purpose' }))
    await user.selectOptions(screen.getByLabelText('Group columns'), '2')
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
    const purpose = await openQuestion(user, 'purpose')
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
