import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { exportCaseEvidence, getStageMail } from '@/api/endpoints'
import { useWorkspace } from '@/app/workspace'
import { Button } from '@/components/ui/button'
import { QueryError } from '@/components/common/QueryError'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { downloadBlob } from '@/lib/download'
import { formatDateTime } from '@/lib/dates'

const statusText = { queued: 'Queued', providerAccepted: 'Accepted by mail server', failed: 'Failed after three attempts', suppressed: 'Suppressed: task ended or recipient lost access', blocked: 'Needs operator attention' }
export function CaseControlsPanel({ runId }: { runId: string }) {
  const { workspace, can } = useWorkspace(); const ws = workspace.id
  const [open, setOpen] = useState(false); const [pending, setPending] = useState(false); const [error, setError] = useState<unknown>()
  const mail = useQuery({ queryKey: ['ws', ws, 'run', runId, 'mail'], queryFn: () => getStageMail(ws, runId), enabled: open && can('run.operate'), refetchInterval: open ? 10_000 : false })
  const download = async (format: 'json' | 'csv') => {
    setPending(true); setError(undefined)
    try { downloadBlob(await exportCaseEvidence(ws, runId, format), `case-${runId}-evidence.${format}`) }
    catch (e) { setError(e) } finally { setPending(false) }
  }
  if (!can('run.operate', 'audit.read')) return null
  return <section className="my-6 space-y-3 rounded border p-4" aria-label="Case controls and evidence">
    <h2 className="text-base font-semibold">Request controls and evidence</h2>
    {can('audit.read') ? <div className="flex gap-2"><Button disabled={pending} onClick={() => void download('json')}>Download JSON evidence</Button><Button disabled={pending} onClick={() => void download('csv')}>Download CSV evidence</Button></div> : null}
    {error ? <ProblemAlert error={error} /> : null}
    {can('run.operate') ? <>
      <Button aria-expanded={open} onClick={() => setOpen(v => !v)}>{open ? 'Hide stage email' : 'Inspect stage email'}</Button>
      {open ? <><p className="text-sm text-muted-foreground">Acceptance by a mail server does not confirm receipt. Queued messages wait while transport is disabled.</p>
        {mail.isPending ? <p role="status">Loading notification status…</p> : mail.isError ? <QueryError error={mail.error} onRetry={() => void mail.refetch()} /> : mail.data.items.length ?
          <ul className="space-y-2">{mail.data.items.map(item => <li key={item.id} className="text-sm">{item.event}: {statusText[item.status]} — {item.attempts} attempts, {formatDateTime(item.updatedAt)}{item.failureCode ? ` (${item.failureCode})` : ''}</li>)}</ul> : <p>No stage email is recorded for this request.</p>}
      </> : null}
    </> : null}
  </section>
}
