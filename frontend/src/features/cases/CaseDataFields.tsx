import type { ViewDefinition } from '@/features/views/model'
import { useId } from 'react'
import type { components } from '@/api/schema'
import { Input, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'

export type DataField = components['schemas']['CaseDataField']
export type DataValues = components['schemas']['CaseDataValues']
export type DataInputs = Record<string, string>

export function inputValues(values: DataValues): DataInputs {
  return Object.fromEntries(Object.entries(values).map(([key, value]) => [key, String(value)]))
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

export function typedValues(fields: DataField[], inputs: DataInputs): DataValues {
  const result: DataValues = {}
  for (const field of fields) {
    const value = inputs[field.key] ?? ''
    if (value === '') continue
    if (field.type === 'number' || field.type === 'integer') {
      const number = Number(value)
      if (!Number.isFinite(number) || (field.type === 'integer' && !Number.isInteger(number))) throw new Error(`${field.label} needs a valid ${field.type === 'integer' ? 'whole ' : ''}number.`)
      if (decimalIdentity(value) !== decimalIdentity(String(number))) throw new Error(`${field.label} has more precision than can be saved. Use fewer digits.`)
      result[field.key] = number
    } else if (field.type === 'boolean') result[field.key] = value === 'true'
    else result[field.key] = value
  }
  return result
}

export function CaseDataFields({ fields, values, onChange, disabled = false, readOnly = false, view }: { view?: ViewDefinition; fields: DataField[]; values: DataInputs; onChange: (values: DataInputs) => void; disabled?: boolean; readOnly?: boolean }) {
  const prefix = useId()
  const groups = view ? view.sections.map(section => ({ id: section.id, label: section.label, columns: section.columns, fields: section.fields.flatMap(control => {
    const field = fields.find(item => `case.${item.key}` === control.binding)
    return field ? [{ ...field, label: control.label }] : []
  }) })) : [{ id: 'default', label: '', columns: 2, fields }]
  return <div className="space-y-5">{groups.map(group => <section key={group.id} aria-label={group.label || undefined} className="space-y-3">
    {group.label ? <h3 className="font-semibold">{group.label}</h3> : null}
    <div className={`grid gap-4 ${group.columns === 2 ? 'sm:grid-cols-2' : ''}`}>{group.fields.map(field => {
    const id = `${prefix}-${field.key}`
    const set = (value: string) => onChange({ ...values, [field.key]: value })
    return <div key={field.key} className="space-y-1">
      <Label htmlFor={id}>{field.label}{field.required ? ' (required)' : ''}</Label>
      {readOnly ? <Input id={id} readOnly value={values[field.key] ?? 'No value'} />
        : field.type === 'choice' || field.type === 'boolean' ? <select id={id} className="h-10 w-full rounded border bg-surface px-3" value={values[field.key] ?? ''} disabled={disabled} required={field.required} onChange={event => set(event.target.value)}>
          <option value="">Choose a value</option>{field.type === 'boolean' ? <><option value="true">Yes</option><option value="false">No</option></> : field.options?.map(option => <option key={option} value={option}>{option}</option>)}
        </select> : <Input id={id} value={values[field.key] ?? ''} onChange={event => set(event.target.value)} disabled={disabled} required={field.required}
          type={field.type === 'date' ? 'date' : field.type === 'number' || field.type === 'integer' ? 'number' : 'text'}
          step={field.type === 'integer' ? 1 : 'any'} min={field.minimum ?? undefined} max={field.maximum ?? undefined} maxLength={field.type === 'text' ? field.maxLength ?? 2000 : undefined} />}
    </div>
  })}</div></section>)}</div>
}

export function CaseDataFieldsEditor({ fields, onChange, disabled }: { fields: DataField[]; onChange: (fields: DataField[]) => void; disabled: boolean }) {
  const prefix = useId()
  const update = (index: number, patch: Partial<DataField>) => onChange(fields.map((field, current) => current === index ? { ...field, ...patch } : field))
  return <fieldset disabled={disabled} className="space-y-3">
    <legend className="font-medium">Case data fields</legend>
    <p className="text-sm text-muted-foreground">Define the information collected when a case starts. These fields are pinned to this definition version.</p>
    {fields.map((field, index) => <div key={index} className="space-y-2 rounded border p-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <div><Label htmlFor={`${prefix}-key-${index}`}>Field {index + 1} key</Label><Input id={`${prefix}-key-${index}`} required pattern="[A-Za-z][A-Za-z0-9_]{0,63}" value={field.key} onChange={event => update(index, { key: event.target.value })} /></div>
        <div><Label htmlFor={`${prefix}-label-${index}`}>Field {index + 1} label</Label><Input id={`${prefix}-label-${index}`} required maxLength={120} value={field.label} onChange={event => update(index, { label: event.target.value })} /></div>
        <div><Label htmlFor={`${prefix}-type-${index}`}>Field {index + 1} type</Label><select id={`${prefix}-type-${index}`} className="h-10 w-full rounded border bg-surface px-3" value={field.type} onChange={event => update(index, { type: event.target.value as DataField['type'], options: undefined, minimum: undefined, maximum: undefined, maxLength: undefined })}>
          <option value="text">Text</option><option value="number">Number</option><option value="integer">Whole number</option><option value="boolean">Yes or no</option><option value="date">Date</option><option value="choice">Choice</option>
        </select></div>
        <label className="flex items-center gap-2"><input type="checkbox" checked={field.required ?? false} onChange={event => update(index, { required: event.target.checked })} />Required field {index + 1}</label>
      </div>
      {field.type === 'choice' ? <div><Label htmlFor={`${prefix}-options-${index}`}>Choices, one per line</Label><Textarea id={`${prefix}-options-${index}`} required value={field.options?.join('\n') ?? ''} onChange={event => update(index, { options: event.target.value.split('\n') })} /></div> : null}
      <Button type="button" variant="secondary" onClick={() => onChange(fields.filter((_, current) => current !== index))}>Remove field {index + 1}</Button>
    </div>)}
    <Button type="button" variant="secondary" disabled={fields.length >= 30} onClick={() => { let index = 1; while (fields.some(field => field.key === `field${index}`)) index++; onChange([...fields, { key: `field${index}`, label: 'New field', type: 'text', required: false }]) }}>Add case data field</Button>
  </fieldset>
}
