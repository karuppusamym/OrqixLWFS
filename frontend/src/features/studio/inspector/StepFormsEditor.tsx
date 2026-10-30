import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import type { FieldSchemaDoc, WorkflowNode } from '../model/types'
import {
  ACCESS_LABELS, MAX_CAPTURED, accessOf, accessPatch, captureKey, captureOf, capturePatch, changeable, stepFormBlocked,
  stepFormSteps, type FieldAccess,
} from '../model/stepForms'
import { CommitInput, InspectorSection } from './controls'

/** Types a step can ask for: single values only (the compiler refuses lists, rows and files). */
const CAPTURE_TYPES: Array<{ id: string; label: string; schema: (title: string) => FieldSchemaDoc }> = [
  { id: 'text', label: 'Text', schema: (title) => ({ type: 'string', title, maxLength: 200 }) },
  { id: 'number', label: 'Number', schema: (title) => ({ type: 'number', title }) },
  { id: 'integer', label: 'Whole number', schema: (title) => ({ type: 'integer', title }) },
  { id: 'boolean', label: 'Yes or no', schema: (title) => ({ type: 'boolean', title }) },
  { id: 'date', label: 'Date', schema: (title) => ({ type: 'string', format: 'date', title }) },
]

function captureTypeOf(field: FieldSchemaDoc): string {
  if (field.type === 'string') return field.format === 'date' ? 'date' : 'text'
  return field.type
}

/**
 * Step forms (docs/build/S123) on the Form tab: a grid of request questions × human steps that says how each step
 * shows each question, and the fields each step fills in itself. Publishing proves that a later step uses a filled-in
 * field only after the step that fills it in has approved.
 */
export function StepFormsEditor() {
  const definition = useStudio((s) => s.definition)
  const { store, readOnly } = useStudioContext()
  const steps = stepFormSteps(definition)
  const questions = Object.entries(definition.inputSchema.properties)
  const set = (node: WorkflowNode, patch: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, patch)

  return (
    <InspectorSection title="Step forms">
      <p className="text-sm text-muted-foreground">
        Choose what each approval step shows, what the approver may change, and what they fill in themselves. A step that
        fills in a field, such as a PO number, makes it available to later steps after it approves.
      </p>
      {steps.length === 0 ? (
        <p className="text-sm text-muted-foreground">Add an approval step to give it a step form.</p>
      ) : (
        <>
          {questions.length > 0 ? (
            <div className="overflow-x-auto rounded-card border border-border">
              <table className="w-full text-sm" data-testid="step-form-grid">
                <caption className="sr-only">How each approval step shows each request question</caption>
                <thead className="bg-surface-muted">
                  <tr>
                    <th scope="col" className="px-3 py-2 text-left font-semibold">Question</th>
                    {steps.map((step) => (
                      <th key={step.id} scope="col" className="px-3 py-2 text-left font-semibold">{step.label}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {questions.map(([key, field]) => (
                    <tr key={key}>
                      <th scope="row" className="px-3 py-2 text-left font-normal">{field.title ?? key}</th>
                      {steps.map((step) => {
                        const access = accessOf(step, key)
                        const blocked = stepFormBlocked(step)
                        const options: FieldAccess[] = changeable(field) && !blocked
                          ? ['readOnly', 'hidden', 'editable', 'required'] : ['readOnly', 'hidden']
                        return (
                          <td key={step.id} className="px-3 py-1.5">
                            <Select
                              aria-label={`${field.title ?? key} at ${step.label}`}
                              value={access}
                              disabled={readOnly}
                              onChange={(e) => set(step, accessPatch(step, key, e.target.value as FieldAccess))}
                            >
                              {options.map((option) => <option key={option} value={option}>{ACCESS_LABELS[option]}</option>)}
                              {options.includes(access) ? null : <option value={access}>{ACCESS_LABELS[access]} (not allowed here)</option>}
                            </Select>
                          </td>
                        )
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
          {steps.map((step) => <CapturedFields key={step.id} step={step} readOnly={readOnly} onChange={(patch) => set(step, patch)} />)}
        </>
      )}
    </InspectorSection>
  )
}

function CapturedFields({ step, readOnly, onChange }: { step: WorkflowNode; readOnly: boolean; onChange: (patch: Record<string, unknown>) => void }) {
  const capture = captureOf(step)
  const properties = capture?.properties ?? {}
  const required = capture?.required ?? []
  const keys = Object.keys(properties)
  const blocked = stepFormBlocked(step)
  const update = (next: Record<string, FieldSchemaDoc>, nextRequired = required) => onChange(capturePatch(next, nextRequired))
  const headingId = `capture-${step.id}`

  return (
    <section aria-labelledby={headingId} className="space-y-2 rounded-card border border-border p-3" data-testid={`capture-${step.id}`}>
      <h4 id={headingId} className="text-sm font-semibold">Fields {step.label} fills in</h4>
      {blocked ? <p className="text-xs text-muted-foreground">{blocked}</p> : null}
      {keys.length === 0 ? <p className="text-xs text-muted-foreground">None. The approver only approves or rejects.</p> : (
        <ul className="space-y-2">
          {keys.map((key) => {
            const field = properties[key]!
            const label = field.title ?? key
            return (
              <li key={key} className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_9rem_auto_auto] sm:items-center">
                <CommitInput aria-label={`Name of the field ${label}`} value={label} disabled={readOnly} maxLength={80}
                  onCommit={(title) => title.trim() && update({ ...properties, [key]: { ...field, title: title.trim() } })} />
                <Select aria-label={`Type of ${label}`} value={captureTypeOf(field)} disabled={readOnly}
                  onChange={(e) => {
                    const type = CAPTURE_TYPES.find((t) => t.id === e.target.value)!
                    update({ ...properties, [key]: type.schema(label) })
                  }}>
                  {CAPTURE_TYPES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
                </Select>
                <div className="flex items-center gap-2 text-sm">
                  <Checkbox id={`capture-${step.id}-${key}-required`} checked={required.includes(key)} disabled={readOnly}
                    onCheckedChange={(checked) => update(properties, checked ? [...required, key] : required.filter((k) => k !== key))} />
                  <Label htmlFor={`capture-${step.id}-${key}-required`}>Required to approve</Label>
                </div>
                <Button variant="ghost" size="sm" disabled={readOnly} aria-label={`Remove ${label}`}
                  onClick={() => {
                    const next = { ...properties }
                    delete next[key]
                    update(next, required.filter((k) => k !== key))
                  }}>
                  <Trash2 aria-hidden="true" />
                </Button>
              </li>
            )
          })}
        </ul>
      )}
      <Button variant="secondary" size="sm" disabled={readOnly || !!blocked || keys.length >= MAX_CAPTURED}
        onClick={() => {
          const title = `New field ${keys.length + 1}`
          update({ ...properties, [captureKey(title, keys)]: CAPTURE_TYPES[0]!.schema(title) })
        }}>
        <Plus aria-hidden="true" /> Add a field
      </Button>
      {keys.length > 0 ? (
        <p className="text-xs text-muted-foreground">
          Later steps can use these after {step.label} approves, for example in a route condition. A rejection leaves them empty.
        </p>
      ) : null}
    </section>
  )
}
