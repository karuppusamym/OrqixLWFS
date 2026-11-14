import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { components } from '@/api/schema'
import { useOptionalWorkspace } from '@/app/workspace'
import { Button } from '@/components/ui/button'
import { Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { type ExprType, check, describeType } from '@/lib/expr'
import { describe } from '@/lib/expr/describe'
import { refToExpression, type StudioScope } from '../model/expressionScope'

/** Formulas the guided editor offers, with a short business explanation (docs/build/S137). */
export const FUNCTION_TEMPLATES: Array<{ group: string; label: string; text: string }> = [
  { group: 'Numbers', label: 'Round to 2 decimal places', text: 'round(x, 2)' },
  { group: 'Numbers', label: 'Round half to even (banker’s)', text: 'round(x, 2, "halfEven")' },
  { group: 'Numbers', label: 'Use a fallback when missing', text: 'coalesce(x, 0)' },
  { group: 'Lists', label: 'Total of a column', text: 'sum(list, item.column)' },
  { group: 'Lists', label: 'Number of rows that match', text: 'count(list, item.column > 0)' },
  { group: 'Lists', label: 'Any row matches', text: 'any(list, item.column = "value")' },
  { group: 'Lists', label: 'Largest value', text: 'max(list, item.column)' },
  { group: 'Dates', label: 'Today', text: 'today()' },
  { group: 'Dates', label: 'Days later', text: 'addDays(date, 30)' },
  { group: 'Dates', label: 'Working days later', text: 'addWorkingDays(date, 5)' },
  { group: 'Dates', label: 'Days between two dates', text: 'daysBetween(from, to)' },
  { group: 'Text', label: 'Capital letters', text: 'upper(text)' },
  { group: 'Text', label: 'Contains', text: 'contains(text, "word")' },
  { group: 'Choices', label: 'If … then … otherwise', text: 'if condition then a else b' },
  { group: 'Choices', label: 'One of several values', text: 'x in ["A", "B"]' },
]

/** The types of the fields the expression uses, for the server's own check (the expression type notation). */
type ApiType = components['schemas']['ExpressionType']

/** The API notation has no null type tag: a value of unknown type is any. */
function apiType(t: ExprType): ApiType {
  if (typeof t === 'string') return t === 'null' ? 'any' : t
  if ('list' in t) return { list: apiType(t.list) }
  return { row: Object.fromEntries(Object.entries(t.row).map(([k, v]) => [k, apiType(v)])) }
}

function pinned(context: StudioScope, source: string): { input: Record<string, ApiType>; steps: Record<string, Record<string, ApiType>> } {
  const result = check(source, context.scope)
  const input: Record<string, ApiType> = {}
  const steps: Record<string, Record<string, ApiType>> = {}
  if (result.ok) for (const ref of result.expression.fieldRefs) {
    const [, root, a, b] = ref.split('/')
    if (root === 'input' && a) { const t = context.scope.input(a); if (t) input[a] = apiType(t) }
    if (root === 'steps' && a && b) { const t = context.scope.step(a, b); if (t) (steps[a] ??= {})[b] = apiType(t) }
  }
  return { input, steps }
}

export interface ExpressionEditorProps {
  id: string
  label: string
  value: string
  onCommit: (value: string) => void
  context: StudioScope
  /** The type the expression must give (true or false for a condition), if any. */
  expected?: ExprType
  disabled?: boolean
  dataField?: string
  description?: string
}

/**
 * Guided editor for an Orqix expression (CG-X01, docs/build/S137): pick fields and formulas instead of typing them,
 * see at once what is wrong and where, read the formula in plain words, and let the server's own engine confirm it.
 * Never raw code: the language has no loops, calls or I/O.
 */
export function ExpressionEditor({ id, label, value, onCommit, context, expected, disabled, dataField, description }: ExpressionEditorProps) {
  const ws = useOptionalWorkspace()
  const [draft, setDraft] = useState(value)
  const [seen, setSeen] = useState(value)
  const [server, setServer] = useState<string | null>(null)
  if (seen !== value) {
    setSeen(value)
    setDraft(value)
  }
  const result = draft.trim() === '' ? null : check(draft, context.scope)
  const wrongType = result?.ok && expected && result.expression.type !== expected && result.expression.type !== 'null' && result.expression.type !== 'any'
  const reading = result?.ok ? describe(draft, context.labels) : null
  const commit = (text = draft) => {
    if (text !== value) onCommit(text)
  }
  const insert = (text: string) => {
    const next = draft.trim() === '' || draft === '0' ? text : `${draft} ${text}`
    setDraft(next)
    commit(next)
  }
  const verify = async () => {
    setServer(null)
    try {
      const answer = await endpoints.evaluateExpression(ws!.workspace.id, { expression: draft, types: pinned(context, draft) })
      setServer(answer.valid ? `The server's engine agrees: it gives ${answer.typeDescription ?? 'a value'}.` : `The server's engine says: ${answer.problem?.message ?? 'not valid'}`)
    } catch {
      setServer('The server could not check this expression now.')
    }
  }
  const statusId = `${id}-status`
  const groups = [...new Set(FUNCTION_TEMPLATES.map(f => f.group))]
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{label}</Label>
      {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
      <Textarea
        id={id}
        data-field={dataField}
        value={draft}
        rows={2}
        spellCheck={false}
        maxLength={2000}
        disabled={disabled}
        className="font-mono text-sm"
        aria-describedby={statusId}
        aria-invalid={result && (!result.ok || wrongType) ? true : undefined}
        onChange={e => setDraft(e.target.value)}
        onBlur={() => commit()}
      />
      {!disabled ? (
        <div className="flex flex-wrap gap-2">
          <Select aria-label={`Insert a field into ${label}`} value="" onChange={e => { if (e.target.value) insert(refToExpression(e.target.value)) }}>
            <option value="">Insert a field…</option>
            {context.fields.map(f => <option key={f.ref} value={f.ref}>{f.group}: {f.label}</option>)}
          </Select>
          <Select aria-label={`Insert a formula into ${label}`} value="" onChange={e => { if (e.target.value) insert(e.target.value) }}>
            <option value="">Insert a formula…</option>
            {groups.map(g => (
              <optgroup key={g} label={g}>
                {FUNCTION_TEMPLATES.filter(f => f.group === g).map(f => <option key={f.label} value={f.text}>{f.label}</option>)}
              </optgroup>
            ))}
          </Select>
          {ws?.can('workflow.edit') && result?.ok ? <Button type="button" variant="secondary" size="sm" onClick={verify}>Check on the server</Button> : null}
        </div>
      ) : null}
      <div id={statusId} role="status" aria-live="polite" className="space-y-1 text-sm">
        {result === null ? <p className="text-muted-foreground">Write a formula, or insert a field or a formula.</p> : null}
        {result && !result.ok ? (
          <p className="text-danger">
            {result.error.message} <span className="text-muted-foreground">(at character {result.error.position + 1}: “{draft.slice(result.error.position, result.error.position + 12)}”)</span>
          </p>
        ) : null}
        {result?.ok && wrongType ? <p className="text-danger">This must give {describeType(expected!)}, but it gives {describeType(result.expression.type)}.</p> : null}
        {result?.ok && !wrongType ? <p>Gives {describeType(result.expression.type)}.{reading ? <> Reads as: <span className="italic">{reading}</span>.</> : null}</p> : null}
        {server ? <p className="text-muted-foreground">{server}</p> : null}
      </div>
    </div>
  )
}
