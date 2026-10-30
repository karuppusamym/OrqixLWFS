import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../examples/purchase-approval.json'
import { FormView } from './FormView'
import { StudioProvider } from './StudioContext'
import { StartInspector } from './inspector/StartInspector'
import { describeFormCondition, questionRules } from './model/formRules'
import { formFocusKey, locateIssue } from './model/issues'
import { normalizeDefinition, type Definition } from './model/types'

/** Purchase approval with "Business purpose" shown and required only when the amount is at least 5000, in a group. */
function conditional(): Definition {
  const def = normalizeDefinition(purchase)
  return {
    ...def,
    inputUi: {
      ...def.inputUi,
      visibleWhen: { purpose: { fieldRef: '/input/amount', operator: 'gte', value: 5000 } },
      requiredWhen: { purpose: { all: [{ fieldRef: '/input/amount', operator: 'gte', value: 5000 }, { fieldRef: '/input/title', operator: 'ne', value: 'Test' }] } },
      layout: { sections: [{ id: 'why', title: 'Why', fields: ['purpose'], columns: 1 }] },
    },
  }
}

function setup(definition: Definition, extra: { openForm?: () => void; readOnly?: boolean } = {}, ui: 'form' | 'start' = 'form') {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: extra.readOnly ?? false, connections: { responseType: () => undefined }, openForm: extra.openForm }}>
        {ui === 'form' ? <FormView /> : <StartInspector />}
      </StudioProvider>
    </QueryClientProvider>,
  )
  return store
}

describe('plain-language form rules (S120)', () => {
  it('describes single and grouped conditions with question labels', () => {
    const def = conditional()
    expect(describeFormCondition(def, { fieldRef: '/input/amount', operator: 'gte', value: 5000 })).toBe('Amount (USD) is at least 5000')
    expect(describeFormCondition(def, { any: [{ fieldRef: '/input/title', operator: 'eq', value: 'Laptop' }, { fieldRef: '/input/gone', operator: 'eq', value: true }] }))
      .toBe('What are you buying? is "Laptop" or gone is Yes')
    expect(questionRules(def).purpose).toEqual({
      shown: 'Shown only when Amount (USD) is at least 5000',
      required: 'Required when Amount (USD) is at least 5000 and What are you buying? is not "Test"',
      group: 'Why',
    })
    expect(questionRules(def).amount).toBeUndefined()
  })

  it('joins a question rule and its group rule', () => {
    const def = conditional()
    def.inputUi!.layout!.sections![0]!.visibleWhen = { fieldRef: '/input/title', operator: 'ne', value: '' }
    expect(questionRules(def).purpose?.shown).toBe('Shown only when Amount (USD) is at least 5000, and when What are you buying? is not ""')
  })
})

describe('Form tab (S120)', () => {
  it('lists questions as compact rows with their rules and opens one on click', async () => {
    setup(conditional())
    const user = userEvent.setup()
    const row = within(screen.getByTestId('question-purpose'))
    expect(row.getByText('In group "Why"')).toBeInTheDocument()
    expect(row.getByText('Shown only when Amount (USD) is at least 5000')).toBeInTheDocument()
    expect(screen.queryByTestId('field-card-purpose')).not.toBeInTheDocument()
    const toggle = row.getByRole('button', { expanded: false })
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(within(screen.getByTestId('field-card-purpose')).getByLabelText(/^Label/)).toHaveValue('Business purpose')
  })

  it('applies rules in the live preview as answers are typed', async () => {
    setup(conditional())
    const user = userEvent.setup()
    const preview = within(screen.getByTestId('form-preview'))
    expect(screen.getByTestId('form-preview-status')).toHaveTextContent('Showing 3 of 4 questions. Hidden by rules with these answers: Business purpose.')
    expect(preview.queryByLabelText(/Business purpose/)).not.toBeInTheDocument()
    await user.type(preview.getByLabelText(/Amount \(USD\)/), '6000')
    expect(screen.getByTestId('form-preview-status')).toHaveTextContent('Showing all 4 questions.')
    expect(preview.getByRole('group', { name: 'Why' })).toBeInTheDocument()
    await user.click(preview.getByRole('button', { name: 'Clear answers' }))
    expect(screen.getByTestId('form-preview-status')).toHaveTextContent('Showing 3 of 4 questions.')
  })

  it('fills sample answers, checks them and never submits', async () => {
    setup(normalizeDefinition(purchase))
    const user = userEvent.setup()
    const preview = within(screen.getByTestId('form-preview'))
    await user.click(preview.getByRole('button', { name: 'Check my answers' }))
    expect(preview.getAllByText(/required|Enter|Choose/i).length).toBeGreaterThan(0)
    await user.click(preview.getByRole('button', { name: 'Fill sample answers' }))
    expect(preview.getByLabelText(/What are you buying\?/)).not.toHaveValue('')
    expect(preview.getByRole('button', { name: /Submit/ })).toBeDisabled()
  })

  it('opens and focuses a question it adds, and the preview follows edits', async () => {
    const store = setup(normalizeDefinition(purchase))
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /Add a question/ }))
    const added = Object.keys(store.getState().definition.inputSchema.properties).find((k) => !(k in normalizeDefinition(purchase).inputSchema.properties))!
    const label = within(screen.getByTestId(`field-card-${added}`)).getByLabelText(/^Label/)
    await vi.waitFor(() => expect(label).toHaveFocus())
    await user.clear(label)
    await user.type(label, 'Delivery address')
    await user.tab()
    expect(within(screen.getByTestId('form-preview')).getByLabelText(/Delivery address/)).toBeInTheDocument()
  })

  it('shows rows without edit controls when read-only', () => {
    setup(conditional(), { readOnly: true })
    expect(screen.queryByRole('button', { name: /Add a question/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Delete "Business purpose"/ })).not.toBeInTheDocument()
    expect(within(screen.getByTestId('question-purpose')).getByRole('button', { expanded: false })).toBeEnabled()
  })
})

describe('start step inspector (S120)', () => {
  it('summarises the form and opens the Form tab', async () => {
    const openForm = vi.fn()
    setup(conditional(), { openForm }, 'start')
    const user = userEvent.setup()
    const summary = within(screen.getByTestId('request-form-summary'))
    expect(summary.getByText('4 questions')).toBeInTheDocument()
    expect(summary.getByText(/1 with a rule/)).toBeInTheDocument()
    expect(screen.queryByText('Progress stages', { selector: 'h3' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Edit the form' }))
    expect(openForm).toHaveBeenCalledOnce()
  })
})

describe('request-form issues open the Form tab (S120)', () => {
  const def = conditional()
  const start = def.nodes.find((n) => n.type === 'start')!.id
  it('locates question problems, rule problems and whole-form problems', () => {
    expect(locateIssue(def, { path: '/inputSchema/properties/title/pattern' })).toMatchObject({ nodeId: start, fieldKey: 'field.title' })
    expect(locateIssue(def, { path: '/inputUi/visibleWhen/purpose/fieldRef' })).toMatchObject({ nodeId: start, fieldKey: 'field.purpose' })
    expect(locateIssue(def, { path: '/inputUi/layout/sections/0/visibleWhen' })).toMatchObject({ nodeId: start, fieldKey: 'form' })
    expect(locateIssue(def, { path: '/inputUi/visibleWhen/gone' })).toMatchObject({ fieldKey: 'form' })
  })
  it('names the question to open only for the start step', () => {
    expect(formFocusKey(def, start, 'field.title')).toBe('title')
    expect(formFocusKey(def, start, 'field.purpose.items')).toBe('purpose')
    expect(formFocusKey(def, start, 'form')).toBe('')
    expect(formFocusKey(def, start, 'label')).toBeUndefined()
    expect(formFocusKey(def, start, null)).toBeUndefined()
    expect(formFocusKey(def, 'manager', 'field.title')).toBeUndefined()
  })
})
