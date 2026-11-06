import { useMemo } from 'react'
import { useConnection, useConnections } from '@/api/queries'
import { FormField } from '@/components/common/FormField'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { humanize } from '@/lib/text'
import { useStudio, useStudioContext } from '../StudioContext'
import { connectionDefinition } from '../connectionSchema'
import { availableFields, chipLabel, type FieldInfo } from '../model/fieldRefs'
import { syncConnectionDependencies } from '../model/ops'
import { describe as describeSeconds, seconds } from '../model/pollSchedule'
import { cfg, type Condition, type WorkflowNode } from '../model/types'
import { ConditionEditor } from './ConditionEditor'
import { InspectorSection } from './controls'

interface Fallback {
  connectionRef: string
  operation: string
  inputMapping: Record<string, string>
  resultMapping: Record<string, string>
  timeoutSeconds: number
  every: string
  until: unknown
  jitterPercent?: number
  maxChecks?: number
}

interface OperationDoc {
  label: string
  effectClass: string
  maxTimeoutSeconds: number
  requestSchema?: { required?: string[]; properties: Record<string, { type: string }> }
  responseSchema: { properties?: Record<string, { type: string; enum?: string[] }> }
}

const EVERY = ['PT10M', 'PT30M', 'PT1H', 'PT2H', 'PT6H', 'P1D']

/**
 * Callback with a polling safety net (docs/build/S129, CG-W03): while the step waits for its message, a read-only
 * operation is checked at a slow interval; when the "done" condition holds, the wait ends with the mapped values as the
 * message's values. Whichever comes first wins.
 */
export function FallbackCheckFields({ node, payload }: { node: WorkflowNode; payload: Array<{ name: string; type: string; required?: boolean }> }) {
  const { store, wsId, readOnly, connections: lookup } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const fallback = cfg<Fallback>(node, 'fallbackCheck')
  const list = useConnections(wsId)
  const detail = useConnection(wsId, fallback?.connectionRef || null)
  const pinned = def.dependencies.connections.find((c) => c.key === fallback?.connectionRef)?.definitionVersion
  const record = connectionDefinition(detail.data, pinned)
  const operations = (record?.definition.operations ?? {}) as Record<string, OperationDoc>
  const readOps = Object.entries(operations).filter(([, o]) => o.effectClass === 'readOnly')
  const operation = fallback ? operations[fallback.operation] : undefined
  const available = useMemo(() => availableFields(def, node.id, lookup), [def, node.id, lookup])
  const fields: FieldInfo[] = useMemo(() => [
    ...payload.map((f) => ({ ref: `/steps/${node.id}/${f.name}`, label: humanize(f.name), group: node.label, type: f.type as FieldInfo['type'] })),
    ...available.filter((f) => f.type !== 'array' && f.type !== 'date'),
  ], [payload, available, node.id, node.label])

  const set = (next: Fallback | undefined) => store.getState().apply((d) => {
    const nodes = d.nodes.map((n) => {
      if (n.id !== node.id) return n
      const config = { ...n.config }
      if (next) config.fallbackCheck = next
      else delete config.fallbackCheck
      return { ...n, config }
    })
    const key = next?.connectionRef
    const latest = key ? list.data?.items.find((c) => c.key === key)?.latestVersion : undefined
    return syncConnectionDependencies({ ...d, nodes }, key && latest && !d.dependencies.connections.some((c) => c.key === key) ? { [key]: latest } : {})
  })
  const patch = (p: Partial<Fallback>) => fallback && set({ ...fallback, ...p })
  const first = payload[0]

  return (
    <InspectorSection title="Safety net">
      <div className="flex items-start gap-3">
        <Checkbox id={`fallback-on-${node.id}`} data-field="config.fallbackCheck" checked={fallback !== undefined} disabled={readOnly}
          onCheckedChange={(v) => set(v === true ? {
            connectionRef: '', operation: '', inputMapping: {}, resultMapping: {}, timeoutSeconds: 10, every: 'PT2H',
            until: { fieldRef: first ? `/steps/${node.id}/${first.name}` : '', operator: 'eq', value: '' },
          } : undefined)} />
        <div>
          <Label htmlFor={`fallback-on-${node.id}`} className="font-medium">Also check the other system now and then</Label>
          <p className="text-xs text-muted-foreground">Webhooks get lost. A slow check finds the work done when the message never comes; whichever comes first ends the wait.</p>
        </div>
      </div>
      {fallback ? (
        <div className="space-y-4" data-testid="fallback-settings">
          <FormField label="Connection" required id={`fallback-conn-${node.id}`}>
            {(ids) => (
              <Select id={ids.input} data-field="config.fallbackCheck.connectionRef" value={fallback.connectionRef} disabled={readOnly}
                onChange={(e) => patch({ connectionRef: e.target.value, operation: '', inputMapping: {}, resultMapping: {} })}>
                <option value="">Choose a connection</option>
                {(list.data?.items ?? []).filter((c) => c.kind !== 'agent').map((c) => <option key={c.key} value={c.key}>{c.name}</option>)}
              </Select>
            )}
          </FormField>
          <FormField label="Read-only operation" required id={`fallback-op-${node.id}`} description="Only operations that just read can be checked repeatedly.">
            {(ids) => (
              <Select id={ids.input} data-field="config.fallbackCheck.operation" value={fallback.operation} disabled={readOnly || !record}
                onChange={(e) => patch({ operation: e.target.value, inputMapping: {}, resultMapping: {},
                  timeoutSeconds: Math.min(fallback.timeoutSeconds, operations[e.target.value]?.maxTimeoutSeconds ?? 30) })}>
                <option value="">{fallback.connectionRef ? 'Choose an operation' : 'Choose a connection first'}</option>
                {readOps.map(([name, o]) => <option key={name} value={name}>{o.label}</option>)}
              </Select>
            )}
          </FormField>
          {operation?.requestSchema ? (
            <fieldset className="space-y-2">
              <legend className="text-sm font-semibold">What to send</legend>
              {Object.entries(operation.requestSchema.properties).map(([target]) => (
                <div key={target} className="space-y-1">
                  <Label htmlFor={`fallback-in-${node.id}-${target}`}>{humanize(target)}</Label>
                  <Select id={`fallback-in-${node.id}-${target}`} value={fallback.inputMapping[target] ?? ''} disabled={readOnly}
                    onChange={(e) => {
                      const next = { ...fallback.inputMapping }
                      if (e.target.value) next[target] = e.target.value
                      else delete next[target]
                      patch({ inputMapping: next })
                    }}>
                    <option value="">Do not send</option>
                    {available.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
                  </Select>
                </div>
              ))}
            </fieldset>
          ) : null}
          {operation && payload.length > 0 ? (
            <fieldset className="space-y-2">
              <legend className="text-sm font-semibold">Message values from the answer</legend>
              {payload.map((f) => (
                <div key={f.name} className="space-y-1">
                  <Label htmlFor={`fallback-out-${node.id}-${f.name}`}>{humanize(f.name)}{f.required ? ' (required)' : ''}</Label>
                  <Select id={`fallback-out-${node.id}-${f.name}`} value={fallback.resultMapping[f.name] ?? ''} disabled={readOnly}
                    onChange={(e) => {
                      const next = { ...fallback.resultMapping }
                      if (e.target.value) next[f.name] = e.target.value
                      else delete next[f.name]
                      patch({ resultMapping: next })
                    }}>
                    <option value="">Not from the answer</option>
                    {Object.keys(operation.responseSchema.properties ?? {}).map((prop) => <option key={prop} value={`/${prop}`}>{prop}</option>)}
                  </Select>
                </div>
              ))}
            </fieldset>
          ) : null}
          <FormField label="Done when" required id={`fallback-until-${node.id}`}>
            {() => (
              <ConditionEditor idBase={`fallback-until-${node.id}`} dataFieldBase="config.fallbackCheck.until" allowSets
                condition={fallback.until as Condition} fields={fields} disabled={readOnly} onChange={(until) => patch({ until })} />
            )}
          </FormField>
          <FormField label="Check every" id={`fallback-every-${node.id}`} description={`From 1 minute to 1 day. ${seconds(fallback.every) > 0 ? describeSeconds(seconds(fallback.every)) : ''}`}>
            {(ids) => (
              <Select id={ids.input} data-field="config.fallbackCheck.every" value={EVERY.includes(fallback.every) ? fallback.every : ''} disabled={readOnly}
                onChange={(e) => patch({ every: e.target.value })}>
                {!EVERY.includes(fallback.every) ? <option value="">{fallback.every}</option> : null}
                {EVERY.map((p) => <option key={p} value={p}>{describeSeconds(seconds(p))}</option>)}
              </Select>
            )}
          </FormField>
        </div>
      ) : null}
    </InspectorSection>
  )
}
