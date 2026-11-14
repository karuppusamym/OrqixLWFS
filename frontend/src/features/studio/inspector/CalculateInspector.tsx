import { Plus, Trash2 } from 'lucide-react'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { type ExprType, ROUNDING } from '@/lib/expr'
import { declaredType, type CalculateOutput } from '@/lib/expr/calculate'
import { useStudio, useStudioContext } from '../StudioContext'
import { studioScope } from '../model/expressionScope'
import type { WorkflowNode } from '../model/types'
import { Advanced, CommitInput, CommitNumber, InspectorSection } from './controls'
import { ExpressionEditor } from './ExpressionEditor'
import { StepIdField } from './SimpleInspectors'

const TYPE_LABEL: Record<CalculateOutput['type'], string> = {
  number: 'A number', integer: 'A whole number', string: 'Text', boolean: 'True or false', date: 'A date',
}
const ROUNDING_LABEL: Record<string, string> = {
  halfUp: 'Half up (2.5 becomes 3)', halfEven: 'Half to even, banker’s (2.5 becomes 2)', down: 'Towards zero',
  up: 'Away from zero', floor: 'Down', ceiling: 'Up',
}

/**
 * Calculate (CG-X01, docs/build/S137): named outputs, each a formula over the request, earlier steps and the outputs
 * above it. The compiler checks every formula and its declared type before publishing; the engine evaluates them with
 * the same language as this preview.
 */
export function CalculateInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const outputs = (Array.isArray(node.config.outputs) ? node.config.outputs : []) as CalculateOutput[]
  const set = (next: CalculateOutput[]) => store.getState().updateNodeConfig(node.id, { outputs: next })
  const change = (index: number, patch: Partial<CalculateOutput>) =>
    set(outputs.map((o, i) => {
      if (i !== index) return o
      const merged = { ...o, ...patch }
      if (merged.type !== 'number' && merged.type !== 'integer') { delete merged.scale; delete merged.rounding }
      if (merged.type === 'integer') delete merged.scale
      return merged
    }))
  const free = (base: string) => {
    let n = outputs.length + 1
    while (outputs.some(o => o.key === `${base}${n}`)) n++
    return `${base}${n}`
  }
  return (
    <>
      <InspectorSection title="Outputs">
        <p className="text-sm text-muted-foreground">
          Each output is worked out in order and can use the outputs above it by name. Later steps use them as results of this step.
        </p>
        <ol className="space-y-3" aria-label="Outputs">
          {outputs.map((o, i) => {
            const locals: Record<string, ExprType> = {}
            for (const earlier of outputs.slice(0, i)) locals[earlier.key] = declaredType(earlier.type)
            const context = studioScope(def, node.id, connections, locals)
            const numeric = o.type === 'number' || o.type === 'integer'
            return (
              <li key={i} className="space-y-3 rounded-card border border-border bg-surface-muted p-3" data-testid={`calc-output-${i}`}>
                <div className="flex items-center justify-between gap-2">
                  <h4 className="text-sm font-semibold">Output {i + 1}</h4>
                  {!readOnly && outputs.length > 1 ? (
                    <Button size="icon" variant="ghost" className="size-8" onClick={() => set(outputs.filter((_, j) => j !== i))} aria-label={`Remove output ${o.key}`}>
                      <Trash2 aria-hidden="true" />
                    </Button>
                  ) : null}
                </div>
                <FormField label="Name" id={`calc-${node.id}-${i}-key`} description="Lower camel case, for example totalWithTax.">
                  {(ids) => <CommitInput id={ids.input} data-field={`config.outputs.${i}.key`} value={o.key} maxLength={64} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(key) => change(i, { key: key.trim() })} />}
                </FormField>
                <FormField label="It gives" id={`calc-${node.id}-${i}-type`}>
                  {(ids) => (
                    <Select id={ids.input} data-field={`config.outputs.${i}.type`} value={o.type} disabled={readOnly} onChange={(e) => change(i, { type: e.target.value as CalculateOutput['type'] })}>
                      {Object.entries(TYPE_LABEL).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
                    </Select>
                  )}
                </FormField>
                <ExpressionEditor id={`calc-${node.id}-${i}-expr`} label="Formula" dataField={`config.outputs.${i}.expression`} value={o.expression}
                  context={context} expected={declaredType(o.type)} disabled={readOnly} onCommit={(expression) => change(i, { expression })} />
                {numeric ? (
                  <div className="grid grid-cols-2 gap-2">
                    {o.type === 'number' ? (
                      <FormField label="Decimal places" optionalText id={`calc-${node.id}-${i}-scale`} description="Leave empty to keep the exact result.">
                        {(ids) => <CommitNumber id={ids.input} data-field={`config.outputs.${i}.scale`} value={o.scale} integer disabled={readOnly} aria-describedby={ids.describedBy}
                          onCommit={(scale) => change(i, scale === undefined ? { scale: undefined } : { scale: Math.max(0, Math.min(10, scale)) })} />}
                      </FormField>
                    ) : null}
                    <FormField label="Rounding" id={`calc-${node.id}-${i}-rounding`}>
                      {(ids) => (
                        <Select id={ids.input} data-field={`config.outputs.${i}.rounding`} value={o.rounding ?? 'halfUp'} disabled={readOnly} onChange={(e) => change(i, { rounding: e.target.value as CalculateOutput['rounding'] })}>
                          {ROUNDING.map((r) => <option key={r} value={r}>{ROUNDING_LABEL[r]}</option>)}
                        </Select>
                      )}
                    </FormField>
                  </div>
                ) : null}
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={o.optional === true} disabled={readOnly} data-field={`config.outputs.${i}.optional`}
                    onChange={(e) => change(i, e.target.checked ? { optional: true } : { optional: undefined })} />
                  May have no value (otherwise a request without a value stops for attention)
                </label>
              </li>
            )
          })}
        </ol>
        {!readOnly ? (
          <Button variant="secondary" disabled={outputs.length >= 20} onClick={() => set([...outputs, { key: free('result'), expression: '0', type: 'number' }])}>
            <Plus aria-hidden="true" /> Add output
          </Button>
        ) : null}
      </InspectorSection>
      <Advanced>
        <StepIdField node={node} />
      </Advanced>
    </>
  )
}
