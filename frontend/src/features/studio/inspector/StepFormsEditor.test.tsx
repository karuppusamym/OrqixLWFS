import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { FormView } from '../FormView'
import { StudioProvider } from '../StudioContext'
import { availableFields } from '../model/fieldRefs'
import { accessOf, accessPatch, captureKey, capturePatch, stepFormSummary } from '../model/stepForms'
import { normalizeDefinition, type Definition, type WorkflowNode } from '../model/types'
import { validateDefinition } from '@/mocks/validate'

const CAPTURE = { type: 'object', additionalProperties: false, required: ['poNumber'], properties: { poNumber: { type: 'string', title: 'PO number', maxLength: 20 } } }

function withCapture(): Definition {
  const def = normalizeDefinition(purchase)
  const manager = def.nodes.find((n) => n.id === 'manager')!
  manager.config = { ...manager.config, capture: CAPTURE, fieldAccess: { costCenter: 'hidden' } }
  return def
}

function setup(definition: Definition) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>
        <FormView />
      </StudioProvider>
    </QueryClientProvider>,
  )
  return store
}

const node = (def: Definition, id: string) => def.nodes.find((n) => n.id === id) as WorkflowNode

describe('step form model (S123)', () => {
  it('reads correctable as editable and moves a setting into fieldAccess', () => {
    const def = normalizeDefinition(purchase)
    const manager = node(def, 'manager')
    manager.config = { ...manager.config, correctable: ['amount', 'purpose'] }
    expect(accessOf(manager, 'amount')).toBe('editable')
    expect(accessOf(manager, 'title')).toBe('readOnly')
    expect(accessPatch(manager, 'amount', 'required')).toEqual({ fieldAccess: { amount: 'required' }, correctable: ['purpose'] })
    expect(accessPatch(manager, 'purpose', 'readOnly')).toEqual({ fieldAccess: undefined, correctable: ['amount'] })
  })

  it('makes keys from labels and removes an empty capture', () => {
    expect(captureKey('PO number', [])).toBe('poNumber')
    expect(captureKey('PO number', ['poNumber'])).toBe('poNumber2')
    expect(captureKey('Decision', [])).toBe('decision2')
    expect(captureKey('2nd check', [])).toBe('field2ndCheck')
    expect(capturePatch({}, ['x'])).toEqual({ capture: undefined })
    expect(capturePatch({ a: { type: 'boolean', title: 'A' } }, ['a', 'gone'])).toEqual({
      capture: { type: 'object', additionalProperties: false, required: ['a'], properties: { a: { type: 'boolean', title: 'A' } } },
    })
  })

  it('summarises a step form in one line', () => {
    const def = withCapture()
    expect(stepFormSummary(def, node(def, 'manager'))).toBe('Hides 1 · asks for PO number')
    expect(stepFormSummary(def, node(def, 'finance'))).toBe('')
  })

  it('offers a captured field only after the approved route, and the mock validator agrees', () => {
    const def = withCapture()
    expect(availableFields(def, 'route').map((f) => f.ref)).toContain('/steps/manager/poNumber')
    expect(availableFields(def, 'notifyRejected').map((f) => f.ref)).not.toContain('/steps/manager/poNumber')
    const bad = withCapture()
    node(bad, 'manager').config.capture = { ...CAPTURE, properties: { ...CAPTURE.properties, decision: { type: 'string', title: 'Decision' } } }
    node(bad, 'manager').config.fieldAccess = { nothing: 'hidden' }
    const codes = validateDefinition(bad, { groups: ['managers', 'finance-approvers'], connections: [] } as never).map((i) => `${i.code} ${i.path}`)
    expect(codes).toContain('WF_STEP_FORM_INVALID /nodes/1/config/capture/properties/decision')
    expect(codes).toContain('WF_STEP_FORM_INVALID /nodes/1/config/fieldAccess/nothing')
  })
})

describe('step forms on the Form tab (S123)', () => {
  it('sets field access in the grid and adds a field the step fills in', async () => {
    const user = userEvent.setup()
    const store = setup(normalizeDefinition(purchase))
    const grid = screen.getByTestId('step-form-grid')
    await user.selectOptions(within(grid).getByRole('combobox', { name: 'Cost center at Review request' }), 'hidden')
    expect(node(store.getState().definition, 'manager').config.fieldAccess).toEqual({ costCenter: 'hidden' })
    await user.click(within(screen.getByTestId('capture-manager')).getByRole('button', { name: 'Add a field' }))
    expect(Object.keys((node(store.getState().definition, 'manager').config.capture as typeof CAPTURE).properties)).toEqual(['newField1'])
  })

  it('previews the form as an approval step sees it', async () => {
    const user = userEvent.setup()
    setup(withCapture())
    await user.selectOptions(screen.getByLabelText('Show as'), 'manager')
    const preview = screen.getByTestId('step-preview')
    expect(within(preview).queryByText('Cost center')).toBeNull()
    expect(within(preview).getByText('1 question is hidden at this step.')).toBeInTheDocument()
    expect(within(preview).getByLabelText(/PO number/)).toBeInTheDocument()
  })
})
