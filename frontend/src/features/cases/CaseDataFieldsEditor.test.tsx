import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { ViewDefinition } from '@/features/views/model'
import { setCurrentUser } from '@/mocks/handlers'
import { seedDb } from '@/mocks/seed'
import { WS_PURCHASING } from '@/mocks/state'
import { type CaseDataError, CaseDataFields, CaseDataFieldsEditor, typedValues, type DataField, type DataInputs } from './CaseDataFields'
import { caseField, caseFieldProblem, caseFormSchema, fieldModel, legacyKeyWarning, suggestCaseKey } from './caseFields'
import { createCaseDefinition, getCaseData, getCaseDefinition, startCaseInstance, updateCaseData } from './api'

/** docs/build/S125: case data fields use the request form's field editor, renderer and checks. */
function Editor({ initial = [], onFields }: { initial?: DataField[]; onFields?: (fields: DataField[]) => void }) {
  const [fields, setFields] = useState<DataField[]>(initial)
  return <CaseDataFieldsEditor fields={fields} disabled={false} onChange={next => { setFields(next); onFields?.(next) }} />
}

async function openField(user: ReturnType<typeof userEvent.setup>, key: string) {
  const row = screen.getByTestId(`case-question-${key}`)
  const toggle = within(row).getAllByRole('button')[0]!
  if (toggle.getAttribute('aria-expanded') === 'false') await user.click(toggle)
  return screen.getByTestId(`case-field-card-${key}`)
}

describe('case data fields as request questions (docs/build/S125)', () => {
  it('converts every single-value kind to the case contract and back', () => {
    const fields: DataField[] = [
      { key: 'summary', label: 'Summary', type: 'text', required: true, maxLength: 80, minLength: 3, pattern: '^[A-Z].*$', patternMessage: 'Start with a capital.', description: 'One line.' },
      { key: 'notes', label: 'Notes', type: 'text', widget: 'textarea' },
      { key: 'amount', label: 'Amount', type: 'number', widget: 'money', multipleOf: 0.01, minimum: 0.01 },
      { key: 'score', label: 'Score', type: 'number', minimum: 0, maximum: 10 },
      { key: 'count', label: 'Count', type: 'integer' },
      { key: 'due', label: 'Due', type: 'date', formatMinimum: '2026-01-01', formatMaximum: '2026-12-31' },
      { key: 'checked', label: 'Checked', type: 'boolean', required: false },
      { key: 'priority', label: 'Priority', type: 'choice', options: ['normal', 'urgent'] },
    ]
    expect(fields.map(fieldModel).map(model => model.kind)).toEqual(['text', 'longText', 'money', 'number', 'integer', 'date', 'yesNo', 'choice'])
    expect(fields.map(fieldModel).map(caseField)).toEqual(fields.map(field => ({ required: false, ...field })))
    // A changed type keeps only that type's settings, as the server requires.
    expect(caseField({ ...fieldModel(fields[0]!), kind: 'number' })).toEqual({ key: 'summary', label: 'Summary', type: 'number', required: true, description: 'One line.' })
    // Earlier fields (nulls, no S125 settings) read the same.
    expect(caseField(fieldModel({ key: 'Old_Key', label: 'Old', type: 'text', required: false, maxLength: null, minimum: null, maximum: null, options: null }))).toEqual({ key: 'Old_Key', label: 'Old', type: 'text', required: false })
    expect(caseFormSchema(fields).schema.properties.amount).toMatchObject({ type: 'number', multipleOf: 0.01, minimum: 0.01 })
    expect(caseFormSchema(fields).ui.widgets).toMatchObject({ notes: 'textarea', amount: 'money', checked: 'checkbox', priority: 'select' })
  })

  it('adds, types and checks a field with the shared editor and keeps new keys in the question pattern', async () => {
    let latest: DataField[] = []
    render(<Editor onFields={fields => { latest = fields }} />)
    const user = userEvent.setup()
    expect(screen.getByText('No case data fields yet.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add case data field' }))
    expect(latest).toEqual([{ key: 'field1', label: 'New field', type: 'text', required: false }])
    const card = within(screen.getByTestId('case-field-card-field1'))
    expect(card.getByLabelText(/^Label/)).toHaveFocus()
    await user.clear(card.getByLabelText(/^Label/)); await user.type(card.getByLabelText(/^Label/), 'Claim amount{Enter}')
    // Case data holds single values: no collections or files, and no request-form rules or incident setting.
    expect(card.getByRole('option', { name: 'Money (USD)' })).toBeInTheDocument()
    expect(card.queryByRole('option', { name: 'Collection' })).not.toBeInTheDocument()
    expect(card.queryByRole('option', { name: 'File upload' })).not.toBeInTheDocument()
    expect(card.queryByText(/operator correction/)).not.toBeInTheDocument()
    await user.selectOptions(card.getByLabelText('Type'), 'money')
    await user.click(card.getByRole('checkbox', { name: 'Required' }))
    await user.type(card.getByLabelText(/^Help text/), 'From the claim letter.{Enter}')
    expect(latest).toEqual([{ key: 'field1', label: 'Claim amount', type: 'number', widget: 'money', multipleOf: 0.01, required: true, description: 'From the claim letter.' }])
    expect(screen.getByRole('button', { name: /^Field 1: Claim amount/ })).toHaveAccessibleDescription(/Money \(USD\) · Required/)

    // The key is under Advanced, with the request-question rule.
    await user.click(card.getByText('Advanced'))
    const key = card.getByLabelText('Field key')
    await user.clear(key); await user.type(key, 'Claim_Amount{Enter}')
    expect(card.getByText('Start with a lowercase letter; use letters and digits only.')).toBeInTheDocument()
    expect(latest[0]!.key).toBe('field1')
    await user.clear(key); await user.type(key, 'claimAmount{Enter}')
    expect(latest[0]!.key).toBe('claimAmount')
    expect(document.getElementById('case-field-claimAmount-key')).toHaveFocus()

    // Text checks: shortest, longest and an answer pattern checked by the same safe engine as the server.
    await user.click(screen.getByRole('button', { name: 'Add case data field' }))
    const text = within(screen.getByTestId('case-field-card-field1'))
    await user.click(text.getByText('Advanced'))
    await user.type(text.getByLabelText(/^Shortest/), '9{Enter}')
    await user.type(text.getByLabelText(/^Answer pattern/), '^CASE-\\d{{4}$')
    await user.keyboard('{Enter}')
    expect(text.getByText('The whole answer must match, for example "CASE-0000".')).toBeInTheDocument()
    await user.type(text.getByLabelText(/^Message when the answer does not match/), 'Enter a reference like CASE-1234.{Enter}')
    expect(latest[1]).toEqual({ key: 'field1', label: 'New field', type: 'text', required: false, minLength: 9, pattern: '^CASE-\\d{4}$', patternMessage: 'Enter a reference like CASE-1234.' })
    expect(caseFieldProblem(latest)).toBeUndefined()

    // Choices, reorder and delete.
    await user.selectOptions(text.getByLabelText('Type'), 'choice')
    expect(latest[1]).toEqual({ key: 'field1', label: 'New field', type: 'choice', required: false, options: ['Option 1', 'Option 2'] })
    await user.click(screen.getByRole('button', { name: 'Move "New field" up' }))
    expect(latest.map(field => field.key)).toEqual(['field1', 'claimAmount'])
    await user.click(screen.getByRole('button', { name: 'Delete "New field"' }))
    expect(latest.map(field => field.key)).toEqual(['claimAmount'])
  })

  it('warns about a key in the older pattern without changing it', async () => {
    let latest: DataField[] = []
    render(<Editor initial={[{ key: 'Customer_Name', label: 'Customer', type: 'text', required: true }]} onFields={fields => { latest = fields }} />)
    const user = userEvent.setup()
    expect(screen.getByRole('button', { name: /^Field 1: Customer/ })).toHaveAccessibleDescription(/Key Customer_Name uses the older pattern/)
    const card = within(await openField(user, 'Customer_Name'))
    expect(card.getByTestId('case-key-warning-Customer_Name')).toHaveTextContent('It keeps working')
    expect(card.getByTestId('case-key-warning-Customer_Name')).toHaveTextContent('for example customerName')
    await user.type(card.getByLabelText(/^Help text/), 'Legal name{Enter}')
    expect(latest).toEqual([{ key: 'Customer_Name', label: 'Customer', type: 'text', required: true, description: 'Legal name' }])
    expect(legacyKeyWarning('customerName')).toBeUndefined()
    expect(suggestCaseKey('claim_2')).toBe('claim2')
    expect(suggestCaseKey('_9')).toBeUndefined()
  })

  it('renders and checks case data with the request form renderer', async () => {
    const fields: DataField[] = [
      { key: 'reference', label: 'Reference', type: 'text', required: true, pattern: '^CASE-\\d{4}$', patternMessage: 'Enter a reference like CASE-1234.', description: 'From the claim letter.' },
      { key: 'amount', label: 'Amount', type: 'number', widget: 'money', multipleOf: 0.01 },
      { key: 'notes', label: 'Notes', type: 'text', widget: 'textarea' },
      { key: 'confirmed', label: 'Confirmed', type: 'boolean', required: true },
      { key: 'priority', label: 'Priority', type: 'choice', options: ['normal', 'urgent'] },
    ]
    function Form({ readOnly = false, view }: { readOnly?: boolean; view?: ViewDefinition }) {
      const [values, setValues] = useState<DataInputs>(readOnly ? { reference: 'CASE-0042', confirmed: 'true', priority: 'urgent' } : {})
      return <><CaseDataFields fields={fields} values={values} onChange={setValues} readOnly={readOnly} view={view} /><output data-testid="typed">{JSON.stringify(values)}</output></>
    }
    const user = userEvent.setup()
    const { unmount } = render(<Form />)
    expect(screen.getByText('From the claim letter.')).toBeInTheDocument()
    expect(screen.getByText('USD')).toBeInTheDocument()
    expect(screen.getByLabelText('Notes').tagName).toBe('TEXTAREA')
    await user.type(screen.getByLabelText(/^Reference/), 'CASE-42')
    await user.click(screen.getByRole('checkbox', { name: /Confirmed/ }))
    await user.selectOptions(screen.getByLabelText('Priority'), 'urgent')
    expect(JSON.parse(screen.getByTestId('typed').textContent!)).toEqual({ reference: 'CASE-42', confirmed: 'true', priority: 'urgent' })
    unmount()

    // Read only: the values as text, choice labels from a pinned case view, sections as groups.
    const view: ViewDefinition = { schemaVersion: 1, kind: 'case', sections: [{ id: 'main', label: 'Claim details', columns: 2, fields: [
      { id: 'reference', binding: 'case.reference', type: 'text', label: 'Claim reference', required: true },
      { id: 'amount', binding: 'case.amount', type: 'number', label: 'Amount' },
      { id: 'notes', binding: 'case.notes', type: 'text', label: 'Notes' },
      { id: 'confirmed', binding: 'case.confirmed', type: 'boolean', label: 'Confirmed', required: true },
      { id: 'priority', binding: 'case.priority', type: 'choice', label: 'Priority', options: [{ value: 'normal', label: 'normal' }, { value: 'urgent', label: 'urgent' }] },
    ] }], actions: [{ id: 'save', type: 'saveData', label: 'Save' }] }
    render(<Form readOnly view={view} />)
    const group = within(screen.getByRole('group', { name: 'Claim details' }))
    expect(group.getByLabelText(/^Claim reference/)).toHaveValue('CASE-0042')
    expect(group.getByLabelText(/^Claim reference/)).toHaveAttribute('readonly')
    expect(group.getByLabelText(/^Amount/)).toHaveValue('No value')
    expect(group.getByLabelText(/^Confirmed/)).toHaveValue('Yes')
  })

  it('reports field problems with the request form messages before sending', () => {
    const fields: DataField[] = [
      { key: 'reference', label: 'Reference', type: 'text', required: true, minLength: 9, pattern: '^CASE-\\d{4}$', patternMessage: 'Enter a reference like CASE-1234.' },
      { key: 'confirmed', label: 'Confirmed', type: 'boolean', required: true },
      { key: 'due', label: 'Due', type: 'date', formatMaximum: '2026-12-31' },
      { key: 'amount', label: 'Amount', type: 'number', widget: 'money', multipleOf: 0.01 },
    ]
    expect(typedValues(fields, { reference: 'CASE-0042' })).toEqual({ reference: 'CASE-0042', confirmed: false })
    const failure = (inputs: DataInputs) => { try { typedValues(fields, inputs); return undefined } catch (cause) { return cause as CaseDataError } }
    expect(failure({ reference: 'CASE-42' })?.message).toBe('Reference: Use at least 9 characters.')
    expect(failure({ reference: 'CASE-00042' })?.fieldErrors).toEqual({ reference: 'Enter a reference like CASE-1234.' })
    expect(failure({ reference: 'CASE-0042', due: '2027-01-01' })?.fieldErrors.due).toMatch(/on or before/)
    expect(failure({ reference: 'CASE-0042', amount: '10.005' })?.fieldErrors.amount).toBe('Use at most two decimal places (cents).')
    expect(failure({})?.fieldErrors.reference).toBe('This field is required.')
  })

  it('the mock backend checks the same fields, reports legacy keys and keeps case data', async () => {
    seedDb()
    setCurrentUser('dana.designer')
    const sourceXml = (key: string) => `<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"><case id="${key}" name="Claim"/></definitions>`
    const dataFields: DataField[] = [
      { key: 'reference', label: 'Reference', type: 'text', required: true, pattern: '^CASE-\\d{4}$' },
      { key: 'Old_Key', label: 'Older key', type: 'boolean' },
    ]
    const created = await createCaseDefinition(WS_PURCHASING, { key: 'claim', name: 'Claim', sourceXml: sourceXml('claim'), dataFields })
    expect(created.warnings).toEqual([expect.objectContaining({ code: 'CASE_DATA_KEY_LEGACY', field: 'Old_Key' })])
    expect((await getCaseDefinition(WS_PURCHASING, created.id)).warnings).toHaveLength(1)
    await expect(createCaseDefinition(WS_PURCHASING, { key: 'claim', name: 'Claim', sourceXml: sourceXml('claim'), dataFields: [{ key: 'count', label: 'Count', type: 'integer', pattern: '^\\d+$' }] })).rejects.toThrow(/answer pattern/)
    const clean = await createCaseDefinition(WS_PURCHASING, { key: 'claim', name: 'Claim', sourceXml: sourceXml('claim'), dataFields: [dataFields[0]!] })
    expect(clean.warnings).toBeUndefined()

    setCurrentUser('rhea.requester')
    await expect(startCaseInstance(WS_PURCHASING, created.id, { reference: 'CASE-42' })).rejects.toThrow(/pattern/)
    const started = await startCaseInstance(WS_PURCHASING, created.id, { reference: 'CASE-0042', Old_Key: true })
    setCurrentUser('mani.manager')
    const data = await getCaseData(WS_PURCHASING, started.id)
    expect(data).toMatchObject({ values: { reference: 'CASE-0042', Old_Key: true }, canEdit: true })
    expect(data.fields[0]).toMatchObject({ pattern: '^CASE-\\d{4}$' })
    await expect(updateCaseData(WS_PURCHASING, started.id, 1, 'Fix', { reference: 'case-1' })).rejects.toThrow(/pattern/)
    const updated = await updateCaseData(WS_PURCHASING, started.id, 1, 'Corrected reference', { reference: 'CASE-0043' })
    expect(updated.version).toBe(2)
    expect((await getCaseData(WS_PURCHASING, started.id)).values).toEqual({ reference: 'CASE-0043' })
  })
})
