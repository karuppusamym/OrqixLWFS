import { Plus, Trash2 } from 'lucide-react'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { cfg, type WorkflowNode } from '../model/types'
import { Advanced, CommitInput, CommitNumber, InspectorSection } from './controls'
import { StepIdField } from './SimpleInspectors'
import type { PayloadField } from './MessageInspector'

/** Topics are lowercase words joined with dashes (schema pattern). */
export const TOPIC = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/
const VALUE_NAME = /^[a-z][A-Za-z0-9]{0,63}$/
const INPUT_NAME = /^[A-Za-z][A-Za-z0-9_]{0,62}$/

type ValueType = PayloadField['type']
const TYPE_LABEL: Record<ValueType, string> = { string: 'Text', number: 'Number', integer: 'Whole number', boolean: 'True or false', date: 'Date' }

/**
 * Work done by an external worker (docs/build/S43): which kind of job, what the worker receives and what it must return.
 * The worker takes the job over the worker API, so this step names the work, never the worker or its credentials.
 */
export function ExternalTaskInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const topic = cfg<string>(node, 'topic') ?? ''
  const lease = cfg<number>(node, 'leaseSeconds')
  const mapping = (cfg<Record<string, string>>(node, 'inputMapping') ?? {}) as Record<string, string>
  const result = (cfg<PayloadField[]>(node, 'result') ?? []) as PayloadField[]
  const patch = (p: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, p)
  // The worker receives single values that exist whenever the step starts.
  const candidates = availableFields(def, node.id, connections).filter((f) => f.type !== 'array')
  const entries = Object.entries(mapping)
  const topicOk = topic === '' || TOPIC.test(topic)
  const setMapping = (next: Record<string, string>) => patch({ inputMapping: next })
  const renameInput = (from: string, to: string) => setMapping(Object.fromEntries(entries.map(([k, v]) => [k === from ? to : k, v])))
  const setResult = (next: PayloadField[]) => patch({ result: next })
  const change = (index: number, p: Partial<PayloadField>) => setResult(result.map((f, i) => (i === index ? { ...f, ...p } : f)))

  return <>
    <InspectorSection title="Job">
      <FormField label="Topic" required id={`external-topic-${node.id}`} description="The kind of job. Workers ask for the topics they serve, for example address-check." error={topicOk ? undefined : 'Use lowercase letters and digits joined with dashes, like address-check.'}>
        {(ids) => <CommitInput id={ids.input} value={topic} maxLength={64} disabled={readOnly} aria-describedby={ids.describedBy} aria-invalid={!topicOk || undefined} onCommit={(v) => patch({ topic: v.trim() })} />}
      </FormField>
      <FormField label="Lease time (seconds)" optionalText id={`external-lease-${node.id}`} description="How long a worker may hold a job before it must report in again. 30 to 3600; the default is 120.">
        {(ids) => <CommitNumber id={ids.input} integer value={lease} disabled={readOnly} onCommit={(v) => patch({ leaseSeconds: v === undefined ? undefined : Math.max(30, Math.min(3600, v)) })} />}
      </FormField>
      <p className="text-sm text-muted-foreground">A worker that stops responding loses its job and it is offered again, up to the attempt limit set for the whole workflow. After that the request needs attention.</p>
    </InspectorSection>

    <InspectorSection title="What the worker receives">
      <p className="text-sm text-muted-foreground">Only these values are sent. Use values that always exist when this step starts.</p>
      {entries.length === 0 ? <p className="text-sm text-muted-foreground">Nothing yet.</p> : null}
      <ul className="space-y-3" aria-label="Values sent to the worker">
        {entries.map(([name, ref], i) => {
          const bad = name !== '' && !INPUT_NAME.test(name)
          return (
            <li key={i} className="space-y-2 rounded-control border border-border p-3" data-testid={`external-input-${i}`}>
              <FormField label="Name the worker sees" required id={`external-input-name-${node.id}-${i}`} error={bad ? 'Start with a letter; use letters, digits and underscores.' : undefined}>
                {(ids) => <CommitInput id={ids.input} value={name} maxLength={63} disabled={readOnly} aria-describedby={ids.describedBy} aria-invalid={bad || undefined} onCommit={(v) => renameInput(name, v.trim())} />}
              </FormField>
              <FormField label="Value" required id={`external-input-ref-${node.id}-${i}`}>
                {(ids) => <Select id={ids.input} value={ref} disabled={readOnly} onChange={(e) => setMapping({ ...mapping, [name]: e.target.value })}>
                  <option value="">Choose a value</option>
                  {ref && !candidates.some((f) => f.ref === ref) ? <option value={ref}>{ref} (not available here)</option> : null}
                  {candidates.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
                </Select>}
              </FormField>
              {readOnly ? null : <Button variant="secondary" aria-label={`Remove ${name || 'the value'}`} onClick={() => setMapping(Object.fromEntries(entries.filter((_, j) => j !== i)))}><Trash2 className="size-4" aria-hidden="true" /> Remove</Button>}
            </li>
          )
        })}
      </ul>
      {readOnly || entries.length >= 10 ? null : <Button variant="secondary" onClick={() => setMapping({ ...mapping, [`value${entries.length + 1}`]: candidates[0]?.ref ?? '' })}><Plus className="size-4" aria-hidden="true" /> Add a value to send</Button>}
    </InspectorSection>

    <InspectorSection title="What the worker must return">
      <p className="text-sm text-muted-foreground">Later steps can use these values. The worker is refused if it returns anything else. A value that is not required may be missing.</p>
      {result.length === 0 ? <p className="text-sm text-muted-foreground">No values yet.</p> : null}
      <ul className="space-y-3" aria-label="Values returned by the worker">
        {result.map((f, i) => {
          const dup = result.some((o, j) => j !== i && o.name === f.name)
          const bad = f.name !== '' && (!VALUE_NAME.test(f.name) || dup)
          return (
            <li key={i} className="space-y-2 rounded-control border border-border p-3" data-testid={`external-result-${i}`}>
              <FormField label="Value name" required id={`external-result-name-${node.id}-${i}`} error={bad ? (dup ? 'Each value needs its own name.' : 'Start with a lowercase letter; use letters and digits only.') : undefined}>
                {(ids) => <CommitInput id={ids.input} value={f.name} maxLength={64} disabled={readOnly} aria-describedby={ids.describedBy} aria-invalid={bad || undefined} onCommit={(v) => change(i, { name: v.trim() })} />}
              </FormField>
              <FormField label="Kind of value" id={`external-result-type-${node.id}-${i}`}>
                {(ids) => <Select id={ids.input} value={f.type} disabled={readOnly} onChange={(e) => change(i, { type: e.target.value as ValueType })}>
                  {(Object.keys(TYPE_LABEL) as ValueType[]).map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
                </Select>}
              </FormField>
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <Checkbox id={`external-result-required-${node.id}-${i}`} checked={f.required === true} disabled={readOnly} onCheckedChange={(checked) => change(i, { required: checked === true ? true : undefined })} />
                  <Label htmlFor={`external-result-required-${node.id}-${i}`}>The worker must return it</Label>
                </div>
                {readOnly ? null : <Button variant="secondary" aria-label={`Remove the value ${f.name || i + 1}`} onClick={() => setResult(result.filter((_, j) => j !== i))}><Trash2 className="size-4" aria-hidden="true" /> Remove</Button>}
              </div>
            </li>
          )
        })}
      </ul>
      {readOnly || result.length >= 10 ? null : <Button variant="secondary" onClick={() => setResult([...result, { name: `result${result.length + 1}`, type: 'string' }])}><Plus className="size-4" aria-hidden="true" /> Add a result value</Button>}
    </InspectorSection>
    <Advanced><StepIdField node={node} /></Advanced>
  </>
}
