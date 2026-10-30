import { useMemo, useState } from 'react'
import { FormRenderer } from '@/components/form/FormRenderer'
import { Button } from '@/components/ui/button'
import { FormAssistant } from '@/features/ai/FormAssistant'
import { buildFields, parseFormSchema, parseFormUi } from '@/lib/form/schema'
import { emptyValues, validateForm, type FieldErrors, type RawValue, type RawValues } from '@/lib/form/values'
import { visibleFields } from '@/lib/form/visibility'
import { useStudio } from './StudioContext'
import { FormLayoutEditor } from './inspector/FormBehaviorEditor'
import { QuestionList } from './inspector/QuestionEditor'
import { RequestViewPicker } from './inspector/RequestViewPicker'
import { InspectorSection } from './inspector/controls'
import { sampleValues } from './test/sampleValues'

/**
 * Form tab (docs/build/S120): the request form's questions, groups and rules on the left and a live preview on the right,
 * so the effect of every rule can be tried while it is written. `open` names a question to open (a check issue).
 */
export function FormView({ open }: { open?: { key: string; n: number } | null }) {
  return (
    <div className="grid h-full min-h-0 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]" data-testid="form-view">
      <div className="min-h-0 space-y-6 overflow-y-auto p-4 lg:border-r lg:border-border">
        <div>
          <h2 className="text-lg font-semibold">Request form</h2>
          <p className="text-sm text-muted-foreground">The questions someone answers to start a request. Open a question to change it or give it a rule.</p>
        </div>
        <InspectorSection title="Questions">
          <QuestionList open={open} />
          <FormAssistant />
        </InspectorSection>
        <FormLayoutEditor />
        <RequestViewPicker />
      </div>
      <FormPreviewPanel />
    </div>
  )
}

/** The request form as requesters see it, with answers the designer can type to try the rules. Nothing is sent. */
function FormPreviewPanel() {
  const def = useStudio((s) => s.definition)
  const schema = useMemo(() => parseFormSchema(def.inputSchema), [def.inputSchema])
  const ui = useMemo(() => parseFormUi(def.inputUi), [def.inputUi])
  const fields = useMemo(() => buildFields(schema, ui), [schema, ui])
  const [values, setValues] = useState<RawValues>({})
  const [errors, setErrors] = useState<FieldErrors>({})
  const merged = { ...emptyValues(fields), ...values }
  const shown = visibleFields(fields, merged)
  const hidden = fields.filter((f) => !shown.some((s) => s.key === f.key))
  return (
    <section aria-labelledby="form-preview-heading" className="min-h-0 overflow-y-auto bg-canvas p-4" data-testid="form-preview">
      <div className="mx-auto max-w-xl space-y-4">
        <div>
          <h2 id="form-preview-heading" className="text-lg font-semibold">Live preview</h2>
          <p className="text-sm text-muted-foreground">What requesters see. Type answers to try your rules; nothing is sent.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" disabled={fields.length === 0} onClick={() => { setValues(sampleValues(fields)); setErrors({}) }}>
            Fill sample answers
          </Button>
          <Button variant="ghost" size="sm" disabled={fields.length === 0} onClick={() => { setValues({}); setErrors({}) }}>
            Clear answers
          </Button>
          <Button variant="ghost" size="sm" disabled={fields.length === 0} onClick={() => setErrors(validateForm(schema, ui, merged).errors)}>
            Check my answers
          </Button>
        </div>
        <p role="status" aria-live="polite" className="text-sm" data-testid="form-preview-status">
          {fields.length === 0
            ? 'The form has no questions yet.'
            : hidden.length === 0
              ? `Showing all ${fields.length} ${fields.length === 1 ? 'question' : 'questions'}.`
              : `Showing ${shown.length} of ${fields.length} questions. Hidden by rules with these answers: ${hidden.map((f) => f.label).join(', ')}.`}
        </p>
        {fields.length > 0 ? (
          <div className="rounded-card border border-border bg-surface p-4">
            <FormRenderer fields={fields} values={merged} errors={errors} idPrefix="preview" onChange={(key: string, value: RawValue) => setValues((v) => ({ ...v, [key]: value }))} />
            <div className="mt-5">
              <Button variant="primary" disabled aria-describedby="form-preview-submit-note">
                {ui.submitLabel ?? 'Submit request'}
              </Button>
              <p id="form-preview-submit-note" className="mt-1 text-xs text-muted-foreground">Submitting is turned off in the preview.</p>
            </div>
          </div>
        ) : null}
      </div>
    </section>
  )
}
