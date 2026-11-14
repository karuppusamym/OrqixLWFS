import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import { buildFields, parseFormSchema, parseFormUi } from '@/lib/form/schema'
import { validateForm, type RawValues } from '@/lib/form/values'
import { FormRenderer } from './FormRenderer'

function Harness({ schemaJson, uiJson, initial, lookup }: { schemaJson: unknown; uiJson?: unknown; initial: RawValues; lookup?: { workspaceId: string; workflowId: string } }) {
  const schema = parseFormSchema(schemaJson)
  const ui = parseFormUi(uiJson ?? {})
  const [values, setValues] = useState<RawValues>(initial)
  return <>
    <FormRenderer fields={buildFields(schema, ui)} values={values} errors={{}} lookup={lookup} onChange={(key, value) => setValues((previous) => ({ ...previous, [key]: value }))} />
    <output aria-label="Payload">{JSON.stringify(validateForm(schema, ui, values).payload)}</output>
  </>
}

it('ticks checklist choices and adds each tag once', async () => {
  render(<Harness initial={{ checks: [], tags: [] }}
    schemaJson={{ properties: {
      checks: { type: 'array', title: 'Checks done', minItems: 1, maxItems: 3, uniqueItems: true, items: { type: 'string', title: 'Check', enum: ['ID', 'Address', 'Bank'] } },
      tags: { type: 'array', title: 'Labels', minItems: 1, maxItems: 2, uniqueItems: true, items: { type: 'string', title: 'Tag' } },
    } }}
    uiJson={{ widgets: { checks: 'checklist', tags: 'tags' } }} />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('checkbox', { name: 'Bank' }))
  await user.click(screen.getByRole('checkbox', { name: 'ID' }))
  const add = screen.getByRole('textbox', { name: 'Add to Labels' })
  await user.type(add, 'urgent{Enter}')
  await user.type(add, 'urgent{Enter}')
  await user.type(add, 'vendor{Enter}')
  expect(screen.getByLabelText('Payload')).toHaveTextContent('{"checks":["ID","Bank"],"tags":["urgent","vendor"]}')
  expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: 'Remove urgent' }))
  expect(screen.getByLabelText('Payload')).toHaveTextContent('"tags":["vendor"]')
})

it('enters a time of day and a date-time in the question zone', async () => {
  render(<Harness initial={{ start: '', at: '' }} schemaJson={{ properties: {
    start: { type: 'string', title: 'Start time', format: 'time' },
    at: { type: 'string', title: 'Delivery', format: 'date-time', timeZone: 'Europe/Berlin' },
  } }} />)
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Start time'), '09:30')
  const at = screen.getByLabelText('Delivery')
  await user.type(at, '2026-11-06T14:30')
  expect(screen.getByLabelText('Payload')).toHaveTextContent('"start":"09:30"')
  expect(screen.getByLabelText('Payload')).toHaveTextContent('"at":"2026-11-06T14:30:00+01:00"')
  expect(screen.getByText(/Time zone: Europe\/Berlin/)).toBeInTheDocument()
})

it('searches lookup options as the person types and stores the chosen value', async () => {
  render(<Harness initial={{ buyer: '' }} lookup={{ workspaceId: 'ws-1', workflowId: 'wf-1' }} schemaJson={{ properties: {
    buyer: { type: 'string', title: 'Buyer', lookup: { connection: 'servicenow-users', operation: 'searchUsers', searchInput: 'text', rows: '/result/[*]', valueColumn: 'email', labelColumn: 'name', minSearchLength: 2 } },
  } }} />)
  const user = userEvent.setup()
  const box = screen.getByRole('combobox', { name: 'Buyer' })
  await user.type(box, 'an')
  const option = await screen.findByRole('option', { name: /Ann Buyer/ })
  await user.click(option)
  expect(screen.getByLabelText('Payload')).toHaveTextContent('{"buyer":"ann.buyer@supplier.example"}')
  // Typing again clears the chosen value until an option is chosen.
  await user.type(box, 'x')
  expect(screen.getByLabelText('Payload')).toHaveTextContent('{}')
})
