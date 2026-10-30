import { ArrowDown, ArrowUp, ChevronDown, ChevronRight, Plus, Trash2 } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ALL_FILE_TYPES, FILE_TYPES } from '@/api/attachments'
import { FormPattern, MAX_PATTERN_LENGTH } from '@/lib/form/pattern'
import type { QuestionRules } from '../model/formRules'
import { FIELD_KIND_LABELS, type FieldKind, type FormFieldModel } from '../model/ops'
import { Advanced, CommitInput, CommitNumber, CommitTextarea } from './controls'
import { RowColumnsEditor } from './RowColumnsEditor'

export const ALL_FIELD_KINDS: FieldKind[] = ['text', 'longText', 'number', 'money', 'integer', 'date', 'yesNo', 'choice', 'collection', 'file']

/** The words the list uses for one item: "question" on the request form, "field" for case data. */
export interface FieldNoun {
  one: string
  title: string
}

export interface FieldListEditorProps {
  fields: FormFieldModel[]
  readOnly: boolean
  /** The item to open from outside (a check issue or a focus request), once per `n`. */
  open?: { key: string; n: number } | null
  /** Types offered in the Type select. The request form offers all; case data offers single values only. */
  kinds?: FieldKind[]
  /** Prefix of element ids and test ids; empty for the request form, so its ids are unchanged. */
  idPrefix?: string
  noun?: FieldNoun
  listLabel: string
  emptyText: string
  addLabel: string
  /** Most items the list accepts; the add button is disabled at the limit. */
  max?: number
  rules?: Record<string, QuestionRules>
  /** Rule editors of one item (the request form's conditions); left out where rules do not apply. */
  renderRules?: (key: string) => ReactNode
  /** Offer "operator correction during an incident" (request form only). */
  incidentEditable?: boolean
  keyDescription?: string
  /** A warning about an item's key that does not stop it from working, shown on its row and under the key field. */
  keyWarning?: (key: string) => string | undefined
  onUpdate: (key: string, patch: Partial<FormFieldModel>) => void
  onMove: (key: string, delta: -1 | 1) => void
  onDelete: (key: string) => void
  /** Adds an item and returns its key, or undefined when nothing was added. */
  onAdd: () => string | undefined
  /** Renames a key; returns the reason when the new key is refused. */
  onRename: (key: string, next: string) => string | undefined
}

const QUESTION: FieldNoun = { one: 'question', title: 'Question' }

/**
 * A form's fields as compact rows (docs/build/S120): label, type and rules in one line each; one click opens an item's
 * details. It holds no store: the request form (Studio) and case data fields (docs/build/S125) both use it, so a field is
 * defined the same way everywhere.
 */
export function FieldListEditor({
  fields,
  readOnly,
  open,
  kinds = ALL_FIELD_KINDS,
  idPrefix = '',
  noun = QUESTION,
  listLabel,
  emptyText,
  addLabel,
  max,
  rules,
  renderRules,
  incidentEditable = false,
  keyDescription = 'Used in rules and exports. Changing it updates every place that uses this question.',
  keyWarning,
  onUpdate,
  onMove,
  onDelete,
  onAdd,
  onRename,
}: FieldListEditorProps) {
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())
  // Items whose Advanced settings are open: kept across a field-key rename, which remounts the item.
  const [advanced, setAdvanced] = useState<Set<string>>(() => new Set())
  // Element to focus once it is rendered: a new item's label, or the key field of a renamed item. A timer is not
  // enough: the opened item renders after the store update, which a real browser may do after a 0 ms timeout.
  const pendingFocus = useRef<string | null>(null)
  useEffect(() => {
    if (!pendingFocus.current) return
    const target = document.getElementById(pendingFocus.current)
    if (!target) return
    pendingFocus.current = null
    target.focus()
  })

  // Opening from outside is adjusted while rendering, once per request (a check issue may name the same item again).
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

  const add = () => {
    const added = onAdd()
    if (!added) return
    setExpanded((current) => new Set([...current, added]))
    pendingFocus.current = `${idPrefix}field-${added}-label`
  }

  return (
    <>
      {fields.length === 0 ? <p className="text-sm text-muted-foreground">{emptyText}</p> : null}
      <ol className="space-y-2" aria-label={listLabel}>
        {fields.map((f, index) => (
          <QuestionRow
            key={f.key}
            field={f}
            index={index}
            total={fields.length}
            rules={rules?.[f.key]}
            expanded={expanded.has(f.key)}
            advancedOpen={advanced.has(f.key)}
            onAdvancedToggle={(isOpen) => setAdvanced((current) => {
              if (isOpen === current.has(f.key)) return current
              const next = new Set(current)
              if (isOpen) next.add(f.key)
              else next.delete(f.key)
              return next
            })}
            readOnly={readOnly}
            kinds={kinds}
            idPrefix={idPrefix}
            noun={noun}
            renderRules={renderRules}
            incidentEditable={incidentEditable}
            keyDescription={keyDescription}
            keyWarning={keyWarning?.(f.key)}
            onToggle={() => toggle(f.key)}
            onChange={(patch) => onUpdate(f.key, patch)}
            onMove={(delta) => onMove(f.key, delta)}
            onDelete={() => onDelete(f.key)}
            onRename={(next) => {
              if (next === f.key) return undefined
              const reason = onRename(f.key, next)
              if (reason) return reason
              const carry = (current: Set<string>) => (current.has(f.key) ? new Set([...[...current].filter((k) => k !== f.key), next]) : current)
              setExpanded(carry)
              setAdvanced(carry)
              // The renamed item is a new element: give the key field its focus back.
              pendingFocus.current = `${idPrefix}field-${next}-key`
              return undefined
            }}
          />
        ))}
      </ol>
      {!readOnly ? (
        <Button variant="secondary" onClick={add} disabled={max !== undefined && fields.length >= max}>
          <Plus aria-hidden="true" /> {addLabel}
        </Button>
      ) : null}
    </>
  )
}

interface RowProps {
  field: FormFieldModel
  index: number
  total: number
  rules: QuestionRules | undefined
  expanded: boolean
  advancedOpen: boolean
  onAdvancedToggle: (open: boolean) => void
  readOnly: boolean
  kinds: FieldKind[]
  idPrefix: string
  noun: FieldNoun
  renderRules: ((key: string) => ReactNode) | undefined
  incidentEditable: boolean
  keyDescription: string
  keyWarning: string | undefined
  onToggle: () => void
  onChange: (patch: Partial<FormFieldModel>) => void
  onMove: (delta: -1 | 1) => void
  onDelete: () => void
  onRename: (next: string) => string | undefined
}

function QuestionRow({ field: f, index, total, rules, expanded, advancedOpen, onAdvancedToggle, readOnly, kinds, idPrefix, noun, renderRules, incidentEditable, keyDescription, keyWarning, onToggle, onChange, onMove, onDelete, onRename }: RowProps) {
  const detailsId = `${idPrefix}question-${f.key}-details`
  const summaryId = `${idPrefix}question-${f.key}-summary`
  const Chevron = expanded ? ChevronDown : ChevronRight
  const untitled = `Untitled ${noun.one}`
  const lines = [rules?.group ? `In group "${rules.group}"` : null, rules?.shown, rules?.required].filter((l): l is string => !!l)
  return (
    <li className="rounded-card border border-border bg-surface" data-testid={`${idPrefix}question-${f.key}`}>
      <div className="flex items-start gap-2 p-2">
        <button type="button" className="flex min-w-0 flex-1 items-start gap-2 rounded-control p-1 text-left hover:bg-surface-muted" aria-label={`${noun.title} ${index + 1}: ${f.label || untitled}`} aria-describedby={summaryId} aria-expanded={expanded} aria-controls={detailsId} onClick={onToggle}>
          <Chevron className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-semibold">
              {index + 1}. {f.label || untitled}
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
              {keyWarning ? <span className="block text-xs font-medium text-warning-foreground">Key {f.key} uses the older pattern</span> : null}
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
        <div id={detailsId} className="space-y-3 border-t border-border bg-surface-muted p-3" data-testid={`${idPrefix}field-card-${f.key}`}>
          <QuestionDetails field={f} readOnly={readOnly} kinds={kinds} idPrefix={idPrefix} renderRules={renderRules} incidentEditable={incidentEditable} keyDescription={keyDescription} keyWarning={keyWarning} advancedOpen={advancedOpen} onAdvancedToggle={onAdvancedToggle} onChange={onChange} onRename={onRename} />
        </div>
      ) : null}
    </li>
  )
}

function QuestionDetails({
  field: f,
  readOnly,
  kinds,
  idPrefix,
  renderRules,
  incidentEditable,
  keyDescription,
  keyWarning,
  advancedOpen,
  onAdvancedToggle,
  onChange,
  onRename,
}: {
  field: FormFieldModel
  readOnly: boolean
  kinds: FieldKind[]
  idPrefix: string
  renderRules: ((key: string) => ReactNode) | undefined
  incidentEditable: boolean
  keyDescription: string
  keyWarning: string | undefined
  advancedOpen: boolean
  onAdvancedToggle: (open: boolean) => void
  onChange: (patch: Partial<FormFieldModel>) => void
  onRename: (next: string) => string | undefined
}) {
  const [keyError, setKeyError] = useState<string>()
  const p = `${idPrefix}field-${f.key}`
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
            {kinds.map((k) => (
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
        <fieldset className="space-y-2" data-testid={`${idPrefix}file-options-${f.key}`}>
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
      {renderRules?.(f.key)}
      {keyWarning ? <p className="text-sm text-warning-foreground" data-testid={`${idPrefix}key-warning-${f.key}`}>{keyWarning}</p> : null}
      <Advanced label="Advanced" open={advancedOpen} onToggle={onAdvancedToggle}>
        {incidentEditable && f.kind !== 'file' && f.kind !== 'collection' ? <label htmlFor={`${p}-incident-editable`} className="flex items-center gap-2 text-sm">
          <Checkbox id={`${p}-incident-editable`} checked={!!f.incidentEditable} disabled={readOnly} onCheckedChange={checked => onChange({incidentEditable: checked === true})} />
          Allow operator correction during an incident before data is consumed
        </label> : null}
        <FormField label="Field key" id={`${p}-key`} error={keyError} description={keyDescription}>
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
        {textual ? <PatternFields field={f} idPrefix={idPrefix} readOnly={readOnly} onChange={onChange} /> : null}
      </Advanced>
    </>
  )
}

/** Answer pattern of a text field (docs/build/S90): checked as it is typed with the same safe engine as the server. */
function PatternFields({ field: f, idPrefix, readOnly, onChange }: { field: FormFieldModel; idPrefix: string; readOnly: boolean; onChange: (patch: Partial<FormFieldModel>) => void }) {
  const p = `${idPrefix}field-${f.key}`
  const pattern = f.pattern?.trim() ?? ''
  const problem = pattern ? FormPattern.problem(pattern) : undefined
  const example = pattern && !problem ? FormPattern.compile(pattern).example() : null
  return (
    <div className="space-y-3" data-testid={`${idPrefix}pattern-options-${f.key}`}>
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
