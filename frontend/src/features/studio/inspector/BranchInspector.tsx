import { Plus, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields } from '../model/fieldRefs'
import { nodeById, outgoing, wouldCreateCycle } from '../model/graph'
import { addBranchPath, setDefaultRoute } from '../model/ops'
import { targetLabel } from '../model/summary'
import type { Condition, Transition, WorkflowNode } from '../model/types'
import { Advanced, CommitInput, InspectorSection } from './controls'
import { ConditionEditor, defaultLiteral } from './ConditionEditor'
import { StepIdField } from './SimpleInspectors'

/**
 * Choose a path: routes with a condition and exactly one Otherwise route
 * (docs/build/S7-frontend.md section 5). Conditions are structured: field, operator, value.
 */
export function BranchInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const fields = useMemo(() => availableFields(def, node.id, connections), [def, node.id, connections])
  const routes = outgoing(def, node.id)
  const ordered = [...routes.filter((t) => t.on === 'condition'), ...routes.filter((t) => t.on !== 'condition')]
  const otherwise = routes.find((t) => t.on === 'default')
  const [target, setTarget] = useState('')

  const targets = def.nodes.filter((n) => n.type !== 'start' && n.id !== node.id && !routes.some((t) => t.to === n.id) && !wouldCreateCycle(def, node.id, n.id))

  const updateCondition = (t: Transition, condition: Condition) => store.getState().updateTransition(t.id, { condition })

  const addPath = () => {
    const to = target || targets[0]?.id
    if (!to) return
    const first = fields.find((f) => f.type === 'number') ?? fields[0]
    store.getState().apply((d) => {
      const result = addBranchPath(d, node.id, to, { fieldRef: routes.find((t) => t.condition?.fieldRef)?.condition?.fieldRef ?? first?.ref ?? '', operator: 'eq', value: defaultLiteral(first?.type, first?.enumValues) })
      return result.ok ? result.def : d
    })
    setTarget('')
  }

  return (
    <>
      <InspectorSection title="Basic">
        <p className="text-sm text-muted-foreground">Each path has a condition. Exactly one path is the Otherwise path, taken when no condition matches.</p>
        {ordered.length > 0 ? (
          <fieldset className="space-y-2">
            <legend className="text-sm font-semibold">Which path is Otherwise?</legend>
            <RadioGroup
              value={otherwise?.id ?? ''}
              disabled={readOnly}
              onValueChange={(id) => store.getState().apply((d) => setDefaultRoute(d, node.id, id, fields[0]?.ref ?? ''))}
              aria-label="Which path is Otherwise?"
            >
              {ordered.map((t) => (
                <div key={t.id} className="flex items-center gap-3">
                  <RadioGroupItem value={t.id} id={`otherwise-${t.id}`} data-field={`route.${t.id}.default`} />
                  <Label htmlFor={`otherwise-${t.id}`} className="font-medium">
                    Path to {targetLabel(def, t.to)}
                  </Label>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
        ) : null}

        <ol className="space-y-3" aria-label="Paths">
          {ordered.map((t) => (
            <li key={t.id} className="space-y-3 rounded-card border border-border bg-surface-muted p-3" data-testid={`route-${t.id}`}>
              <div className="flex items-center justify-between gap-2">
                <h4 className="text-sm font-semibold">
                  {t.on === 'default' ? 'Otherwise, go to' : 'When true, go to'} {targetLabel(def, t.to)}
                </h4>
                {!readOnly ? (
                  <Button size="icon" variant="ghost" className="size-8" onClick={() => store.getState().removeTransition(t.id)} aria-label={`Remove path to ${targetLabel(def, t.to)}`}>
                    <Trash2 aria-hidden="true" />
                  </Button>
                ) : null}
              </div>
              {t.on === 'condition' && t.condition ? (
                <ConditionEditor idBase={`route-${t.id}`} dataFieldBase={`route.${t.id}.condition`} condition={t.condition} fields={fields} disabled={readOnly} onChange={(c) => updateCondition(t, c)} />
              ) : null}
              <FormField label="Label on the canvas" optionalText id={`route-${t.id}-label`} description="For example: USD 5,000 or more.">
                {(ids) => (
                  <CommitInput
                    id={ids.input}
                    data-field={`route.${t.id}.label`}
                    value={t.label ?? ''}
                    maxLength={80}
                    disabled={readOnly}
                    aria-describedby={ids.describedBy}
                    onCommit={(v) => store.getState().updateTransition(t.id, { label: v.trim() })}
                  />
                )}
              </FormField>
            </li>
          ))}
        </ol>

        {!readOnly ? (
          <div className="space-y-2 rounded-card border border-dashed border-input-border p-3">
            <Label htmlFor={`branch-add-${node.id}`}>Add a path to</Label>
            <Select id={`branch-add-${node.id}`} value={target} onChange={(e) => setTarget(e.target.value)} disabled={targets.length === 0}>
              <option value="">{targets.length === 0 ? 'No other step available' : 'Choose a step'}</option>
              {targets.map((n) => (
                <option key={n.id} value={n.id}>
                  {nodeById(def, n.id)?.label}
                </option>
              ))}
            </Select>
            <Button variant="secondary" onClick={addPath} disabled={!target}>
              <Plus aria-hidden="true" /> Add path
            </Button>
          </div>
        ) : null}
      </InspectorSection>
      <Advanced>
        <StepIdField node={node} />
      </Advanced>
    </>
  )
}
