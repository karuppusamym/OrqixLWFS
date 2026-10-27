import { Plus, Trash2 } from 'lucide-react'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { messageWaitPreview } from '../model/summary'
import { cfg, type WorkflowNode } from '../model/types'
import { Advanced, CommitInput, DurationPreview, InspectorSection } from './controls'
import { StepIdField } from './SimpleInspectors'

/** Message names are lowercase letters and digits in kebab-case or camelCase (schema pattern). */
export const MESSAGE_NAME = /^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$/
const VALUE_NAME = /^[a-z][A-Za-z0-9]{0,63}$/

type ValueType = 'string' | 'number' | 'integer' | 'boolean' | 'date'
export interface PayloadField { name: string; type: ValueType; title?: string; required?: boolean }

const TYPE_LABEL: Record<ValueType, string> = { string: 'Text', number: 'Number', integer: 'Whole number', boolean: 'True or false', date: 'Date' }

/** Wait for a message (S16): which message, which request field identifies the request, an optional time limit, the values it may carry. */
export function MessageInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const name = cfg<string>(node, 'message') ?? ''
  const field = cfg<string>(node, 'correlationField') ?? ''
  const timeout = cfg<string>(node, 'timeoutAfter') ?? ''
  const payload = (cfg<PayloadField[]>(node, 'payload') ?? []) as PayloadField[]
  const patch = (p: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, p)
  const candidates = availableFields(def, node.id, connections).filter((f) => f.type === 'string' || f.type === 'integer')
  const chosen = candidates.find((f) => f.ref === field)
  const nameOk = name === '' || MESSAGE_NAME.test(name)
  const setPayload = (next: PayloadField[]) => patch({ payload: next })
  const change = (index: number, p: Partial<PayloadField>) => setPayload(payload.map((f, i) => (i === index ? { ...f, ...p } : f)))

  return <>
    <InspectorSection title="Message">
      <FormField label="Message name" required id={`message-name-${node.id}`} description="Lowercase letters and digits joined with dashes, for example payment-received. The sending system uses this name." error={nameOk ? undefined : 'Use lowercase letters and digits joined with dashes, like payment-received.'}>
        {(ids) => <CommitInput id={ids.input} value={name} maxLength={64} disabled={readOnly} aria-describedby={ids.describedBy} aria-invalid={!nameOk || undefined} onCommit={(v) => patch({ message: v.trim() })} />}
      </FormField>
      <FormField label="Which field identifies the request?" required id={`message-field-${node.id}`} description="The message must carry this value, for example the order number, so it reaches the right request.">
        {(ids) => <Select id={ids.input} value={field} disabled={readOnly} aria-describedby={ids.describedBy} onChange={(e) => patch({ correlationField: e.target.value })}>
          <option value="">Choose a field</option>
          {field && !chosen ? <option value={field}>{field} (not a text or whole number)</option> : null}
          {candidates.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
        </Select>}
      </FormField>
      {candidates.length === 0 ? <p className="text-sm text-muted-foreground">There is no text or number question yet. Add one, such as an order number, to the request form first.</p> : null}
      <p role="status" data-testid="message-wait-preview" className="text-sm text-muted-foreground">{messageWaitPreview(def, node.config, connections)}</p>
    </InspectorSection>

    <InspectorSection title="Time limit">
      <FormField label="Give up after" optionalText id={`message-timeout-${node.id}`} description="For example P2D or PT12H. When no message arrives in time, the request follows Timed out.">
        {(ids) => <CommitInput id={ids.input} value={timeout} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => store.getState().apply((d) => ({
          ...d,
          nodes: d.nodes.map((n) => {
            if (n.id !== node.id) return n
            const config = { ...n.config }
            if (v.trim()) config.timeoutAfter = v.trim()
            else delete config.timeoutAfter
            return { ...n, config }
          }),
          transitions: v.trim() ? d.transitions : d.transitions.filter((t) => t.from !== node.id || t.on !== 'timedOut'),
        }))} />}
      </FormField>
      {timeout ? <DurationPreview text={timeout} verb="waits" /> : <p className="text-sm text-muted-foreground">Without a time limit the request waits until the message arrives or someone cancels it.</p>}
    </InspectorSection>

    <InspectorSection title="Values the message may carry">
      <p className="text-sm text-muted-foreground">Later steps can use these values. A value that is not required may be missing.</p>
      {payload.length === 0 ? <p className="text-sm text-muted-foreground">No values yet.</p> : null}
      <ul className="space-y-3" aria-label="Message values">
        {payload.map((f, i) => {
          const dup = payload.some((o, j) => j !== i && o.name === f.name)
          const bad = f.name !== '' && (!VALUE_NAME.test(f.name) || dup)
          return (
            <li key={i} className="space-y-2 rounded-control border border-border p-3" data-testid={`message-value-${i}`}>
              <FormField label="Value name" required id={`message-value-name-${node.id}-${i}`} error={bad ? (dup ? 'Each value needs its own name.' : 'Start with a lowercase letter; use letters and digits only.') : undefined}>
                {(ids) => <CommitInput id={ids.input} value={f.name} maxLength={64} disabled={readOnly} aria-describedby={ids.describedBy} aria-invalid={bad || undefined} onCommit={(v) => change(i, { name: v.trim() })} />}
              </FormField>
              <FormField label="Kind of value" id={`message-value-type-${node.id}-${i}`}>
                {(ids) => <Select id={ids.input} value={f.type} disabled={readOnly} onChange={(e) => change(i, { type: e.target.value as ValueType })}>
                  {(Object.keys(TYPE_LABEL) as ValueType[]).map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
                </Select>}
              </FormField>
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <Checkbox id={`message-value-required-${node.id}-${i}`} checked={f.required === true} disabled={readOnly} onCheckedChange={(checked) => change(i, { required: checked === true ? true : undefined })} />
                  <Label htmlFor={`message-value-required-${node.id}-${i}`}>The message must carry it</Label>
                </div>
                {readOnly ? null : <Button variant="secondary" aria-label={`Remove the value ${f.name || i + 1}`} onClick={() => setPayload(payload.filter((_, j) => j !== i))}><Trash2 className="size-4" aria-hidden="true" /> Remove</Button>}
              </div>
            </li>
          )
        })}
      </ul>
      {readOnly || payload.length >= 10 ? null : <Button variant="secondary" onClick={() => setPayload([...payload, { name: `value${payload.length + 1}`, type: 'string' }])}><Plus className="size-4" aria-hidden="true" /> Add a value</Button>}
    </InspectorSection>
    <ExternalSystemSection node={node} payload={payload} />
    <Advanced><StepIdField node={node} /></Advanced>
  </>
}

/**
 * EX10 (docs/build/S98): the wait may be answered by an external system with a one-time callback token. The panel shows
 * the contract the external system gets; tokens themselves are issued on the request page by an operator.
 */
function ExternalSystemSection({ node, payload }: { node: WorkflowNode; payload: PayloadField[] }) {
  const { store, readOnly } = useStudioContext()
  const external = cfg<{ tokenValidFor?: string }>(node, 'externalCallback')
  const validFor = external?.tokenValidFor ?? ''
  const setExternal = (value: { tokenValidFor: string } | undefined) => store.getState().apply((d) => ({
    ...d,
    nodes: d.nodes.map((n) => {
      if (n.id !== node.id) return n
      const config = { ...n.config }
      if (value) config.externalCallback = value
      else delete config.externalCallback
      return { ...n, config }
    }),
  }))
  return (
    <InspectorSection title="External system">
      <div className="flex items-center gap-2">
        <Checkbox id={`message-external-${node.id}`} checked={external !== undefined} disabled={readOnly}
          onCheckedChange={(checked) => setExternal(checked === true ? { tokenValidFor: validFor || 'P7D' } : undefined)} />
        <Label htmlFor={`message-external-${node.id}`}>Answered by an external system</Label>
      </div>
      {external === undefined ? <p className="text-sm text-muted-foreground">Turn this on when another system answers this wait directly with a callback token instead of sending a named message.</p> : <>
        <FormField label="Token is valid for" required id={`message-external-ttl-${node.id}`} description="From 1 minute (PT1M) to 30 days (P30D), counted from when an operator issues the token.">
          {(ids) => <CommitInput id={ids.input} value={validFor} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => setExternal({ tokenValidFor: v.trim() })} />}
        </FormField>
        {validFor ? <DurationPreview text={validFor} verb="allows" /> : null}
        <div className="space-y-1 rounded-control border border-border p-3 text-sm" data-testid="callback-contract" aria-label="Contract for the external system">
          <p className="font-medium">What the external system gets</p>
          <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
            <li>A token, shown once to the operator who issues it on the request page. Orqix keeps only a hash.</li>
            <li>Poll: <code>GET …/callbacks/wait</code> with the header <code>X-Orqix-Callback-Token</code>; the answer is waiting, resumed, expired or cancelled.</li>
            <li>Resume once: <code>POST …/callbacks/wait/resume</code> with an <code>Idempotency-Key</code> and <code>{'{"result": {…}}'}</code>.</li>
            <li>Result values: {payload.length === 0 ? 'none (an empty result)' : payload.map((f) => `${f.name} (${TYPE_LABEL[f.type]}${f.required ? ', required' : ''})`).join(', ')}.</li>
          </ul>
          <p className="text-muted-foreground">This is not the same as resuming a suspended request: a suspended request refuses the answer until an operator resumes it.</p>
        </div>
      </>}
    </InspectorSection>
  )
}
