import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import type { components } from '@/api/schema'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/input'
import { CaseDataFields, inputValues, typedValues, type DataInputs } from './CaseDataFields'
import { getCaseData, updateCaseData } from './api'

function DataEditor({ data, ws, instanceId, busy, onPending }: { data: components['schemas']['CaseData']; ws: string; instanceId: string; busy: boolean; onPending: (value: boolean) => void }) {
  const [values, setValues] = useState<DataInputs>(() => inputValues(data.values))
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const id = useId(), client = useQueryClient()
  const save = async () => {
    if (!data || !reason.trim()) return
    onPending(true); setError(null)
    try {
      const updated = await updateCaseData(ws, instanceId, data.version, reason.trim(), typedValues(data.fields, values))
      client.setQueryData(['ws', ws, 'case', instanceId], updated)
      void client.invalidateQueries({ queryKey: ['ws', ws, 'cases'] })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The case data changed. Refresh and try again.')
      void client.invalidateQueries({ queryKey: ['ws', ws, 'case', instanceId], exact: true })
    } finally { onPending(false) }
  }
  return <form className="space-y-4" onSubmit={event => { event.preventDefault(); void save() }}>
    {error ? <Alert tone="danger">{error}</Alert> : null}
    <CaseDataFields fields={data.fields} values={values} onChange={setValues} disabled={busy} readOnly={!data.canEdit} />
    {data.canEdit ? <><Label htmlFor={`${id}-reason`}>Reason for data change</Label><Textarea id={`${id}-reason`} required maxLength={1000} value={reason} disabled={busy} onChange={event => setReason(event.target.value)} /><Button type="submit" disabled={busy || !reason.trim()} loading={busy}>Save case data</Button></> : null}
  </form>
}

export function CaseDataPanel({ ws, instanceId, version, busy, onPending }: { ws: string; instanceId: string; version: number; busy: boolean; onPending: (value: boolean) => void }) {
  const [expanded, setExpanded] = useState(false)
  const id = useId()
  const query = useQuery({ queryKey: ['ws', ws, 'case', instanceId, 'data', version], queryFn: () => getCaseData(ws, instanceId), enabled: expanded, retry: false })
  return <section aria-label="Case data" className="space-y-3">
    <Button variant="secondary" aria-expanded={expanded} aria-controls={id} onClick={() => setExpanded(value => !value)}>{expanded ? 'Hide case data' : 'Show case data'}</Button>
    <div id={id} hidden={!expanded} className="space-y-4">
      {expanded && query.isPending ? <LoadingBlock label="Loading case data" /> : null}
      {query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="case data with your current access" /> : null}
      {query.data ? <>
        <h2>Case data</h2>
        {query.data.fields.length ? <DataEditor key={`${instanceId}:${query.data.version}`} data={query.data} ws={ws} instanceId={instanceId} busy={busy} onPending={onPending} /> : <p>This definition has no case data fields.</p>}
        {query.data.changes.length ? <section aria-label="Case data history" className="space-y-3"><h3>Data changes</h3><ol className="space-y-3">{query.data.changes.map(change => <li key={change.version} className="rounded border p-3"><p>Case version {change.version}</p><dl>{query.data.fields.filter(field => change.before[field.key] !== change.after[field.key]).map(field => <div key={field.key}><dt className="font-medium">{field.label}</dt><dd className="whitespace-pre-wrap break-words">{String(change.before[field.key] ?? 'No value')} → {String(change.after[field.key] ?? 'No value')}</dd></div>)}</dl></li>)}</ol></section> : null}
      </> : null}
    </div>
  </section>
}
