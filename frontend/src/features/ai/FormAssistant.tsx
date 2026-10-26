import { Sparkles } from 'lucide-react'
import { useId, useState } from 'react'
import { useInWorkspace } from '@/app/workspace'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '@/features/studio/StudioContext'
import { sameJson } from '@/features/studio/model/ops'
import type { Definition, FieldSchemaDoc } from '@/features/studio/model/types'
import { suggestFormChanges, useAiEnabled, type AiFormChange, type AiFormSuggestion } from './api'

const MIN = 10
const MAX = 2000

/** What applying the chosen changes did: applied keys and skipped ones (the form changed since the suggestion). */
export interface ApplyOutcome {
  def: Definition
  applied: string[]
  skipped: string[]
}

/**
 * Applies reviewed changes to the draft (one undoable edit). A change applies only to the form it was suggested for:
 * an added key must still be free, and an updated or removed question must still be exactly as shown in the review.
 */
export function applyFormChanges(def: Definition, changes: AiFormChange[]): ApplyOutcome {
  const next = structuredClone(def)
  const applied: string[] = [], skipped: string[] = []
  const properties = next.inputSchema.properties
  let required = [...(next.inputSchema.required ?? [])]
  let order = [...(next.inputUi?.order ?? Object.keys(properties))]
  for (const change of changes) {
    const exists = change.key in properties
    const current = properties[change.key]
    const fresh = change.op === 'add' ? !exists : exists && sameJson(current, change.before)
    if (!fresh) { skipped.push(change.key); continue }
    if (change.op === 'remove') {
      delete properties[change.key]
      required = required.filter(k => k !== change.key)
      order = order.filter(k => k !== change.key)
    } else {
      properties[change.key] = structuredClone(change.field) as unknown as FieldSchemaDoc
      required = required.filter(k => k !== change.key)
      if (change.required) required.push(change.key)
      if (!order.includes(change.key)) order.push(change.key)
      if (change.op === 'update' && next.inputUi?.widgets?.[change.key]) delete next.inputUi.widgets[change.key]
    }
    applied.push(change.key)
  }
  next.inputSchema.required = required
  next.inputUi = { ...next.inputUi, order: order.filter(k => k in properties) }
  for (const name of ['visibleWhen', 'requiredWhen'] as const) if (next.inputUi[name]) next.inputUi[name] = Object.fromEntries(Object.entries(next.inputUi[name]!).filter(([key]) => key in properties))
  if (next.inputUi.layout?.sections) next.inputUi.layout.sections = next.inputUi.layout.sections.map(s => ({ ...s, fields: s.fields.filter(key => key in properties) }))
  return { def: applied.length > 0 ? next : def, applied, skipped }
}

const OP_LABEL: Record<AiFormChange['op'], string> = { add: 'Add', update: 'Change', remove: 'Remove' }

/** Plain-language summary of one question schema, for the review list. */
function describeField(field: Record<string, unknown> | undefined): string[] {
  if (!field) return []
  const lines: string[] = []
  const type = field.type === 'string' ? (field.format === 'date' ? 'date' : field.format === 'attachment' ? 'file' : Array.isArray(field.enum) ? 'choice' : 'text') : String(field.type)
  lines.push(`Type: ${type}`)
  if (typeof field.description === 'string') lines.push(`Help: ${field.description}`)
  if (Array.isArray(field.enum)) lines.push(`Choices: ${field.enum.join(', ')}`)
  for (const [key, label] of [['minLength', 'Shortest'], ['maxLength', 'Longest'], ['minimum', 'Minimum'], ['maximum', 'Maximum'], ['formatMinimum', 'Earliest'], ['formatMaximum', 'Latest'], ['pattern', 'Pattern'], ['patternMessage', 'Pattern message']] as const) {
    if (field[key] !== undefined) lines.push(`${label}: ${String(field[key])}`)
  }
  return lines
}

function ChangeView({ change, checked, onCheck }: { change: AiFormChange; checked: boolean; onCheck: (value: boolean) => void }) {
  const id = useId()
  const after = describeField(change.field as Record<string, unknown> | undefined)
  const before = describeField(change.before as Record<string, unknown> | undefined)
  const title = String((change.field as { title?: unknown } | undefined)?.title ?? (change.before as { title?: unknown } | undefined)?.title ?? change.key)
  const removed = before.filter(line => !after.includes(line))
  const added = change.op === 'remove' ? [] : after.filter(line => !before.includes(line))
  const requiredLine = change.op === 'remove' ? null : change.wasRequired === undefined ? (change.required ? 'Required' : 'Optional')
    : change.wasRequired !== change.required ? `${change.wasRequired ? 'Required' : 'Optional'} → ${change.required ? 'required' : 'optional'}` : null
  return (
    <li className="space-y-2 rounded-card border border-border bg-surface p-3" data-testid={`ai-form-change-${change.key}`}>
      <div className="flex items-start gap-2">
        <Checkbox id={id} checked={checked} onCheckedChange={v => onCheck(v === true)} aria-describedby={`${id}-reason`} />
        <Label htmlFor={id} className="font-medium">
          {OP_LABEL[change.op]} “{title}” <span className="font-mono text-xs text-muted-foreground">({change.key})</span>
        </Label>
      </div>
      <p id={`${id}-reason`} className="text-sm text-muted-foreground">{change.reason}</p>
      {removed.length > 0 || added.length > 0 || requiredLine ? (
        <ul className="space-y-0.5 font-mono text-xs" aria-label={`Differences for ${title}`}>
          {removed.map(line => <li key={`-${line}`} className="text-danger"><span aria-hidden="true">− </span><span className="sr-only">Removed: </span>{line}</li>)}
          {added.map(line => <li key={`+${line}`} className="text-success-foreground"><span aria-hidden="true">+ </span><span className="sr-only">Added: </span>{line}</li>)}
          {requiredLine ? <li>{requiredLine}</li> : null}
        </ul>
      ) : null}
    </li>
  )
}

/**
 * AI assistant of the request-form editor (docs/build/S90, G4). Hidden unless AI is enabled. The server validates
 * every suggestion against the form rules; this panel only shows them as a reviewable difference, and nothing changes
 * until the designer ticks changes and presses Apply.
 */
export function FormAssistant() {
  // The request form editor is also rendered outside a workspace route (tests, embedded authoring): no AI there.
  return useInWorkspace() ? <FormAssistantPanel /> : null
}

function FormAssistantPanel() {
  const enabled = useAiEnabled()
  const { store, readOnly, wsId, workflowId } = useStudioContext()
  const form = useStudio(s => s.definition.inputSchema)
  const [open, setOpen] = useState(false)
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [result, setResult] = useState<AiFormSuggestion | null>(null)
  const [chosen, setChosen] = useState<Record<string, boolean>>({})
  const [status, setStatus] = useState('')
  const descriptionId = useId()
  if (!enabled || readOnly) return null

  const length = description.trim().length
  const suggest = async () => {
    setBusy(true); setError(null); setResult(null); setChosen({}); setStatus('')
    try {
      const suggestion = await suggestFormChanges(wsId, workflowId, { description: description.trim(), inputSchema: form as unknown as Record<string, unknown> })
      setResult(suggestion)
      setStatus(suggestion.changes.length === 0 ? 'No changes to review.' : `${suggestion.changes.length} suggested change${suggestion.changes.length === 1 ? '' : 's'} to review. Nothing has been applied.`)
    } catch (cause) {
      setError(cause)
    } finally {
      setBusy(false)
    }
  }
  const selected = result?.changes.filter(c => chosen[c.key]) ?? []
  const apply = () => {
    let outcome: ApplyOutcome | null = null
    store.getState().apply(d => { outcome = applyFormChanges(d, selected); return outcome.def })
    const done = outcome as ApplyOutcome | null
    if (!done) return
    setStatus(`Applied ${done.applied.length} change${done.applied.length === 1 ? '' : 's'}.${done.skipped.length > 0 ? ` Skipped ${done.skipped.join(', ')}: the form changed since the suggestion.` : ''} Use Undo to revert.`)
    setResult(null); setChosen({})
  }

  if (!open) {
    return (
      <Button variant="secondary" onClick={() => setOpen(true)}>
        <Sparkles aria-hidden="true" /> Suggest questions with AI
      </Button>
    )
  }
  return (
    <section aria-labelledby={`${descriptionId}-title`} className="space-y-3 rounded-card border border-border bg-surface-muted p-3" data-testid="ai-form-assistant">
      <h4 id={`${descriptionId}-title`} className="text-sm font-semibold">Suggest questions with AI</h4>
      <div className="space-y-1.5">
        <Label htmlFor={descriptionId}>Describe what the form should ask</Label>
        <Textarea id={descriptionId} value={description} rows={3} maxLength={MAX} aria-describedby={`${descriptionId}-help`} onChange={e => setDescription(e.target.value)} />
        <p id={`${descriptionId}-help`} className="text-xs text-muted-foreground">
          For example: “Add a cost code like AB-1234 and a date when it is needed.” Suggestions are checked against the form rules and are only applied when you choose them.
        </p>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button onClick={() => void suggest()} disabled={busy || length < MIN || length > MAX}>{busy ? 'Suggesting…' : 'Suggest changes'}</Button>
        <Button variant="ghost" onClick={() => { setOpen(false); setResult(null); setError(null) }}>Close</Button>
      </div>
      <p role="status" className="text-sm" aria-live="polite">{busy ? 'Asking the AI for suggestions…' : status}</p>
      {error ? <ProblemAlert error={error} /> : null}
      {result ? (
        <div className="space-y-3">
          {result.notAForm ? <Alert tone="warning"><p>{result.notAForm}</p></Alert> : null}
          {result.changes.length > 0 ? (
            <>
              <ul className="space-y-2" aria-label="Suggested changes">
                {result.changes.map(change => <ChangeView key={change.key} change={change} checked={!!chosen[change.key]} onCheck={v => setChosen(c => ({ ...c, [change.key]: v }))} />)}
              </ul>
              <Button onClick={apply} disabled={selected.length === 0}>Apply {selected.length} selected change{selected.length === 1 ? '' : 's'}</Button>
            </>
          ) : null}
          {result.rejected.length > 0 ? (
            <div>
              <h5 className="text-sm font-semibold">Not offered: they failed the form rules</h5>
              <ul className="list-disc pl-5 text-sm" aria-label="Rejected suggestions">
                {result.rejected.map((r, i) => <li key={`${r.key}-${i}`}><span className="font-mono">{r.key || '(no key)'}</span>: {r.reasons.join(' ')}</li>)}
              </ul>
            </div>
          ) : null}
          {result.notes.length > 0 ? <ul className="list-disc pl-5 text-xs text-muted-foreground" aria-label="AI notes">{result.notes.map((n, i) => <li key={i}>{n}</li>)}</ul> : null}
        </div>
      ) : null}
    </section>
  )
}
