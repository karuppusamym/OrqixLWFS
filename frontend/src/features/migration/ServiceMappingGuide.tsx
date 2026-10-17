import { useState } from 'react'
import type { components } from '@/api/schema'
import { useConnection, useConnections } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { QueryError } from '@/components/common/QueryError'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/input'
import { connectionDefinition } from '@/features/studio/connectionSchema'

type Hints = NonNullable<NonNullable<components['schemas']['CompatibilityReport']['mappingHints']>['services']>
interface Operation { label: string; method?: string; path?: string; maxTimeoutSeconds: number; requestSchema?: { properties?: Record<string, unknown> }; responseSchema?: { properties?: Record<string, unknown> } }

const pointerKey = (pointer: string) => pointer.split('/')[1]?.replace(/~1/g, '/').replace(/~0/g, '~') ?? ''

/** Build explicit draft mappings from an approved contract; no endpoint is called. */
export function ServiceMappingGuide({ tasks, value, onChange, hints = {} }: { tasks: string[]; value: string; onChange: (value: string) => void; hints?: Hints }) {
  const { workspace } = useWorkspace()
  const list = useConnections(workspace.id)
  const [task, setTask] = useState(tasks[0] ?? '')
  const [key, setKey] = useState('')
  const [operation, setOperation] = useState('')
  const [error, setError] = useState<string | null>(null)
  const detail = useConnection(workspace.id, key)
  const definition = connectionDefinition(detail.data, undefined)
  const operations = definition?.definition.operations as Record<string, Operation> | undefined
  const hint = hints[task]
  const matches = (op: Operation) => !!hint?.method && !!hint.urlPath && op.method?.toUpperCase() === hint.method.toUpperCase() && op.path === hint.urlPath
  const best = Object.entries(operations ?? {}).find(([, op]) => matches(op))?.[0]
  // Preselect the operation that makes the same call as the source connector (same method and path), when there is one.
  const selected = operation || best || ''
  const chosen = operations?.[selected]
  const methodDiffers = !!(hint?.method && chosen?.method && chosen.method.toUpperCase() !== hint.method.toUpperCase())
  const add = () => {
    try {
      const current = JSON.parse(value) as Record<string, unknown>
      if (!current || typeof current !== 'object' || Array.isArray(current)) throw new Error('Mappings must be a JSON object.')
      const doc = operations?.[selected]
      if (!doc || !definition) return
      const requestKeys = Object.keys(doc.requestSchema?.properties ?? {})
      const responseKeys = Object.keys(doc.responseSchema?.properties ?? {}).filter(k => /^[a-z][A-Za-z0-9]{0,63}$/.test(k))
      // Names derived from the source (body fields read from variables, result pointers) win over the plain defaults.
      const inputMapping = Object.fromEntries(requestKeys.map(k => [k, `/input/${hint?.inputFields?.[k] ?? k}`]))
      const resultMapping: Record<string, string> = Object.fromEntries(responseKeys.map(k => [k, `/${k.replace(/~/g, '~0').replace(/\//g, '~1')}`]))
      for (const [name, pointer] of Object.entries(hint?.resultFields ?? {})) if (responseKeys.includes(pointerKey(pointer))) resultMapping[name] = pointer
      onChange(JSON.stringify({ ...current, [task]: { connectionRef: key, definitionVersion: definition.version, operation: selected, inputMapping, resultMapping,
        timeoutSeconds: Math.min(10, doc.maxTimeoutSeconds) } }, null, 2))
      setError(null)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Check the mapping JSON.') }
  }
  return <fieldset className="space-y-3 rounded-control border border-border p-4"><legend className="px-1 font-semibold">Map a source service to an approved operation</legend>
    <Label htmlFor="migration-service-task">Source service task</Label><Select id="migration-service-task" value={task} onChange={e => { setTask(e.target.value); setOperation('') }}>{tasks.map(t => <option key={t}>{t}</option>)}</Select>
    {hint ? <div aria-label="Original call" className="space-y-1 rounded-control bg-muted p-3 text-sm">
      <p className="font-semibold">{hint.kind === 'httpJson' ? 'Original call (read-only)' : hint.kind === 'jobWorker' ? 'Original job worker (read-only)' : 'Original connector (read-only)'}</p>
      {hint.kind === 'httpJson' ? <p>{hint.method ?? 'Method not set'} {hint.urlHost ?? ''}{hint.urlPath ?? (hint.urlComputed ? ' (address is calculated in the source)' : '')}</p> : null}
      {hint.jobType ? <p>Job type: <code>{hint.jobType}</code></p> : null}
      {hint.suggestedOperation ? <p>Suggested operation name: <code>{hint.suggestedOperation}</code></p> : null}
      {hint.authentication ? <p>Source sign-in: {hint.authentication} (not copied; the connection owns credentials)</p> : null}
      {Object.keys(hint.inputFields ?? {}).length ? <p>Request values read from: {Object.entries(hint.inputFields ?? {}).map(([name, field]) => `${name} ← ${field}`).join(', ')}</p> : null}
      {Object.keys(hint.resultFields ?? {}).length ? <p>Result values used: {Object.entries(hint.resultFields ?? {}).map(([name, pointer]) => `${name} ← ${pointer}`).join(', ')}</p> : null}
      {hint.unmapped?.length ? <p>Not derived, check by hand: {hint.unmapped.join('; ')}</p> : null}
      {hint.replacement ? <p>{hint.replacement}</p> : null}
    </div> : null}
    <Label htmlFor="migration-connection">Target connection</Label><Select id="migration-connection" value={key} onChange={e => { setKey(e.target.value); setOperation('') }}><option value="">Choose connection</option>{list.data?.items.map(c => <option key={c.key} value={c.key}>{c.name}</option>)}</Select>
    <Label htmlFor="migration-operation">Target operation</Label><Select id="migration-operation" value={selected} disabled={!definition} onChange={e => setOperation(e.target.value)}><option value="">Choose operation</option>{Object.entries(operations ?? {}).map(([name, op]) => <option key={name} value={name}>{op.label}{op.method && op.path ? ` (${op.method} ${op.path})` : ''}{matches(op) ? ' - same call as the source' : ''}</option>)}</Select>
    {methodDiffers ? <p role="alert" className="text-sm">The source called {hint?.method} but this operation uses {chosen?.method}. Check that it does the same work.</p> : null}
    <p className="text-sm text-muted-foreground">Adds explicit request-field mappings using contract types and pins version {definition?.version ?? '—'}. Review field references and kept result names below. No external system is called.</p>
    <Button type="button" disabled={!selected} onClick={add}>Apply operation mapping</Button>
    {error ? <p role="alert">{error}</p> : null}
    {list.isError ? <QueryError error={list.error} onRetry={() => void list.refetch()} what="connections" /> : null}
    {detail.isError ? <QueryError error={detail.error} onRetry={() => void detail.refetch()} what="the connection contract" /> : null}
  </fieldset>
}
