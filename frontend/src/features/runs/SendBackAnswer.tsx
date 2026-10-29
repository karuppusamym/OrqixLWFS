import { useQuery, useQueryClient } from '@tanstack/react-query'
import { MessageSquareReply } from 'lucide-react'
import { useState } from 'react'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import { answerSendBack, listRunSendBacks, type SendBack } from '@/api/sendBacks'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatDateTime } from '@/lib/dates'

type Schema = { type?: string; enum?: unknown[] }

/**
 * Send-backs of a request (S116). The requester corrects the returned fields here; only those fields, as single values.
 * The server validates them against the request's form and records before and after.
 */
export function SendBackAnswer({ runId }: { runId: string }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const list = useQuery({ queryKey: ['sendBacks', ws, runId], queryFn: () => listRunSendBacks(ws, runId) })
  const items = list.data?.items ?? []
  if (items.length === 0) return null
  return <section aria-labelledby="send-backs-heading" className="mb-6 space-y-3 rounded-card border border-border bg-surface p-4">
    <h2 id="send-backs-heading" className="flex items-center gap-2 text-base"><MessageSquareReply className="size-4" aria-hidden="true" /> Sent back for corrections</h2>
    {items.map((item) => item.status === 'open' && item.canAnswer ? <Answer key={item.id} ws={ws} runId={runId} item={item} />
      : <p key={item.id} className="text-sm"><Badge tone={item.status === 'open' ? 'warning' : 'neutral'}>{item.status}</Badge> {item.stepLabel}: {item.reason} ({formatDateTime(item.sentAt)}{item.closedAt ? `, closed ${formatDateTime(item.closedAt)}` : ''})</p>)}
  </section>
}

function Answer({ ws, runId, item }: { ws: string; runId: string; item: SendBack }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(item.fields.map((field) => [field.key, field.value === null || field.value === undefined ? '' : String(field.value)])))
  const [note, setNote] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const typed = (schema: Schema | null, raw: string): unknown => schema?.type === 'number' || schema?.type === 'integer' ? Number(raw) : schema?.type === 'boolean' ? raw === 'true' : raw
  const changes = Object.fromEntries(item.fields.filter((field) => values[field.key] !== (field.value === null || field.value === undefined ? '' : String(field.value)))
    .map((field) => [field.key, typed(field.schema as Schema | null, values[field.key] ?? '')]))

  const submit = async () => {
    setPending(true)
    setError(null)
    try {
      await answerSendBack(ws, runId, item.id, { expectedVersion: item.taskVersion, changes, ...(note.trim() ? { note: note.trim() } : {}) }, key.keyFor(fingerprintOf(item.id, JSON.stringify(changes), note.trim())))
      await client.invalidateQueries()
      announce('Your corrections were sent.')
    } catch (cause) {
      setError(cause)
    } finally {
      setPending(false)
    }
  }

  return <div className="space-y-3 rounded border border-border p-3" data-testid="send-back-answer">
    <p><strong>{item.stepLabel}</strong> sent this request back {formatDateTime(item.sentAt)}{item.sentBy.displayName ? ` (${item.sentBy.displayName})` : ''}: {item.reason}</p>
    <div className="grid gap-3 sm:grid-cols-2">{item.fields.map((field) => {
      const schema = field.schema as Schema | null
      const id = `send-back-${item.id}-${field.key}`
      return <div key={field.key} className="min-w-0 space-y-1.5"><Label htmlFor={id}>{field.title}</Label>
        {schema?.enum ? <Select id={id} value={values[field.key] ?? ''} onChange={(event) => setValues((old) => ({ ...old, [field.key]: event.target.value }))}>{schema.enum.map((option) => <option key={String(option)} value={String(option)}>{String(option)}</option>)}</Select>
          : schema?.type === 'boolean' ? <Select id={id} value={values[field.key] ?? ''} onChange={(event) => setValues((old) => ({ ...old, [field.key]: event.target.value }))}><option value="true">Yes</option><option value="false">No</option></Select>
            : <Input id={id} type={schema?.type === 'number' || schema?.type === 'integer' ? 'number' : 'text'} step="any" value={values[field.key] ?? ''} onChange={(event) => setValues((old) => ({ ...old, [field.key]: event.target.value }))} />}
      </div>
    })}</div>
    <div><Label htmlFor={`send-back-${item.id}-note`}>Note for the approver (optional)</Label><Textarea id={`send-back-${item.id}-note`} rows={2} maxLength={500} value={note} onChange={(event) => setNote(event.target.value)} /></div>
    {error ? <ProblemAlert error={error} /> : null}
    <Button loading={pending} disabled={Object.keys(changes).length === 0} onClick={() => void submit()}>Send corrections</Button>
  </div>
}
