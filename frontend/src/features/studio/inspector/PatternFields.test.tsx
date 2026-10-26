import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { applyFields, fieldsFromDefinition } from '../model/ops'
import { roundTrip } from '../model/source'
import { validateDefinition } from '@/mocks/validate'
import { sampleValues } from '../test/sampleValues'
import { buildFields, parseFormSchema, parseFormUi } from '@/lib/form/schema'
import { StartInspector } from './StartInspector'

function setup(readOnly = false) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition: normalizeDefinition(purchase) })
  function Inspector() {
    const node = useStudio(s => s.definition.nodes.find(n => n.type === 'start')!)
    return <StartInspector node={node} />
  }
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}><Inspector /></StudioProvider></QueryClientProvider>)
  return store
}

describe('answer patterns in the request form editor (docs/build/S90)', () => {
  it('authors a pattern with a message, shows a matching example and keeps it in the definition', async () => {
    const store = setup(), user = userEvent.setup()
    const card = screen.getByTestId('field-card-title')
    const pattern = within(card).getByLabelText(/Answer pattern/)
    await user.type(pattern, '^[[A-Z]{{2}-\\d{{4}$')
    await user.keyboard('{Enter}')
    expect(store.getState().definition.inputSchema.properties.title?.pattern).toBe('^[A-Z]{2}-\\d{4}$')
    expect(within(card).getByText('The whole answer must match, for example "AA-0000".')).toBeInTheDocument()
    await user.type(within(card).getByLabelText(/Message when the answer does not match/), 'Enter a code like AB-1234.{Enter}')
    expect(store.getState().definition.inputSchema.properties.title?.patternMessage).toBe('Enter a code like AB-1234.')
    // Clearing the pattern removes its message too.
    await user.clear(within(card).getByLabelText(/Answer pattern/))
    await user.keyboard('{Enter}')
    expect(store.getState().definition.inputSchema.properties.title?.pattern).toBeUndefined()
    expect(store.getState().definition.inputSchema.properties.title?.patternMessage).toBeUndefined()
  })

  it('explains an unsafe pattern next to the field and announces it', async () => {
    setup()
    const user = userEvent.setup()
    const card = screen.getByTestId('field-card-title')
    const pattern = within(card).getByLabelText(/Answer pattern/)
    await user.type(pattern, '^(a)\\1$')
    await user.keyboard('{Enter}')
    expect(pattern).toHaveAttribute('aria-invalid', 'true')
    expect(within(card).getByRole('alert')).toHaveTextContent('Backreferences are not supported')
  })

  it('only offers patterns for text questions', () => {
    setup()
    expect(screen.queryByTestId('pattern-options-amount')).not.toBeInTheDocument()
    expect(screen.queryByTestId('pattern-options-costCenter')).not.toBeInTheDocument()
    expect(screen.getByTestId('pattern-options-purpose')).toBeInTheDocument()
  })
})

describe('pattern round trips and mock compiler', () => {
  const definition = () => {
    const def = normalizeDefinition(purchase)
    return applyFields(def, fieldsFromDefinition(def).map(f => f.key === 'title' ? { ...f, pattern: '^[A-Z][^\\\\]*$', patternMessage: 'Start with a capital letter.' } : f))
  }

  it('survives the YAML source view and the field model', () => {
    const def = definition()
    expect(def.inputSchema.properties.title).toMatchObject({ pattern: '^[A-Z][^\\\\]*$', patternMessage: 'Start with a capital letter.' })
    expect(roundTrip(def)).toEqual({ stable: true, detail: '' })
    expect(applyFields(def, fieldsFromDefinition(def))).toBe(def)
  })

  it('reports unsafe patterns like the server compiler and seeds sample answers that match', () => {
    const def = definition()
    const issues = (d: typeof def) => validateDefinition(d, { groups: ['managers', 'finance'], connections: [], isTestWorkspace: true }).filter(i => i.code === 'WF_FORM_PATTERN_INVALID')
    expect(issues(def)).toEqual([])
    def.inputSchema.properties.title!.pattern = '^(?=x)x$'
    expect(issues(def)[0]).toMatchObject({ path: '/inputSchema/properties/title/pattern', severity: 'blocker' })
    def.inputSchema.properties.title!.pattern = '^[A-Z]{2}-\\d{4}$'
    def.inputSchema.properties.costCenter!.pattern = '^[A-Z]+$'
    expect(issues(def).map(i => i.path)).toEqual(['/inputSchema/properties/costCenter/pattern'])
    const fields = buildFields(parseFormSchema(def.inputSchema), parseFormUi(def.inputUi))
    expect(sampleValues(fields).title).toBe('AA-0000')
  })
})
