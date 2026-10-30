import { ArrowDown, ArrowUp, ChevronDown, ChevronRight, Plus, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { usagesOfRef } from '../model/fieldRefs'
import { questionRules, type QuestionRules } from '../model/formRules'
import { ALL_FILE_TYPES, FILE_TYPES } from '@/api/attachments'
import { addField, applyFields, fieldsFromDefinition, FIELD_KIND_LABELS, renameFieldKey, type FieldKind, type FormFieldModel } from '../model/ops'
import { Advanced, CommitInput, CommitNumber, CommitTextarea } from './controls'
import { RowColumnsEditor } from './RowColumnsEditor'
import { FieldConditionsEditor } from './FormBehaviorEditor'
import { FormPattern, MAX_PATTERN_LENGTH } from '@/lib/form/pattern'

const KIND_ORDER: FieldKind[] = ['text', 'longText', 'number', 'money', 'integer', 'date', 'yesNo', 'choice', 'collection', 'file']

/**
 * The request form's questions as compact rows (docs/build/S120): label, type and rules in one line each; one click opens a
 * question's details. `open` is the question to open from outside (a check issue or a focus request).
 */
export function QuestionList({ open }: { open?: { key: string; n: number } | null }) {
  const { store, readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const fields = useMemo(() => fieldsFromDefinition(def), [def])
  const rules = useMemo(() => questionRules(def), [def])
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())
  const [pendingDelete, setPendingDelete] = useState<string | null>(null)

  // Opening from outside is adjusted while rendering, once per request (a check issue may name the same question again).
  const [openedRequest, setOpenedRequest] = useState<number | null>(null)
  if (open && open.n !== openedRequest) {
    setOpenedRequest(open.n)
    setExpanded((current) => (current.has(open.key) ? current : new Set([...current, open.key])))
  }

  const toggle = (key: string) => setExpanded((current) => {
    const next = new Set(current)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    return next
  })

  const update = (key: string, patch: Partial<FormFieldModel>) =>
    store.getState().apply((d) => applyFields(d, fieldsFromDefinition(d).map((f) => (f.key === key ? { ...f, ...patch } : f))))

  const move = (key: string, delta: -1 | 1) =>
    store.getState().apply((d) => {
      const list = fieldsFromDefinition(d)
      const i = list.findIndex((f) => f.key === key)
      const j = i + delta
      if (i < 0 || j < 0 || j >= list.length) return d
      const [item] = list.splice(i, 1)
      list.splice(j, 0, item as FormFieldModel)
      return applyFields(d, list)
    })

  const remove = (key: string) => store.getState().apply((d) => applyFields(d, fieldsFromDefinition(d).filter((f) => f.key !== key)))

  const usesOf = (key: string) => usagesOfRef(def, (ref) => ref === `/input/${key}` || ref.startsWith(`/input/${key}/`))
  const requestDelete = (key: string) => {
    if (usesOf(key).length === 0) remove(key)
    else setPendingDelete(key)
  }

  const add = () => {
    let added = ''
    store.getState().apply((d) => {
      const result = addField(d)
      added = result.key
      return result.def
    })
    if (!added) return
    setExpanded((current) => new Set([...current, added]))
    window.setTimeout(() => document.getElementById(`field-${added}-label`)?.focus(), 0)
  }

  const deleting = pendingDelete ? fields.find((f) => f.key === pendingDelete) : undefined
  const deletingUses = pendingDelete ? usesOf(pendingDelete) : []

  return (
    <>
      {fields.length === 0 ? <p className="text-sm text-muted-foreground">The form has no questions yet.</p> : null}
      <ol className="space-y-2" aria-label="Questions on the request form">
        {fields.map((f, index) => (
          <QuestionRow
            key={f.key}
            field={f}
            index={index}
            total={fields.length}
            rules={rules[f.key]}
            expanded={expanded.has(f.key)}
            readOnly={readOnly}
            onToggle={() => toggle(f.key)}
            onChange={(patch) => update(f.key, patch)}
            onMove={(delta) => move(f.key, delta)}
            onDelete={() => requestDelete(f.key)}
            onRename={(next) => {
              const result = renameFieldKey(store.getState().definition, f.key, next)
              if (!result.ok) return result.reason
              store.getState().apply(() => result.def)
              setExpanded((current) => new Set([...[...current].filter((k) => k !== f.key), next]))
              return undefined
            }}
          />
        ))}
      </ol>
      {!readOnly ? (
        <Button variant="secondary" onClick={add}>
          <Plus aria-hidden="true" /> Add a question
        </Button>
      ) : null}
      <ConfirmDialog
        open={!!deleting}
        title={deleting ? `Delete "${deleting.label}"?` : 'Delete question?'}
        confirmLabel="Delete question"
        destructive
        onCancel={() => setPendingDelete(null)}
        onConfirm={() => {
          if (pendingDelete) remove(pendingDelete)
          setPendingDelete(null)
        }}
      >
        <p>This question is used by:</p>
        <ul className="list-disc pl-5">
          {deletingUses.map((u) => (
            <li key={u}>{u}</li>
          ))}
        </ul>
        <p>Those places will report a problem until you point them at another value.</p>
      </ConfirmDialog>
    </>
  )
}

function QuestionRow({
  field: f,
  index,
  total,
  rules,
  expanded,
  readOnly,
  onToggle,
  onChange,
  onMove,
  onDelete,
  onRename,
}: {
  field: FormFieldModel
  index: number
  total: number
  rules: QuestionRules | undefined
  expanded: boolean
  readOnly: boolean
  onToggle: () => void
  onChange: (patch: Partial<FormFieldModel>) => void
  onMove: (delta: -1 | 1) => void
  onDelete: () => void
  onRename: (next: string) => string | undefined
}) {
  const detailsId = `question-${f.key}-details`
  const summaryId = `question-${f.key}-summary`
  const Chevron = expanded ? ChevronDown : ChevronRight
  const lines = [rules?.group ? `In group "${rules.group}"` : null, rules?.shown, rules?.required].filter((l): l is string => !!l)
  return (
    <li className="rounded-card border border-border bg-surface" data-testid={`question-${f.key}`}>
      <div className="flex items-start gap-2 p-2">
        <button type="button" className="flex min-w-0 flex-1 items-start gap-2 rounded-control p-1 text-left hover:bg-surface-muted" aria-label={`Question ${index + 1}: ${f.label || 'Untitled question'}`} aria-describedby={summaryId} aria-expanded={expanded} aria-controls={detailsId} onClick={onToggle}>
          <Chevron className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-semibold">
              {index + 1}. {f.label || 'Untitled question'}
            </span>
            <span id={summaryId}>
              <span className="block text-xs text-muted-foreground">
                {FIELD_KIND_LABELS[f.kind]}
                {f.required ? ' · Required' : ' · Optional'}
              </span>
              {lines.map((line) => (
                <span key={line} className="block text-xs text-primary-text">
                  {line}
                </span>
              ))}
            </span>
          </span>
        </button>
        {!readOnly ? (
          <div className="flex shrink-0 gap-1">
            <Button size="icon" variant="ghost" className="size-8" onClick={() => onMove(-1)} disabled={index === 0} aria-label={`Move "${f.label}" up`}>
              <ArrowUp aria-hidden="true" />
            </Button>
            <Button size="icon" variant="ghost" className="size-8" onClick={() => onMove(1)} disabled={index === total - 1} aria-label={`Move "${f.label}" down`}>
              <ArrowDown aria-hidden="true" />
            </Button>
            <Button size="icon" variant="ghost" className="size-8" onClick={onDelete} aria-label={`Delete "${f.label}"`}>
              <Trash2 aria-hidden="true" />
            </Button>
          </div>
        ) : null}
      </div>
      {expanded ? (
        <div id={detailsId} className="space-y-3 border-t border-border bg-surface-muted p-3" data-testid={`field-card-${f.key}`}>
          <QuestionDetails field={f} readOnly={readOnly} onChange={onChange} onRename={onRename} />
        </div>
      ) : null}
    </li>
  )
}

function QuestionDetails({
  field: f,
  readOnly,
  onChange,
  onRename,
}: {
  field: FormFieldModel
  readOnly: boolean
  onChange: (patch: Partial<FormFieldModel>) => void
  onRename: (next: string) => string | undefined
}) {
  const [keyError, setKeyError] = useState<string>()
  const p = `field-${f.key}`
  const numeric = f.kind === 'number' || f.kind === 'money' || f.kind === 'integer'
  const textual = f.kind === 'text' || f.kind === 'longText'
  return (
    <>
      <FormField label="Label" required id={`${p}-label`}>
        {(ids) => <CommitInput id={ids.input} data-field={`field.${f.key}`} value={f.label} maxLength={100} disabled={readOnly} onCommit={(v) => onChange({ label: v })} />}
      </FormField>
      <div className="grid grid-cols-[1fr_auto] items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor={`${p}-kind`}>Type</Label>
          <Select id={`${p}-kind`} value={f.kind} disabled={readOnly} onChange={(e) => onChange({ kind: e.target.value as FieldKind, ...(e.target.value === 'choice' && f.choices.length === 0 ? { choices: ['Option 1', 'Option 2'] } : {}) })}>
            {KIND_ORDER.map((k) => (
              <option key={k} value={k}>
                {FIELD_KIND_LABELS[k]}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex h-10 items-center gap-3">
          <Checkbox id={`${p}-required`} checked={f.required} disabled={readOnly} onCheckedChange={(v) => onChange({ required: v === true })} />
          <Label htmlFor={`${p}-required`} className="font-medium">
            Required
          </Label>
        </div>
      </div>
      <FormField label="Help text" optionalText id={`${p}-help`}>
        {(ids) => <CommitInput id={ids.input} value={f.help} maxLength={500} disabled={readOnly} onCommit={(v) => onChange({ help: v })} />}
      </FormField>
      {f.kind === 'choice' ? (
        <FormField label="Choices" id={`${p}-choices`} description="One choice per line.">
          {(ids) => (
            <CommitTextarea
              id={ids.input}
              value={f.choices.join('\n')}
              rows={4}
              disabled={readOnly}
              aria-describedby={ids.describedBy}
              onCommit={(v) => onChange({ choices: [...new Set(v.split('\n').map((c) => c.trim()).filter(Boolean))] })}
            />
          )}
        </FormField>
      ) : null}
      {f.kind === 'collection' ? <>
        <FormField label="Item type" id={`${p}-item-type`}>
          {ids => <Select id={ids.input} value={f.itemSchema?.type ?? 'string'} disabled={readOnly} onChange={e => onChange({ itemSchema: { type: e.target.value as 'string' | 'number' | 'integer' | 'boolean' | 'object', title: 'Item', ...(e.target.value === 'string' ? { minLength: 1, maxLength: 200 } : e.target.value === 'object' ? { additionalProperties: false, required: ['name'], properties: { name: { type: 'string', title: 'Name', minLength: 1, maxLength: 200 } } } : {}) } })}>
            <option value="string">Text</option><option value="number">Number</option><option value="integer">Whole number</option><option value="boolean">Yes/No</option><option value="object">Repeating rows</option>
          </Select>}
        </FormField>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Minimum items" id={`${p}-minitems`}>{ids => <CommitNumber id={ids.input} integer value={f.minItems ?? 1} disabled={readOnly} onCommit={v => onChange({ minItems: v ?? 1 })} />}</FormField>
          <FormField label="Maximum items" id={`${p}-maxitems`}>{ids => <CommitNumber id={ids.input} integer value={f.maxItems ?? 25} disabled={readOnly} onCommit={v => onChange({ maxItems: v ?? 25 })} />}</FormField>
        </div><p className="text-xs text-muted-foreground">Collections contain 1 to 25 scalar values or rows with up to 10 scalar columns.</p>
        {f.itemSchema?.type === 'object' ? <RowColumnsEditor schema={f.itemSchema} disabled={readOnly} onChange={itemSchema => onChange({ itemSchema })} /> : null}
      </> : null}
      {f.kind === 'file' ? (
        <fieldset className="space-y-2" data-testid={`file-options-${f.key}`}>
          <legend className="text-sm font-semibold">Accepted file types</legend>
          <p className="text-xs text-muted-foreground">Tick none to accept every allowed type. Files up to 10 MB; PDF, images, text, CSV and Office documents only.</p>
          <div className="grid grid-cols-2 gap-2">
            {ALL_FILE_TYPES.map((t) => (
              <div key={t} className="flex items-center gap-2">
                <Checkbox
                  id={`${p}-type-${t}`}
                  checked={(f.fileTypes ?? []).includes(t)}
                  disabled={readOnly}
                  onCheckedChange={(v) => onChange({ fileTypes: ALL_FILE_TYPES.filter((x) => (x === t ? v === true : (f.fileTypes ?? []).includes(x))) })}
                />
                <Label htmlFor={`${p}-type-${t}`} className="font-medium">{FILE_TYPES[t]?.label}</Label>
              </div>
            ))}
          </div>
          <FormField label="Largest file (MB)" optionalText id={`${p}-maxmb`}>
            {(ids) => <CommitNumber id={ids.input} value={f.maxFileMb} disabled={readOnly} onCommit={(v) => onChange({ maxFileMb: v === undefined ? undefined : Math.min(10, Math.max(0.1, v)) })} />}
          </FormField>
        </fieldset>
      ) : null}
      <FieldConditionsEditor fieldKey={f.key} />
      <Advanced label="Advanced">
        {f.kind !== 'file' && f.kind !== 'collection' ? <label htmlFor={`${p}-incident-editable`} className="flex items-center gap-2 text-sm">
          <Checkbox id={`${p}-incident-editable`} checked={!!f.incidentEditable} disabled={readOnly} onCheckedChange={checked => onChange({incidentEditable: checked === true})} />
          Allow operator correction during an incident before data is consumed
        </label> : null}
        <FormField label="Field key" id={`${p}-key`} error={keyError} description="Used in rules and exports. Changing it updates every place that uses this question.">
          {(ids) => (
            <CommitInput
              id={ids.input}
              value={f.key}
              disabled={readOnly}
              spellCheck={false}
              autoComplete="off"
              aria-invalid={keyError ? true : undefined}
              aria-describedby={ids.describedBy}
              onCommit={(v) => setKeyError(onRename(v.trim()))}
            />
          )}
        </FormField>
        {numeric ? (
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Minimum" optionalText id={`${p}-min`}>
              {(ids) => <CommitNumber id={ids.input} value={f.minimum} disabled={readOnly} onCommit={(v) => onChange({ minimum: v })} />}
            </FormField>
            <FormField label="Maximum" optionalText id={`${p}-max`}>
              {(ids) => <CommitNumber id={ids.input} value={f.maximum} disabled={readOnly} onCommit={(v) => onChange({ maximum: v })} />}
            </FormField>
          </div>
        ) : null}
        {f.kind === 'date' ? (
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Earliest date" optionalText id={`${p}-mindate`}>
              {(ids) => <CommitInput id={ids.input} type="date" value={f.minDate ?? ''} max={f.maxDate} disabled={readOnly} onCommit={(v) => onChange({ minDate: v || undefined })} />}
            </FormField>
            <FormField label="Latest date" optionalText id={`${p}-maxdate`}>
              {(ids) => <CommitInput id={ids.input} type="date" value={f.maxDate ?? ''} min={f.minDate} disabled={readOnly} onCommit={(v) => onChange({ maxDate: v || undefined })} />}
            </FormField>
          </div>
        ) : null}
        {textual ? (
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Shortest" optionalText id={`${p}-minlen`}>
              {(ids) => <CommitNumber id={ids.input} integer value={f.minLength} disabled={readOnly} onCommit={(v) => onChange({ minLength: v })} />}
            </FormField>
            <FormField label="Longest" optionalText id={`${p}-maxlen`}>
              {(ids) => <CommitNumber id={ids.input} integer value={f.maxLength} disabled={readOnly} onCommit={(v) => onChange({ maxLength: v })} />}
            </FormField>
          </div>
        ) : null}
        {textual ? <PatternFields field={f} readOnly={readOnly} onChange={onChange} /> : null}
      </Advanced>
    </>
  )
}

/** Answer pattern of a text question (docs/build/S90): checked as it is typed with the same safe engine as the server. */
function PatternFields({ field: f, readOnly, onChange }: { field: FormFieldModel; readOnly: boolean; onChange: (patch: Partial<FormFieldModel>) => void }) {
  const p = `field-${f.key}`
  const pattern = f.pattern?.trim() ?? ''
  const problem = pattern ? FormPattern.problem(pattern) : undefined
  const example = pattern && !problem ? FormPattern.compile(pattern).example() : null
  return (
    <div className="space-y-3" data-testid={`pattern-options-${f.key}`}>
      <FormField
        label="Answer pattern"
        optionalText
        id={`${p}-pattern`}
        error={problem}
        description={problem ? undefined : example !== null ? `The whole answer must match, for example "${example}".` : 'For example ^[A-Z]{2}-\\d{4}$ for a code like AB-1234. Start with ^ and end with $.'}
      >
        {(ids) => (
          <CommitInput
            id={ids.input}
            value={f.pattern ?? ''}
            maxLength={MAX_PATTERN_LENGTH}
            disabled={readOnly}
            spellCheck={false}
            autoComplete="off"
            className="font-mono"
            aria-invalid={problem ? true : undefined}
            aria-describedby={ids.describedBy}
            onCommit={(v) => onChange({ pattern: v.trim() || undefined, ...(v.trim() ? {} : { patternMessage: undefined }) })}
          />
        )}
      </FormField>
      {problem ? <p role="alert" className="sr-only">Answer pattern problem: {problem}</p> : null}
      {pattern ? (
        <FormField label="Message when the answer does not match" optionalText id={`${p}-pattern-message`} description="Plain text shown to the requester.">
          {(ids) => <CommitInput id={ids.input} value={f.patternMessage ?? ''} maxLength={200} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => onChange({ patternMessage: v.trim() || undefined })} />}
        </FormField>
      ) : null}
    </div>
  )
}
