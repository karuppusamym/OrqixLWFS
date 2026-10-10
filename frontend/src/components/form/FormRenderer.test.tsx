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
