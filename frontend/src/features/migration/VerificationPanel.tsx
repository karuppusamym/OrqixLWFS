import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { reasonLabel } from './reasonLabels'

type Job = components['schemas']['MigrationJob']
type Issue = components['schemas']['CompatibilityIssue']

const keyOf = (i: Issue) => `${i.sourceElementId}\u0000${i.reasonCode}\u0000${i.sourcePath ?? ''}`

/** Why this person cannot acknowledge verify items, or null when the server would allow it. The server decides. */
export function verifyBlock(job: Job, mayPublish: boolean): string | null {
  if (!job.convertedWorkflowId) return 'Convert the source first. Changed behaviour is checked against the converted draft.'
  if (!mayPublish) return 'You need permission to publish workflows to verify changed behaviour.'
  return null
}

/**
 * "Converted but changed — verify" items of a converted migration (docs/17 "Severity model"). Conversion went ahead;
 * publishing the draft waits until a reviewer other than the mapper checks each change and acknowledges it with a reason.
 */
export function VerificationPanel({ job }: { job: Job }) {
  const { workspace, can } = useWorkspace()
  const client = useQueryClient()
  const announce = useAnnounce()
  const verify = job.report.issues.filter(i => i.severity === 'verify')
  const open = verify.filter(i => i.resolutionStatus === 'open')
  const [selected, setSelected] = useState<Set<string>>(() => new Set(open.map(keyOf)))
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (verify.length === 0) return null
  const blocked = verifyBlock(job, can('workflow.publish'))
  const done = job.report.verifications ?? []
  const submit = async () => {
    setPending(true); setError(null)
    try {
      const items = open.filter(i => selected.has(keyOf(i))).map(i => ({ sourceElementId: i.sourceElementId, reasonCode: i.reasonCode, ...(i.sourcePath ? { sourcePath: i.sourcePath } : {}) }))
      const updated = await endpoints.acknowledgeMigrationVerifications(workspace.id, job.id, { items, reason: reason.trim() })
      client.setQueryData(['ws', workspace.id, 'migration', job.id], updated)
      setReason('')
      announce(`${items.length} changed item(s) verified.`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The items could not be acknowledged.') }
    finally { setPending(false) }
  }
  return <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Converted but changed">
    <h3>Converted but changed — verify</h3>
    <p className="text-sm text-muted-foreground">These items converted, but the converter changed their behaviour slightly. Conversion is not blocked; publishing the draft is, until a reviewer other than the person who converted it checks each change and acknowledges it. The acknowledgement is audited and kept in the report and the migration ledger.</p>
    {open.length === 0 ? <Alert tone="success" live="off">Every changed item is verified. Publishing is no longer blocked by this report.</Alert>
      : <Alert tone="warning" live="off">{open.length} of {verify.length} changed item(s) still need verification. Publishing is refused until they are acknowledged.</Alert>}
    <ul className="space-y-2">{verify.map(issue => {
      const k = keyOf(issue)
      const record = done.filter(v => v.sourceElementId === issue.sourceElementId && v.reasonCode === issue.reasonCode && (v.sourcePath ?? null) === (issue.sourcePath ?? null)).at(-1)
      return <li key={k} className="rounded-card border border-border p-3 text-sm">
        {issue.resolutionStatus === 'open' && !blocked
          ? <Label className="flex items-start gap-2"><input type="checkbox" checked={selected.has(k)} onChange={e => setSelected(current => { const next = new Set(current); if (e.target.checked) next.add(k); else next.delete(k); return next })} /><span><span className="font-semibold">{reasonLabel(issue.reasonCode) ?? issue.reasonCode}</span> · {issue.sourceName ?? issue.sourceElementId}</span></Label>
          : <p><span className="font-semibold">{reasonLabel(issue.reasonCode) ?? issue.reasonCode}</span> · {issue.sourceName ?? issue.sourceElementId} · {issue.resolutionStatus}</p>}
        <p className="mt-1">{issue.explanation}</p>
        {record ? <p className="mt-1 text-xs text-muted-foreground">Verified by {record.acknowledgedByName ?? record.acknowledgedBy} at {record.acknowledgedAt}: {record.reason}</p> : null}
      </li>
    })}</ul>
    {open.length > 0 ? blocked ? <p className="text-sm text-muted-foreground">{blocked}</p> : <form className="space-y-2" onSubmit={e => { e.preventDefault(); void submit() }}>
      <Label htmlFor={`verify-reason-${job.id}`}>What did you check?</Label>
      <Textarea id={`verify-reason-${job.id}`} required maxLength={1000} rows={3} value={reason} onChange={e => setReason(e.target.value)} />
      {error ? <Alert tone="danger">{error}</Alert> : null}
      <Button type="submit" variant="primary" loading={pending} disabled={!reason.trim() || ![...selected].some(k => open.some(i => keyOf(i) === k))}>Acknowledge selected changes</Button>
    </form> : null}
  </section>
}
