import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import { buildFields, parseFormSchema, parseFormUi } from '@/lib/form/schema'
import { validateForm, type RawValues } from '@/lib/form/values'
import { FormRenderer } from './FormRenderer'

it('edits bounded object rows with labelled cells and converts values for submission', async () => {
  const schema = parseFormSchema({ properties: { lines: { type: 'array', title: 'Order lines', minItems: 1, maxItems: 1, items: { type: 'object', additionalProperties: false, required: ['quantity'], properties: { quantity: { type: 'integer', title: 'Quantity', minimum: 1 } } } } } })
  function Form() {
    const [values, setValues] = useState<RawValues>({ lines: [] })
    return <><FormRenderer fields={buildFields(schema, {})} values={values} errors={{}} onChange={(key, value) => setValues(previous => ({ ...previous, [key]: value }))} /><output aria-label="Payload">{JSON.stringify(validateForm(schema, {}, values).payload)}</output></>
  }
  render(<Form />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Add Order lines row' }))
  await user.type(screen.getByRole('textbox', { name: /Order lines, row 1: Quantity/ }), '2')
  expect(screen.getByLabelText('Payload')).toHaveTextContent('{"lines":[{"quantity":2}]}')
  expect(screen.getByRole('button', { name: 'Add Order lines row' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: 'Remove Order lines row 1' }))
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})

it('shows conditional grouped questions while omitting their retained answers when hidden again', async () => {
  const schema = parseFormSchema({ properties: { amount: { type: 'number', title: 'Amount' }, reason: { type: 'string', title: 'Reason', description: 'Explain the request.' } } })
  const ui = parseFormUi({ visibleWhen: { reason: { fieldRef: '/input/amount', operator: 'gte', value: 100 } }, requiredWhen: { reason: { fieldRef: '/input/amount', operator: 'gte', value: 100 } }, layout: { sections: [{ id: 'reason', title: 'Review details', fields: ['reason'], columns: 2 }], helpText: 'below' } })
  function Form() {
    const [values, setValues] = useState<RawValues>({ amount: '50', reason: 'Existing answer' })
    return <><FormRenderer fields={buildFields(schema, ui)} values={values} errors={{}} onChange={(key, value) => setValues(previous => ({ ...previous, [key]: value }))} /><output aria-label="Payload">{JSON.stringify(validateForm(schema, ui, values).payload)}</output></>
  }
  render(<Form />)
  const user = userEvent.setup()
  expect(screen.queryByRole('group', { name: 'Review details' })).not.toBeInTheDocument()
  const amount = screen.getByRole('textbox', { name: 'Amount' })
  await user.clear(amount); await user.type(amount, '100')
  expect(screen.getByRole('group', { name: 'Review details' })).toBeInTheDocument()
  expect(screen.getByLabelText(/Reason/)).toHaveAttribute('aria-required', 'true')
  expect(screen.getByLabelText('Payload')).toHaveTextContent('Existing answer')
  const reason = screen.getByLabelText(/Reason/)
  const help = screen.getByText('Explain the request.')
  expect(reason.compareDocumentPosition(help) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  await user.clear(amount); await user.type(amount, '50')
  expect(screen.queryByLabelText(/Reason/)).not.toBeInTheDocument()
  expect(screen.getByLabelText('Payload')).not.toHaveTextContent('Existing answer')
})

it('lets requesters add, edit and remove bounded collection items', async () => {
  const schema = parseFormSchema({ required: ['items'], properties: { items: { type: 'array', title: 'Codes', minItems: 1, maxItems: 2, items: { type: 'string', title: 'Code', minLength: 1 } } } })
  function Form() {
    const [values, setValues] = useState<RawValues>({ items: [] })
    return <><FormRenderer fields={buildFields(schema, {})} values={values} errors={{}} onChange={(key,value) => setValues(current => ({ ...current, [key]: value }))} /><output aria-label="Payload">{JSON.stringify(validateForm(schema, {}, values).payload)}</output></>
  }
  render(<Form />); const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Add Codes item' }))
  await user.type(screen.getByRole('textbox', { name: /Codes: item 1/ }), 'ABC')
  await user.click(screen.getByRole('button', { name: 'Add Codes item' }))
  await user.type(screen.getByRole('textbox', { name: /Codes: item 2/ }), 'DEF')
  expect(screen.getByRole('button', { name: 'Add Codes item' })).toBeDisabled()
  expect(screen.getByLabelText('Payload')).toHaveTextContent('{"items":["ABC","DEF"]}')
  await user.click(screen.getByRole('button', { name: 'Remove Codes item 1' }))
  expect(screen.getByLabelText('Payload')).toHaveTextContent('{"items":["DEF"]}')
})

it('renders a date question as a labelled native date input with its limits', async () => {
  const schema = parseFormSchema({ required: ['startDate'], properties: { startDate: { type: 'string', format: 'date', title: 'Start date', description: 'First working day.', formatMinimum: '2026-10-01', formatMaximum: '2026-12-31' } } })
  function Form() {
    const [values, setValues] = useState<RawValues>({ startDate: '' })
    return <><FormRenderer fields={buildFields(schema, {})} values={values} errors={{}} onChange={(key, value) => setValues(current => ({ ...current, [key]: value }))} /><output aria-label="Payload">{JSON.stringify(validateForm(schema, {}, values).payload)}</output></>
  }
  render(<Form />)
  const input = screen.getByLabelText(/Start date/)
  expect(input).toHaveAttribute('type', 'date')
  expect(input).toHaveAttribute('min', '2026-10-01')
  expect(input).toHaveAttribute('max', '2026-12-31')
  expect(input).toBeRequired()
  expect(input).toHaveAccessibleDescription(/First working day/)
  const user = userEvent.setup()
  await user.type(input, '2026-10-14')
  expect(screen.getByLabelText('Payload')).toHaveTextContent('{"startDate":"2026-10-14"}')
})
