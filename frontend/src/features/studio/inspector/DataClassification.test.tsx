import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { applyFields, fieldsFromDefinition } from '../model/ops'
import { FormView } from '../FormView'

function setup() {
  const store = createStudioStore()
  const definition = normalizeDefinition(structuredClone(purchase))
  definition.inputSchema.properties.title!.searchable = true
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}><FormView /></StudioProvider></QueryClientProvider>)
  return store
}

async function openAdvanced(user: ReturnType<typeof userEvent.setup>, key: string) {
  await user.click(within(screen.getByTestId(`question-${key}`)).getByRole('button', { expanded: false }))
  const card = within(screen.getByTestId(`field-card-${key}`))
  await user.click(card.getByText('Advanced', { selector: 'summary' }))
  return card
}

describe('data classification of request questions (CG-I05)', () => {
  it('classifies a question as sensitive, which turns off searching by it, and clears it again', async () => {
    const store = setup(), user = userEvent.setup()
    const card = await openAdvanced(user, 'title')
    const select = card.getByLabelText(/Data classification/)
    expect(select).toHaveValue('')
    expect(card.getByText(/masked for everyone and revealed only with permission/)).toBeInTheDocument()
    expect(card.getByRole('checkbox', { name: /Operators can search requests by this answer/ })).toBeChecked()

    await user.selectOptions(select, 'sensitive')
    expect(store.getState().definition.inputSchema.properties.title?.classification).toBe('sensitive')
    expect(store.getState().definition.inputSchema.properties.title?.searchable).toBeUndefined()
    expect(card.queryByRole('checkbox', { name: /Operators can search requests by this answer/ })).not.toBeInTheDocument()

    await user.selectOptions(card.getByLabelText(/Data classification/), 'internal')
    expect(store.getState().definition.inputSchema.properties.title?.classification).toBe('internal')
    expect(card.getByRole('checkbox', { name: /Operators can search requests by this answer/ })).not.toBeChecked()

    await user.selectOptions(card.getByLabelText(/Data classification/), '')
    expect(store.getState().definition.inputSchema.properties.title?.classification).toBeUndefined()
  })

  it('round-trips the classification and never writes it on files or lists', () => {
    const def = normalizeDefinition(structuredClone(purchase))
    const fields = fieldsFromDefinition(def).map((f) => (f.key === 'purpose' ? { ...f, classification: 'personal' as const, searchable: true } : f))
    fields.push({ ...fields[0]!, key: 'receipt', label: 'Receipt', kind: 'file', classification: 'sensitive' })
    const next = applyFields(def, fields)
    expect(next.inputSchema.properties.purpose).toMatchObject({ classification: 'personal' })
    expect(next.inputSchema.properties.purpose?.searchable).toBeUndefined()
    expect(next.inputSchema.properties.receipt?.classification).toBeUndefined()
    expect(fieldsFromDefinition(next).find((f) => f.key === 'purpose')?.classification).toBe('personal')
  })
})
