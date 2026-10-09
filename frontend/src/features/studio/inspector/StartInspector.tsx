import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { usagesOfRef } from '../model/fieldRefs'
import { addField, applyFields, fieldsFromDefinition, FIELD_KIND_LABELS, renameFieldKey, type FieldKind, type FormFieldModel } from '../model/ops'
import type { WorkflowNode } from '../model/types'
import { Advanced, CommitInput, CommitNumber, CommitTextarea, InspectorSection } from './controls'

const KIND_ORDER: FieldKind[] = ['text', 'longText', 'number', 'money', 'integer', 'yesNo', 'choice']

/** Request form editor: an ordered list of questions (docs/build/S7-frontend.md section 5, inspector table). */
export function StartInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const fields = useMemo(() => fieldsFromDefinition(def), [def])
  const [pendingDelete, setPendingDelete] = useState<string | null>(null)

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

  const requestDelete = (key: string) => {
    const uses = usagesOfRef(def, (ref) => ref === `/input/${key}`)
    if (uses.length === 0) remove(key)
    else setPendingDelete(key)
  }

  const deleting = pendingDelete ? fields.find((f) => f.key === pendingDelete) : undefined
  const deletingUses = pendingDelete ? usagesOfRef(def, (ref) => ref === `/input/${pendingDelete}`) : []

  return (
    <>
      <InspectorSection title="Questions on the request form">
        {fields.length === 0 ? <p className="text-sm text-muted-foreground">The form has no questions yet.</p> : null}
        <ol className="space-y-3">
          {fields.map((f, index) => (
            <FieldCard
              key={f.key}
              field={f}
              index={index}
              total={fields.length}
              readOnly={readOnly}
              onChange={(patch) => update(f.key, patch)}
              onMove={(delta) => move(f.key, delta)}
              onDelete={() => requestDelete(f.key)}
              onRename={(next) => {
                const result = renameFieldKey(store.getState().definition, f.key, next)
                if (result.ok) store.getState().apply(() => result.def)
                return result.ok ? undefined : result.reason
              }}
            />
          ))}
        </ol>
        {!readOnly ? (
          <Button variant="secondary" onClick={() => store.getState().apply((d) => addField(d).def)}>
            <Plus aria-hidden="true" /> Add a question
          </Button>
        ) : null}
      </InspectorSection>
      <Advanced>
        <FormField label="Step name" id={`start-label-${node.id}`}>
          {(ids) => <CommitInput id={ids.input} value={node.label} disabled={readOnly} onCommit={(v) => store.getState().updateNodeLabel(node.id, v)} />}
        </FormField>
      </Advanced>
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

function FieldCard({
  field: f,
  index,
  total,
  readOnly,
  onChange,
  onMove,
  onDelete,
  onRename,
}: {
  field: FormFieldModel
  index: number
  total: number
  readOnly: boolean
  onChange: (patch: Partial<FormFieldModel>) => void
  onMove: (delta: -1 | 1) => void
  onDelete: () => void
  onRename: (next: string) => string | undefined
}) {
  const [keyError, setKeyError] = useState<string>()
  const p = `field-${f.key}`
  const numeric = f.kind === 'number' || f.kind === 'money' || f.kind === 'integer'
  const textual = f.kind === 'text' || f.kind === 'longText'
  return (
    <li className="space-y-3 rounded-card border border-border bg-surface-muted p-3" data-testid={`field-card-${f.key}`}>
      <div className="flex items-center justify-between gap-2">
        <h4 className="text-sm font-semibold">Question {index + 1}</h4>
        {!readOnly ? (
          <div className="flex gap-1">
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
      <FormField label="Label" required id={`${p}-label`}>
        {(ids) => <CommitInput id={ids.input} data-field={`field.${f.key}`} value={f.label} maxLength={100} disabled={readOnly} onCommit={(v) => onChange({ label: v })} />}
      </FormField>
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
      <div className="flex items-center gap-3">
        <Checkbox id={`${p}-required`} checked={f.required} disabled={readOnly} onCheckedChange={(v) => onChange({ required: v === true })} />
        <Label htmlFor={`${p}-required`} className="font-medium">
          Required
        </Label>
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
      <Advanced label="Advanced">
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
      </Advanced>
    </li>
  )
}
