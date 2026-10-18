import { Plus, Trash2 } from 'lucide-react'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { cfg, type Definition, type SubprocessDef, type WorkflowNode } from '../model/types'
import { Advanced, CommitInput, InspectorSection } from './controls'
import { StepIdField } from './SimpleInspectors'

/** Signal names follow the workflow schema (docs/build/S55). */
export const SIGNAL_PATTERN = /^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$/
/** Business error codes; ORQIX_ is reserved for the platform. */
export const ERROR_CODE_PATTERN = /^[A-Z][A-Z0-9_]{1,39}$/

/** Steps a section or handler can hold (the compiler checks the rest: WF_SUBPROCESS_STEP_KIND). */
const SECTION_KINDS = new Set(['approval', 'decision', 'branch', 'http', 'generateDocument', 'notification', 'wait', 'waitForSignal', 'sendSignal', 'raiseError', 'compensate'])
const HANDLER_KINDS = new Set(['approval', 'decision', 'branch', 'http', 'generateDocument', 'notification', 'wait', 'sendSignal', 'compensate', 'end'])

export function SignalInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const signal = cfg<string>(node, 'signal') ?? ''
  const invalid = signal !== '' && !SIGNAL_PATTERN.test(signal)
  return (
    <>
      <InspectorSection title="Basic">
        <FormField
          label="Signal"
          required
          id={`signal-${node.id}`}
          error={invalid ? 'Use lowercase letters and digits, for example goods-received.' : undefined}
          description={node.type === 'sendSignal'
            ? 'Every step and handler of this same request that waits for this signal continues. Other requests never receive it.'
            : 'The request waits here until this signal is sent in the request, by a "Send a signal" step or by an operator.'}
        >
          {(ids) => <CommitInput id={ids.input} data-field="config.signal" value={signal} maxLength={64} disabled={readOnly} aria-describedby={ids.describedBy} aria-invalid={invalid || undefined} onCommit={(v) => store.getState().updateNodeConfig(node.id, { signal: v.trim() })} />}
        </FormField>
      </InspectorSection>
      <Advanced>
        <StepIdField node={node} />
      </Advanced>
    </>
  )
}

export function RaiseErrorInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const code = cfg<string>(node, 'errorCode') ?? ''
  const invalid = code !== '' && (!ERROR_CODE_PATTERN.test(code) || code.startsWith('ORQIX_'))
  return (
    <>
      <InspectorSection title="Basic">
        <FormField
          label="Error code"
          required
          id={`error-code-${node.id}`}
          error={invalid ? 'Use capital letters, digits and underscores, for example SUPPLIER_BLOCKED. Codes starting with ORQIX_ are reserved.' : undefined}
          description="This step ends its path. The section's error route, or a handler that starts on this error, decides where the request continues."
        >
          {(ids) => <CommitInput id={ids.input} data-field="config.errorCode" value={code} maxLength={40} disabled={readOnly} aria-describedby={ids.describedBy} aria-invalid={invalid || undefined} onCommit={(v) => store.getState().updateNodeConfig(node.id, { errorCode: v.trim().toUpperCase() })} />}
        </FormField>
      </InspectorSection>
      <Advanced>
        <StepIdField node={node} />
      </Advanced>
    </>
  )
}

export function CompensateInspector({ node }: { node: WorkflowNode }) {
  const def = useStudio((s) => s.definition)
  const handlers = def.nodes.filter((n) => n.type === 'http' && n.config.compensation === 'automatic')
  return (
    <>
      <InspectorSection title="Basic">
        <p className="text-sm">Runs the automatic undo call of every call that completed in this request, the most recent first, then continues. Calls that failed or were never reached are not undone.</p>
        {handlers.length ? (
          <ul className="list-disc space-y-1 pl-5 text-sm" aria-label="Automatic undo calls">
            {handlers.map((h) => {
              const target = def.nodes.find((n) => n.id === h.config.compensates)
              return <li key={h.id}>{h.label} undoes {target?.label ?? String(h.config.compensates ?? '?')}</li>
            })}
          </ul>
        ) : <p className="text-sm text-danger">No call is marked as an automatic undo yet. Open a call to a system and choose what it undoes.</p>}
      </InspectorSection>
      <Advanced>
        <StepIdField node={node} />
      </Advanced>
    </>
  )
}

/** Sets or clears the automatic undo of a call; an automatic undo call keeps no paths of its own (S55). */
export function setUndo(def: Definition, nodeId: string, compensates: string | undefined, automatic: boolean): Definition {
  const next = structuredClone(def)
  const node = next.nodes.find((n) => n.id === nodeId)
  if (!node) return def
  if (compensates) node.config.compensates = compensates
  else delete node.config.compensates
  if (automatic && compensates) {
    node.config.compensation = 'automatic'
    next.transitions = next.transitions.filter((t) => t.from !== nodeId && t.to !== nodeId)
  } else {
    delete node.config.compensation
  }
  return next
}

export function UndoFields({ node }: { node: WorkflowNode }) {
  const def = useStudio((s) => s.definition)
  const { store, readOnly } = useStudioContext()
  const target = cfg<string>(node, 'compensates') ?? ''
  const automatic = node.config.compensation === 'automatic'
  const calls = def.nodes.filter((n) => n.type === 'http' && n.id !== node.id && n.config.compensation !== 'automatic')
  return (
    <InspectorSection title="Undo">
      <FormField label="This call undoes" optionalText id={`undo-target-${node.id}`} description="Choose the earlier call that this call reverses.">
        {(ids) => (
          <Select id={ids.input} value={target} disabled={readOnly} aria-describedby={ids.describedBy} onChange={(e) => store.getState().apply((d) => setUndo(d, node.id, e.target.value || undefined, automatic))}>
            <option value="">Nothing</option>
            {calls.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
          </Select>
        )}
      </FormField>
      <div className="flex items-start gap-3">
        <Checkbox id={`undo-auto-${node.id}`} checked={automatic} disabled={readOnly || !target} onCheckedChange={(v) => store.getState().apply((d) => setUndo(d, node.id, target || undefined, v === true))} />
        <div>
          <Label htmlFor={`undo-auto-${node.id}`}>Run automatically from an &ldquo;Undo completed calls&rdquo; step</Label>
          <p className="text-xs text-muted-foreground">It then has no paths of its own and runs only when the call it undoes completed. Turning this on removes its paths.</p>
        </div>
      </div>
    </InspectorSection>
  )
}

function newId(def: Definition, prefix: string): string {
  const taken = new Set([...def.nodes.map((n) => n.id), ...def.transitions.map((t) => t.id), ...(def.subprocesses ?? []).map((s) => s.id)])
  let n = 1
  while (taken.has(`${prefix}${n}`)) n++
  return `${prefix}${n}`
}

/** Pure edit helpers (unit tested). */
export function addSubprocess(def: Definition, type: SubprocessDef['type']): Definition {
  const list = def.subprocesses ?? []
  const entry: SubprocessDef = type === 'embedded'
    ? { id: newId(def, 'section'), label: `Section ${list.length + 1}`, type, nodes: [] }
    : { id: newId(def, 'handler'), label: `Handler ${list.length + 1}`, type, nodes: [], trigger: { type: 'signal', signal: 'signal-name' }, interrupting: false }
  return { ...def, subprocesses: [...list, entry] }
}

export function patchSubprocess(def: Definition, id: string, change: Partial<SubprocessDef>): Definition {
  const list = (def.subprocesses ?? []).map((s) => {
    if (s.id !== id) return s
    const next: SubprocessDef = { ...s, ...change }
    for (const key of Object.keys(next) as Array<keyof SubprocessDef>) if (next[key] === undefined) delete next[key]
    if (next.trigger?.type === 'error') next.interrupting = true
    return next
  })
  return { ...def, subprocesses: list }
}

export function removeSubprocess(def: Definition, id: string): Definition {
  const list = (def.subprocesses ?? []).filter((s) => s.id !== id)
  if (list.length) return { ...def, subprocesses: list }
  const { subprocesses: _removed, ...rest } = def
  return rest
}

/** Sections and handlers (docs/build/S55), authored like case stages: a scope over existing steps. */
export function SubprocessesInspector() {
  const def = useStudio((s) => s.definition)
  const { store, readOnly } = useStudioContext()
  const list = def.subprocesses ?? []
  const patch = (id: string, change: Partial<SubprocessDef>) => store.getState().apply((d) => patchSubprocess(d, id, change))
  const outside = (s: SubprocessDef) => def.nodes.filter((n) => !s.nodes.includes(n.id) && n.type !== 'start')
  return (
    <InspectorSection title="Sections and handlers">
      <p className="text-xs">A section groups steps entered through one step; it can have a time limit and an error route. A handler starts on an error, a signal of this request or a time after the request started.</p>
      {!list.length ? <p className="text-sm">No sections or handlers.</p> : null}
      {list.map((s) => {
        const allowed = s.type === 'embedded' ? SECTION_KINDS : HANDLER_KINDS
        return (
          <fieldset key={s.id} className="space-y-2 rounded border p-3" disabled={readOnly}>
            <legend className="text-sm font-semibold">{s.type === 'embedded' ? 'Section' : 'Handler'}: {s.label}</legend>
            <Button variant="ghost" size="icon" aria-label={`Remove ${s.label}`} disabled={readOnly} onClick={() => store.getState().apply((d) => removeSubprocess(d, s.id))}><Trash2 /></Button>
            <FormField id={`scope-label-${s.id}`} label="Name" required>{(ids) => <CommitInput id={ids.input} value={s.label} maxLength={200} disabled={readOnly} onCommit={(label) => patch(s.id, { label })} />}</FormField>
            {s.type === 'embedded' ? (
              <>
                <FormField id={`scope-timeout-${s.id}`} label="Time limit" optionalText description="For example P5D. When it passes, the open work inside is cancelled.">{(ids) => <CommitInput id={ids.input} value={s.timeoutAfter ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => patch(s.id, { timeoutAfter: v.trim() || undefined })} />}</FormField>
                <FormField id={`scope-ontimeout-${s.id}`} label="When the time limit passes, continue at" optionalText>
                  {(ids) => <Select id={ids.input} value={s.onTimeout ?? ''} disabled={readOnly} onChange={(e) => patch(s.id, { onTimeout: e.target.value || undefined })}><option value="">Not set</option>{outside(s).map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}</Select>}
                </FormField>
                <FormField id={`scope-onerror-${s.id}`} label="When a step inside raises an error, continue at" optionalText>
                  {(ids) => <Select id={ids.input} value={s.onError?.to ?? ''} disabled={readOnly} onChange={(e) => patch(s.id, { onError: e.target.value ? { ...s.onError, to: e.target.value } : undefined })}><option value="">Not set</option>{outside(s).map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}</Select>}
                </FormField>
                {s.onError ? <FormField id={`scope-errorcode-${s.id}`} label="Only for the error code" optionalText>{(ids) => <CommitInput id={ids.input} value={s.onError?.errorCode ?? ''} disabled={readOnly} onCommit={(v) => patch(s.id, { onError: { to: s.onError!.to, ...(v.trim() ? { errorCode: v.trim().toUpperCase() } : {}) } })} />}</FormField> : null}
              </>
            ) : (
              <>
                <FormField id={`scope-trigger-${s.id}`} label="Starts on">
                  {(ids) => <Select id={ids.input} value={s.trigger?.type ?? 'signal'} disabled={readOnly} onChange={(e) => {
                    const type = e.target.value as 'error' | 'signal' | 'timer'
                    patch(s.id, { trigger: type === 'signal' ? { type, signal: 'signal-name' } : type === 'timer' ? { type, after: 'P1D' } : { type } })
                  }}><option value="signal">A signal of this request</option><option value="timer">A time after the request started</option><option value="error">An error raised in the request</option></Select>}
                </FormField>
                {s.trigger?.type === 'signal' ? <FormField id={`scope-signal-${s.id}`} label="Signal" required>{(ids) => <CommitInput id={ids.input} value={s.trigger?.signal ?? ''} disabled={readOnly} onCommit={(v) => patch(s.id, { trigger: { type: 'signal', signal: v.trim() } })} />}</FormField> : null}
                {s.trigger?.type === 'timer' ? <FormField id={`scope-after-${s.id}`} label="After" required description="For example P3D.">{(ids) => <CommitInput id={ids.input} value={s.trigger?.after ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => patch(s.id, { trigger: { type: 'timer', after: v.trim() } })} />}</FormField> : null}
                {s.trigger?.type === 'error' ? <FormField id={`scope-code-${s.id}`} label="Only for the error code" optionalText>{(ids) => <CommitInput id={ids.input} value={s.trigger?.errorCode ?? ''} disabled={readOnly} onCommit={(v) => patch(s.id, { trigger: { type: 'error', ...(v.trim() ? { errorCode: v.trim().toUpperCase() } : {}) } })} />}</FormField> : null}
                <div className="flex items-center gap-2">
                  <Checkbox id={`scope-interrupting-${s.id}`} checked={s.interrupting === true} disabled={readOnly || s.trigger?.type === 'error'} onCheckedChange={(v) => patch(s.id, { interrupting: v === true })} />
                  <Label htmlFor={`scope-interrupting-${s.id}`}>Stop the rest of the request (its steps must end at a Finish step)</Label>
                </div>
              </>
            )}
            <p className="text-xs">Steps in this {s.type === 'embedded' ? 'section' : 'handler'}:</p>
            {def.nodes.filter((n) => allowed.has(n.type)).map((n) => {
              const other = list.find((o) => o.id !== s.id && o.nodes.includes(n.id))
              const id = `scope-${s.id}-node-${n.id}`
              return (
                <div key={n.id} className="flex items-center gap-2">
                  <Checkbox id={id} checked={s.nodes.includes(n.id)} disabled={readOnly || Boolean(other)} onCheckedChange={(checked) => patch(s.id, { nodes: checked ? [...new Set([...s.nodes, n.id])] : s.nodes.filter((x) => x !== n.id) })} />
                  <Label htmlFor={id}>{n.label}{other ? ` (in ${other.label})` : ''}</Label>
                </div>
              )
            })}
          </fieldset>
        )
      })}
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" disabled={readOnly || list.length >= 10} onClick={() => store.getState().apply((d) => addSubprocess(d, 'embedded'))}><Plus /> Add a section</Button>
        <Button variant="secondary" disabled={readOnly || list.length >= 10} onClick={() => store.getState().apply((d) => addSubprocess(d, 'event'))}><Plus /> Add a handler</Button>
      </div>
    </InspectorSection>
  )
}
