import { FileText } from 'lucide-react'
import { useMemo } from 'react'
import { Button } from '@/components/ui/button'
import { useStudio, useStudioContext } from '../StudioContext'
import { questionRules } from '../model/formRules'
import { fieldsFromDefinition } from '../model/ops'
import { InspectorSection } from './controls'

/**
 * The request form step (docs/build/S120): a summary below the step name that the inspector header shows. Questions,
 * rules and groups are edited on the Studio's Form tab, which has room for a live preview; progress stages and sections
 * are workflow settings.
 */
export function StartInspector() {
  const { readOnly, openForm } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const fields = useMemo(() => fieldsFromDefinition(def), [def])
  const rules = useMemo(() => questionRules(def), [def])
  const ruled = Object.values(rules).filter((r) => r.shown || r.required).length
  const required = fields.filter((f) => f.required).length
  return (
    <>
      <InspectorSection title="Request form">
        <div className="rounded-card border border-border bg-surface-muted p-3" data-testid="request-form-summary">
          <p className="text-sm font-semibold">
            {fields.length === 0 ? 'No questions yet' : `${fields.length} ${fields.length === 1 ? 'question' : 'questions'}`}
          </p>
          {fields.length > 0 ? (
            <p className="text-xs text-muted-foreground">
              {required} required{ruled > 0 ? ` · ${ruled} with ${ruled === 1 ? 'a rule' : 'rules'}` : ''}
            </p>
          ) : null}
          <ul className="mt-2 space-y-0.5 text-sm">
            {fields.slice(0, 6).map((f) => (
              <li key={f.key} className="truncate">{f.label}</li>
            ))}
            {fields.length > 6 ? <li className="text-xs text-muted-foreground">and {fields.length - 6} more</li> : null}
          </ul>
        </div>
        {openForm ? (
          <Button variant="secondary" onClick={openForm}>
            <FileText aria-hidden="true" /> {readOnly ? 'Open the form' : 'Edit the form'}
          </Button>
        ) : null}
        <p className="text-xs text-muted-foreground">Progress stages and sections with handlers are workflow settings: press Workflow settings above.</p>
      </InspectorSection>
    </>
  )
}
