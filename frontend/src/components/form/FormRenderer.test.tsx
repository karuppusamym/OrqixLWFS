import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import { buildFields, parseFormSchema } from '@/lib/form/schema'
import { validateForm, type RawValues } from '@/lib/form/values'
import { FormRenderer } from './FormRenderer'

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
