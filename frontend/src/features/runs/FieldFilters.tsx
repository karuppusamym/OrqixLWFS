import { X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { condition, operatorsFor, useSearchableFields, type SearchableField } from './bulkApi'

const MAX_CONDITIONS = 5

/** "Amount (USD) at least 500" for a stored condition. */
export function describeCondition(raw: string, fields: SearchableField[]): string {
  const [key = '', op = '', ...rest] = raw.split(':')
  const field = fields.find((f) => f.key === key)
  const label = operatorsFor(field?.kind ?? 'text').find((o) => o.value === op)?.label ?? op
  const value = rest.join(':')
  return `${field?.title ?? key} ${label} ${field?.kind === 'boolean' ? (value === 'true' ? 'yes' : 'no') : value}`
}

/**
 * Filters by the workflow's searchable request fields (CG-O04, docs/build/S142). Shown for people who may read request
 * data (run.read-data) once a workflow is chosen; conditions are kept in the URL as key:op:value.
 */
export function FieldFilters({ ws, workflowId, value, onChange }: { ws: string; workflowId: string; value: string[]; onChange: (next: string[]) => void }) {
  const fields = useSearchableFields(ws, workflowId)
  const items = fields.data?.items ?? []
  const [key, setKey] = useState('')
  const [op, setOp] = useState('eq')
  const [text, setText] = useState('')
  if (items.length === 0) return null
  const field = items.find((f) => f.key === key) ?? items[0]!
  const operators = operatorsFor(field.kind)
  const chosenOp = operators.some((o) => o.value === op) ? op : operators[0]!.value
  const add = (event: FormEvent) => {
    event.preventDefault()
    if (!text.trim()) return
    onChange([...value, condition(field.key, chosenOp, text)])
    setText('')
  }
  return (
    <div className="w-full space-y-2">
      <form className="flex flex-wrap items-end gap-2" aria-label="Filter by request fields" onSubmit={add}>
        <div className="space-y-1.5">
          <Label htmlFor="runs-field">Request field</Label>
          <Select id="runs-field" value={field.key} onChange={(e) => { setKey(e.target.value); setText('') }} className="w-48">
            {items.map((f) => <option key={f.key} value={f.key}>{f.title}</option>)}
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="runs-field-op">Condition</Label>
          <Select id="runs-field-op" value={chosenOp} onChange={(e) => setOp(e.target.value)} className="w-36">
            {operators.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="runs-field-value">Value</Label>
          {field.kind === 'choice' || field.kind === 'boolean' ? (
            <Select id="runs-field-value" value={text} onChange={(e) => setText(e.target.value)} className="w-44">
              <option value="">Choose</option>
              {(field.kind === 'boolean' ? ['true', 'false'] : field.options).map((o) => (
                <option key={o} value={o}>{field.kind === 'boolean' ? (o === 'true' ? 'Yes' : 'No') : o}</option>
              ))}
            </Select>
          ) : (
            <Input id="runs-field-value" value={text} onChange={(e) => setText(e.target.value)} className="w-44" maxLength={200}
              type={field.kind === 'date' ? 'date' : field.kind === 'text' ? 'text' : 'number'} />
          )}
        </div>
        <Button type="submit" variant="secondary" disabled={value.length >= MAX_CONDITIONS || !text.trim()}>Add condition</Button>
      </form>
      {value.length ? (
        <ul className="flex flex-wrap gap-2" aria-label="Field conditions">
          {value.map((c, i) => (
            <li key={`${c}-${i}`} className="flex items-center gap-1 rounded-full border border-border px-3 py-0.5 text-sm">
              {describeCondition(c, items)}
              <button type="button" className="rounded-full p-0.5 hover:bg-neutral-soft" aria-label={`Remove ${describeCondition(c, items)}`} onClick={() => onChange(value.filter((_, j) => j !== i))}>
                <X className="size-3" aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
