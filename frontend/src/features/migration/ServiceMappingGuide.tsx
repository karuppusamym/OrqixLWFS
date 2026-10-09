import { useState } from 'react'
import { useConnection, useConnections } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { QueryError } from '@/components/common/QueryError'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/input'
import { connectionDefinition } from '@/features/studio/connectionSchema'

interface Operation { label: string; maxTimeoutSeconds: number; requestSchema?: { properties?: Record<string, unknown> }; responseSchema?: { properties?: Record<string, unknown> } }

/** Build explicit draft mappings from an approved contract; no endpoint is called. */
export function ServiceMappingGuide({ tasks, value, onChange }: { tasks: string[]; value: string; onChange: (value: string) => void }) {
  const { workspace } = useWorkspace()
  const list = useConnections(workspace.id)
  const [task, setTask] = useState(tasks[0] ?? '')
  const [key, setKey] = useState('')
  const [operation, setOperation] = useState('')
  const [error, setError] = useState<string | null>(null)
  const detail = useConnection(workspace.id, key)
  const definition = connectionDefinition(detail.data, undefined)
  const operations = definition?.definition.operations as Record<string, Operation> | undefined
  const add = () => {
    try {
      const current = JSON.parse(value) as Record<string, unknown>
      if (!current || typeof current !== 'object' || Array.isArray(current)) throw new Error('Mappings must be a JSON object.')
      const doc = operations?.[operation]
      if (!doc || !definition) return
      onChange(JSON.stringify({ ...current, [task]: { connectionRef: key, definitionVersion: definition.version, operation,
        inputMapping: Object.fromEntries(Object.keys(doc.requestSchema?.properties ?? {}).map(k => [k, `/input/${k}`])),
        resultMapping: Object.fromEntries(Object.keys(doc.responseSchema?.properties ?? {}).filter(k => /^[a-z][A-Za-z0-9]{0,63}$/.test(k)).map(k => [k, `/${k.replace(/~/g, '~0').replace(/\//g, '~1')}`])),
        timeoutSeconds: Math.min(10, doc.maxTimeoutSeconds) } }, null, 2))
      setError(null)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Check the mapping JSON.') }
  }
  return <fieldset className="space-y-3 rounded-control border border-border p-4"><legend className="px-1 font-semibold">Map a source service to an approved operation</legend>
    <Label htmlFor="migration-service-task">Source service task</Label><Select id="migration-service-task" value={task} onChange={e => setTask(e.target.value)}>{tasks.map(t => <option key={t}>{t}</option>)}</Select>
    <Label htmlFor="migration-connection">Target connection</Label><Select id="migration-connection" value={key} onChange={e => { setKey(e.target.value); setOperation('') }}><option value="">Choose connection</option>{list.data?.items.map(c => <option key={c.key} value={c.key}>{c.name}</option>)}</Select>
    <Label htmlFor="migration-operation">Target operation</Label><Select id="migration-operation" value={operation} disabled={!definition} onChange={e => setOperation(e.target.value)}><option value="">Choose operation</option>{Object.entries(operations ?? {}).map(([name, op]) => <option key={name} value={name}>{op.label}</option>)}</Select>
    <p className="text-sm text-muted-foreground">Adds explicit request-field mappings using contract types and pins version {definition?.version ?? '—'}. Review field references and kept result names below. No external system is called.</p>
    <Button type="button" disabled={!operation} onClick={add}>Apply operation mapping</Button>
    {error ? <p role="alert">{error}</p> : null}
    {list.isError ? <QueryError error={list.error} onRetry={() => void list.refetch()} what="connections" /> : null}
    {detail.isError ? <QueryError error={detail.error} onRetry={() => void detail.refetch()} what="the connection contract" /> : null}
  </fieldset>
}
