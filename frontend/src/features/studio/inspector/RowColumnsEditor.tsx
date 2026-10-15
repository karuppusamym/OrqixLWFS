import { useId, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { FieldSchemaDoc } from '../model/types'
import { CommitInput } from './controls'

export function RowColumnsEditor({ schema, onChange, disabled }: { schema: FieldSchemaDoc; onChange: (next: FieldSchemaDoc) => void; disabled: boolean }) {
  const prefix = useId()
  const [error, setError] = useState<string>()
  const properties = schema.properties ?? {}
  const update = (key: string, patch: Partial<FieldSchemaDoc>) => onChange({ ...schema, properties: { ...properties, [key]: { ...properties[key]!, ...patch } } })
  return <fieldset className="space-y-3"><legend className="font-semibold">Row columns ({Object.keys(properties).length} of 10)</legend>
    <p className="text-xs text-muted-foreground">Rows contain named scalar values. Files, nested objects and nested collections are unsupported.</p>
    {Object.entries(properties).map(([key, column]) => <div key={key} className="space-y-2 rounded border border-border p-3">
      <Label htmlFor={`${prefix}-row-key-${key}`}>Column key</Label>
      <CommitInput id={`${prefix}-row-key-${key}`} value={key} disabled={disabled} onCommit={next => {
        if (!/^[a-z][A-Za-z0-9]{0,63}$/.test(next) || (next !== key && next in properties)) { setError('Use a unique column key starting with a lowercase letter, followed by letters and digits.'); return }
        setError(undefined)
        onChange({ ...schema, properties: Object.fromEntries(Object.entries(properties).map(([old, value]) => [old === key ? next : old, value])), required: (schema.required ?? []).map(old => old === key ? next : old) })
      }} />
      <Label htmlFor={`${prefix}-row-title-${key}`}>Column label</Label><CommitInput id={`${prefix}-row-title-${key}`} value={column.title} disabled={disabled} onCommit={title => update(key, { title })} />
      <Label htmlFor={`${prefix}-row-type-${key}`}>Column type</Label><Select id={`${prefix}-row-type-${key}`} value={column.type} disabled={disabled} onChange={event => {
        const type = event.target.value as 'string' | 'number' | 'integer' | 'boolean'
        onChange({ ...schema, properties: { ...properties, [key]: { type, title: column.title, ...(type === 'string' ? { minLength: 1, maxLength: 200 } : {}) } } })
      }}><option value="string">Text</option><option value="number">Number</option><option value="integer">Whole number</option><option value="boolean">Yes/No</option></Select>
      <div className="flex items-center gap-2"><Checkbox id={`${prefix}-row-required-${key}`} disabled={disabled} checked={(schema.required ?? []).includes(key)} onCheckedChange={checked => onChange({ ...schema, required: checked ? [...new Set([...(schema.required ?? []), key])] : (schema.required ?? []).filter(old => old !== key) })} /><Label htmlFor={`${prefix}-row-required-${key}`}>Required column {column.title}</Label></div>
      <Button variant="ghost" size="sm" disabled={disabled} onClick={() => onChange({ ...schema, properties: Object.fromEntries(Object.entries(properties).filter(([old]) => old !== key)), required: (schema.required ?? []).filter(old => old !== key) })}>Remove column {column.title}</Button>
    </div>)}
    {error ? <p role="alert" className="text-sm text-danger">{error}</p> : null}
    <Button variant="secondary" disabled={disabled || Object.keys(properties).length >= 10} onClick={() => {
      let n = Object.keys(properties).length + 1
      while (`column${n}` in properties) n++
      onChange({ ...schema, additionalProperties: false, properties: { ...properties, [`column${n}`]: { type: 'string', title: `Column ${n}`, minLength: 1, maxLength: 200 } } })
    }}>Add row column</Button>
  </fieldset>
}
