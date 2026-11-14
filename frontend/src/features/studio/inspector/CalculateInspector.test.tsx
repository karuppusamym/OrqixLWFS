import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { availableFields } from '../model/fieldRefs'
import { conditionToExpression } from '../model/conditionText'
import { normalizeDefinition } from '../model/types'
import { BranchInspector } from './BranchInspector'
import { CalculateInspector } from './CalculateInspector'

function setup(readOnly = false) {
  const definition = normalizeDefinition(structuredClone(purchase))
  const node = definition.nodes.find((n) => n.id === 'amountPolicy')!
  node.type = 'calculate'
  node.label = 'Work out the totals'
  node.config = { outputs: [{ key: 'tax', expression: 'round(input.amount * 0.2, 2)', type: 'number', scale: 2 }] }
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  function Inspector({ id }: { id: string }) {
    const current = useStudio((s) => s.definition.nodes.find((n) => n.id === id)!)
    return current.type === 'branch' ? <BranchInspector node={current} /> : <CalculateInspector node={current} />
  }
  const ui = (id: string) => <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}><Inspector id={id} /></StudioProvider>
  return { store, ui }
}

const outputs = (store: ReturnType<typeof setup>['store']) =>
  store.getState().definition.nodes.find((n) => n.id === 'amountPolicy')!.config.outputs as Array<Record<string, unknown>>

describe('inspector: Calculate (docs/build/S137)', () => {
  it('shows the type, the plain-language reading and a problem with its position as the designer types', async () => {
    const { store, ui } = setup()
    render(ui('amountPolicy'))
    const user = userEvent.setup()
    const formula = screen.getByLabelText('Formula')
    expect(screen.getByText(/Gives a number\./)).toBeInTheDocument()
    expect(screen.getByText('Amount (USD) times 0.2 rounded to 2 decimal places')).toBeInTheDocument()
    await user.clear(formula)
    await user.type(formula, 'input.amount + input.title')
    expect(screen.getByText(/\+ adds two numbers or joins two texts/)).toBeInTheDocument()
    expect(screen.getByText(/at character 14/)).toBeInTheDocument()
    await user.clear(formula)
    await user.type(formula, 'input.title')
    expect(screen.getByText('This must give a number, but it gives text.')).toBeInTheDocument()
    await user.tab()
    expect(outputs(store)[0]!.expression).toBe('input.title')
  })

  it('adds outputs that use earlier ones by name and inserts fields from the picker', async () => {
    const { store, ui } = setup()
    render(ui('amountPolicy'))
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Add output' }))
    const second = within(screen.getByTestId('calc-output-1'))
    const formula = second.getByLabelText('Formula')
    await user.clear(formula)
    await user.type(formula, 'tax * 2')
    await user.tab()
    expect(second.getByText(/Gives a number/)).toBeInTheDocument()
    await user.selectOptions(second.getByLabelText('Insert a field into Formula'), '/input/amount')
    expect(outputs(store)[1]!.expression).toBe('tax * 2 input.amount')
    await user.selectOptions(second.getByLabelText('It gives'), 'string')
    expect(outputs(store)[1]!.type).toBe('string')
    expect(second.queryByLabelText('Rounding')).not.toBeInTheDocument()
  })

  it('offers the outputs to later steps with their declared types', () => {
    const { store } = setup()
    const fields = availableFields(store.getState().definition, 'route')
    expect(fields.find((f) => f.ref === '/steps/amountPolicy/tax')?.type).toBe('number')
  })

  it('switches a path to a formula that starts from what it already tests', async () => {
    const { store, ui } = setup()
    store.getState().apply((d) => {
      const t5 = d.transitions.find((t) => t.id === 't5')!
      t5.condition = { fieldRef: '/input/amount', operator: 'gte', value: 5000 }
      return d
    })
    render(ui('route'))
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /Write a formula instead/ }))
    const t5 = () => store.getState().definition.transitions.find((t) => t.id === 't5')!
    expect(t5().condition).toEqual({ expression: 'input.amount >= 5000' })
    expect(screen.getByText(/Gives true or false/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Use a simple comparison instead' }))
    expect(t5().condition).toHaveProperty('operator')
  })

  it('writes structured conditions as the equivalent expression', () => {
    expect(conditionToExpression({ all: [{ fieldRef: '/input/a', operator: 'gt', value: 1 }, { fieldRef: '/steps/s-1/b', operator: 'eq', value: 'x' }] }))
      .toBe('input.a > 1 and steps.s-1.b = "x"')
    expect(conditionToExpression({ fieldRef: '/input/a', operator: 'between', value: { from: 1, to: 5, includeTo: false } })).toBe('input.a >= 1 and input.a < 5')
    expect(conditionToExpression({ fieldRef: '/input/a', operator: 'in', value: ['EU', 'UK'] })).toBe('input.a in ["EU", "UK"]')
  })
})
