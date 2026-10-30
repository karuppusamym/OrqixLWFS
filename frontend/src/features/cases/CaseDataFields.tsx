import type { ViewDefinition } from '@/features/views/model'
import { useMemo } from 'react'
import type { components } from '@/api/schema'
import { FormRenderer } from '@/components/form/FormRenderer'
import { FieldListEditor } from '@/features/studio/inspector/FieldListEditor'
import type { FormFieldModel } from '@/features/studio/model/ops'
import { validateJsonInput } from '@/lib/form/validate'
import { messageFor, type RawValues } from '@/lib/form/values'
import { CASE_FIELD_KINDS, CASE_KEY_PATTERN, MAX_CASE_FIELDS, caseField, caseFieldDefs, caseFormSchema, fieldModel, legacyKeyWarning } from './caseFields'

export type DataField = components['schemas']['CaseDataField']
export type DataValues = components['schemas']['CaseDataValues']
export type DataInputs = Record<string, string>

export function inputValues(values: DataValues): DataInputs {
  return Object.fromEntries(Object.entries(values).map(([key, value]) => [key, String(value)]))
}

/** Case data that does not pass the field checks; `fieldErrors` holds one plain message per field key. */
export class CaseDataError extends Error {
  readonly fieldErrors: Record<string, string>
  constructor(message: string, fieldErrors: Record<string, string>) { super(message); this.fieldErrors = fieldErrors }
}

// Compare the decimal the person typed with the number that JSON would actually send.
function decimalIdentity(value: string): string | null {
  if (value.length > 128) return null
  const match = /^([+-]?)(\d*)(?:\.(\d*))?(?:e([+-]?\d{1,3}))?$/i.exec(value)
  if (!match || !(match[2] || match[3])) return null
  let digits = `${match[2]}${match[3] ?? ''}`.replace(/^0+/, '')
  if (!digits) return '0'
  let exponent = Number(match[4] ?? 0) - (match[3]?.length ?? 0)
  const trimmed = digits.replace(/0+$/, '')
  exponent += digits.length - trimmed.length; digits = trimmed
  return `${match[1] === '-' ? '-' : ''}${digits}e${exponent}`
}

/**
 * Typed case data from what was typed. Numbers keep their exact decimal through JSON; the values are then checked with
 * the request form's validator and messages (docs/build/S125), so a pattern, length, date or step problem is reported
 * before anything is sent. A required yes-or-no field is a checkbox, so an untouched one means No.
 */
export function typedValues(fields: DataField[], inputs: DataInputs): DataValues {
  const result: DataValues = {}
  for (const field of fields) {
    const value = inputs[field.key] ?? ''
    if (value === '') {
      if (field.type === 'boolean' && field.required) result[field.key] = false
      continue
    }
    if (field.type === 'number' || field.type === 'integer') {
      const number = Number(value)
      if (!Number.isFinite(number) || (field.type === 'integer' && !Number.isInteger(number))) throw new CaseDataError(`${field.label} needs a valid ${field.type === 'integer' ? 'whole ' : ''}number.`, { [field.key]: `Enter a valid ${field.type === 'integer' ? 'whole ' : ''}number.` })
      if (decimalIdentity(value) !== decimalIdentity(String(number))) throw new CaseDataError(`${field.label} has more precision than can be saved. Use fewer digits.`, { [field.key]: 'This has more precision than can be saved. Use fewer digits.' })
      result[field.key] = number
    } else if (field.type === 'boolean') result[field.key] = value === 'true'
    else result[field.key] = value
  }
  const { schema } = caseFormSchema(fields)
  const defs = caseFieldDefs(fields)
  const errors: Record<string, string> = {}
  for (const issue of validateJsonInput(schema, result)) {
    const key = issue.pointer.split('/').filter(Boolean)[0]
    const def = defs.find(item => item.key === key)
    if (!key || !def || key in errors) continue
    errors[key] = messageFor(def, issue.keyword, [...(inputs[key] ?? '')].length)
  }
  const first = Object.keys(errors)[0]
  if (first) throw new CaseDataError(`${defs.find(def => def.key === first)?.label ?? first}: ${errors[first]}`, errors)
  return result
}

/** Typed text to the renderer's values: yes-or-no as a checkbox; an unset one stays unset, so read-only shows "No value". */
function rawValues(fields: DataField[], inputs: DataInputs): RawValues {
  const raw: RawValues = {}
  for (const field of fields) {
    const value = inputs[field.key]
    if (value === undefined) continue
    raw[field.key] = field.type === 'boolean' ? (value === '' ? '' : value === 'true') : value
  }
  return raw
}

/** Case data in the request form renderer (docs/build/S125); a pinned case view supplies labels and sections. */
export function CaseDataFields({ fields, values, onChange, disabled = false, readOnly = false, view, errors }: { view?: ViewDefinition; fields: DataField[]; values: DataInputs; onChange: (values: DataInputs) => void; disabled?: boolean; readOnly?: boolean; errors?: Record<string, string> }) {
  const defs = useMemo(() => caseFieldDefs(fields, view), [fields, view])
  return <FormRenderer view={view} fields={defs} values={rawValues(fields, values)} errors={errors ?? {}} idPrefix="case-data" disabled={disabled} readOnly={readOnly}
    onChange={(key, value) => onChange({ ...values, [key]: typeof value === 'boolean' ? String(value) : typeof value === 'string' ? value : '' })} />
}

const KEY_RULE = 'Start with a lowercase letter; use letters and digits only.'

/** Case data fields in the shared field editor (docs/build/S125): the same rows, types and checks as request questions. */
export function CaseDataFieldsEditor({ fields, onChange, disabled }: { fields: DataField[]; onChange: (fields: DataField[]) => void; disabled: boolean }) {
  const models = useMemo(() => fields.map(fieldModel), [fields])
  const update = (key: string, patch: Partial<FormFieldModel>) => onChange(fields.map(field => field.key === key ? caseField({ ...fieldModel(field), ...patch }) : field))
  const move = (key: string, delta: -1 | 1) => {
    const index = fields.findIndex(field => field.key === key), target = index + delta
    if (index < 0 || target < 0 || target >= fields.length) return
    const next = [...fields]
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item!)
    onChange(next)
  }
  const add = () => {
    if (fields.length >= MAX_CASE_FIELDS) return undefined
    let index = 1
    while (fields.some(field => field.key === `field${index}`)) index++
    const key = `field${index}`
    onChange([...fields, { key, label: 'New field', type: 'text', required: false }])
    return key
  }
  const rename = (key: string, next: string) => {
    if (!CASE_KEY_PATTERN.test(next)) return KEY_RULE
    if (fields.some(field => field.key === next)) return `A field with the key "${next}" already exists.`
    onChange(fields.map(field => field.key === key ? { ...field, key: next } : field))
    return undefined
  }
  return <fieldset disabled={disabled} className="space-y-3">
    <legend className="font-medium">Case data fields</legend>
    <p className="text-sm text-muted-foreground">Define the information collected when a case starts. These fields are pinned to this definition version. They are defined like request questions: the same types, help text, lengths, answer patterns and date limits.</p>
    <FieldListEditor
      fields={models}
      readOnly={false}
      kinds={CASE_FIELD_KINDS}
      idPrefix="case-"
      noun={{ one: 'field', title: 'Field' }}
      listLabel="Case data fields"
      emptyText="No case data fields yet."
      addLabel="Add case data field"
      max={MAX_CASE_FIELDS}
      keyDescription={`Used by case views, task mappings and entry rules. ${KEY_RULE}`}
      keyWarning={legacyKeyWarning}
      onUpdate={update}
      onMove={move}
      onDelete={key => onChange(fields.filter(field => field.key !== key))}
      onAdd={add}
      onRename={rename}
    />
  </fieldset>
}
